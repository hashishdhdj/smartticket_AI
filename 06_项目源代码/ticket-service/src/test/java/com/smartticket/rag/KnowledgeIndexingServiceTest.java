package com.smartticket.rag;

import com.smartticket.enums.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库索引编排的单元测试（阶段2 第5天）。
 *
 * <p>语料加载器用<b>真实实现</b>（它就是读 classpath 上的 7 份语料，既快又稳），
 * 向量化与向量库用 mock。这样测的是"编排逻辑"，而不是"接口通不通"——后者由集成测试管。</p>
 *
 * <p>本类里有三条断言是为了防"省钱没省对地方"：增量索引的目的是<b>避免重复调用按量计费的接口</b>，
 * 一旦判断条件写错（该跳过的没跳过、或该重算的没重算），后果分别是"多花钱"和"库里数据是旧的"，
 * 两者都不会报错。</p>
 */
class KnowledgeIndexingServiceTest {

    private static final String MODEL = "test-embedding";

    private final KnowledgeCorpusLoader corpusLoader = new KnowledgeCorpusLoader(new KnowledgeChunker());

    private EmbeddingService embeddingService;

    private KnowledgeVectorRepository vectorRepository;

    private KnowledgeIndexingService indexingService;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        vectorRepository = mock(KnowledgeVectorRepository.class);
        indexingService = new KnowledgeIndexingService(corpusLoader, embeddingService, vectorRepository);

        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.modelName()).thenReturn(MODEL);
    }

    // ------------------------------------------------------------ 降级

    @Test
    @DisplayName("向量化通道不可用时整次索引降级，且不碰向量库")
    void degradesWhenEmbeddingIsUnavailable() {
        when(embeddingService.isAvailable()).thenReturn(false);

        KnowledgeIndexingService.IndexingReport report = indexingService.indexAll();

        assertFalse(report.success());
        assertEquals(ErrorCode.AI_NOT_CONFIGURED, report.errorCode());
        assertTrue(report.totalChunks() > 0, "语料本身应能加载到，降级的是向量化环节");
        verify(vectorRepository, never()).upsert(anyList(), anyString());
    }

    @Test
    @DisplayName("向量库不可用时整次索引降级，不把异常抛给调用方（否则会拖垮启动）")
    void degradesWhenVectorStoreIsUnavailable() {
        when(vectorRepository.loadIndexedState()).thenThrow(new RuntimeException("模拟 PostgreSQL 未启动"));

        KnowledgeIndexingService.IndexingReport report = indexingService.indexAll();

        assertFalse(report.success());
        assertEquals(ErrorCode.VECTOR_STORE_UNAVAILABLE, report.errorCode());
        assertTrue(report.failureDetail().contains("PostgreSQL"));
    }

    // ------------------------------------------------------------ 增量判断

    @Test
    @DisplayName("内容与模型都没变时全部跳过，一条都不送给向量化接口（这是省钱的关键断言）")
    void skipsEverythingWhenNothingChanged() {
        List<KnowledgeChunk> chunks = corpusLoader.loadAll();
        when(vectorRepository.loadIndexedState()).thenReturn(indexedStateOf(chunks, MODEL, null));

        KnowledgeIndexingService.IndexingReport report = indexingService.indexAll();

        assertTrue(report.success());
        assertEquals(chunks.size(), report.totalChunks());
        assertEquals(chunks.size(), report.skipped());
        assertEquals(0, report.written());
        verify(embeddingService, never()).embed(anyList());
    }

    @Test
    @DisplayName("只有内容变化的切片被重新向量化，其余不动")
    void reindexesOnlyTheChangedChunk() {
        List<KnowledgeChunk> chunks = corpusLoader.loadAll();
        KnowledgeChunk changed = chunks.get(0);
        when(vectorRepository.loadIndexedState())
                .thenReturn(indexedStateOf(chunks, MODEL, changed));
        when(embeddingService.embed(anyList()))
                .thenAnswer(invocation -> okVectorsFor(invocation.getArgument(0)));
        when(vectorRepository.upsert(anyList(), anyString())).thenReturn(1);

        KnowledgeIndexingService.IndexingReport report = indexingService.indexAll();

        assertTrue(report.success());
        assertEquals(chunks.size() - 1, report.skipped());
        assertEquals(1, report.written());

        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embed(texts.capture());
        assertEquals(1, texts.getValue().size(), "只应把变化的那一条送出去");
        assertEquals(changed.content(), texts.getValue().get(0));
        assertFalse(texts.getValue().get(0).startsWith(changed.sectionTitle()),
                "标题不进向量：第5周任务 7 实测该配方为负结果（见 embeddingText 的注释）");
    }

    @Test
    @DisplayName("库里是实验配方（标题 + 正文）时全部重建：回退配方必须自动生效，否则旧向量会一直留在库里")
    void reindexesEverythingWhenEmbeddingRecipeChanged() {
        List<KnowledgeChunk> chunks = corpusLoader.loadAll();
        // 模拟"标题 + 正文"那版实验留下的状态：正文一个字没变，但配方已经不是它了。
        // 这正是第5周任务 7 回退实验后真实库里的样子
        when(vectorRepository.loadIndexedState()).thenReturn(titleAugmentedStateOf(chunks, MODEL));
        when(embeddingService.embed(anyList()))
                .thenAnswer(invocation -> okVectorsFor(invocation.getArgument(0)));
        when(vectorRepository.upsert(anyList(), anyString())).thenReturn(chunks.size());

        KnowledgeIndexingService.IndexingReport report = indexingService.indexAll();

        assertTrue(report.success());
        assertEquals(0, report.skipped(), "配方变了就必须全部重建");
        assertEquals(chunks.size(), report.written());
    }

    @Test
    @DisplayName("换了嵌入模型时全部重新向量化（只看内容哈希会漏掉这种情况，导致库里混装两个模型的向量）")
    void reindexesEverythingWhenEmbeddingModelChanged() {
        List<KnowledgeChunk> chunks = corpusLoader.loadAll();
        // 内容哈希都对得上，只有模型名是旧的
        when(vectorRepository.loadIndexedState()).thenReturn(indexedStateOf(chunks, "previous-model", null));
        when(embeddingService.embed(anyList()))
                .thenAnswer(invocation -> okVectorsFor(invocation.getArgument(0)));
        when(vectorRepository.upsert(anyList(), anyString())).thenReturn(chunks.size());

        KnowledgeIndexingService.IndexingReport report = indexingService.indexAll();

        assertTrue(report.success());
        assertEquals(0, report.skipped());
        assertEquals(chunks.size(), report.written());
    }

    // ------------------------------------------------------------ 工具

    /**
     * 构造"库里已有的索引状态"。
     *
     * @param changed 需要被视作"内容已变化"的那一条；传 {@code null} 表示全部视为未变化
     */
    private static Map<KnowledgeVectorRepository.ChunkKey, KnowledgeVectorRepository.IndexedState>
    indexedStateOf(List<KnowledgeChunk> chunks, String model, KnowledgeChunk changed) {
        Map<KnowledgeVectorRepository.ChunkKey, KnowledgeVectorRepository.IndexedState> state = new HashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            String hash = chunk == changed ? "已过期的哈希" : sha256Hex(chunk.content());
            state.put(new KnowledgeVectorRepository.ChunkKey(chunk.sourceFile(), chunk.chunkIndex()),
                    new KnowledgeVectorRepository.IndexedState(hash, model));
        }
        return state;
    }

    /** 模拟"标题 + 正文"那版实验配方留下的状态 */
    private static Map<KnowledgeVectorRepository.ChunkKey, KnowledgeVectorRepository.IndexedState>
    titleAugmentedStateOf(List<KnowledgeChunk> chunks, String model) {
        Map<KnowledgeVectorRepository.ChunkKey, KnowledgeVectorRepository.IndexedState> state = new HashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            state.put(new KnowledgeVectorRepository.ChunkKey(chunk.sourceFile(), chunk.chunkIndex()),
                    new KnowledgeVectorRepository.IndexedState(
                            sha256Hex(chunk.sectionTitle() + "\n" + chunk.content()), model));
        }
        return state;
    }

    private static EmbeddingResult okVectorsFor(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            vectors.add(new float[]{i});
        }
        return EmbeddingResult.ok(vectors, 1, MODEL);
    }

    /** 独立实现一遍 SHA-256：若直接复用被测代码的私有方法，就等于用被测逻辑验证被测逻辑 */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
