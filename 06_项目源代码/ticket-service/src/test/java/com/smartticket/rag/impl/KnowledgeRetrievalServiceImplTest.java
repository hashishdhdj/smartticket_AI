package com.smartticket.rag.impl;

import com.smartticket.enums.ErrorCode;
import com.smartticket.rag.EmbeddingResult;
import com.smartticket.rag.EmbeddingService;
import com.smartticket.rag.KnowledgeChunk;
import com.smartticket.rag.KnowledgeVectorProperties;
import com.smartticket.rag.KnowledgeVectorRepository;
import com.smartticket.rag.RetrievalHit;
import com.smartticket.rag.RetrievalResult;
import com.smartticket.rag.RetrievalSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索服务的单元测试（阶段2 第6天，章程第4周任务 6/7）。
 *
 * <p>重点在三件"出错了也不报错、只会让结果变得不可解释"的事：</p>
 * <ol>
 *   <li><b>查询向量与库中向量必须同源</b>——模型不一致时要拦住，而且要在调用嵌入接口
 *       <b>之前</b>拦住（否则白花一次钱才拿到一个没用的向量）；</li>
 *   <li><b>降级要分得清原因</b>——未配密钥、库是空的、模型不一致、库连不上，
 *       四种情况处置动作完全不同；</li>
 *   <li><b>两条路各自独立降级</b>——嵌入服务挂了，关键词路仍然要能用。</li>
 * </ol>
 */
class KnowledgeRetrievalServiceImplTest {

    private static final String MODEL = "test-embedding";

    private EmbeddingService embeddingService;

    private KnowledgeVectorRepository vectorRepository;

    private KnowledgeVectorProperties properties;

    private KnowledgeRetrievalServiceImpl service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        vectorRepository = mock(KnowledgeVectorRepository.class);
        properties = new KnowledgeVectorProperties();
        properties.getRetrieval().setTopK(5);
        service = new KnowledgeRetrievalServiceImpl(embeddingService, vectorRepository, properties);

        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.modelName()).thenReturn(MODEL);
    }

    // ------------------------------------------------------------ 空输入

    @Test
    @DisplayName("空查询不是错误：两条路都返回成功且结果为空，且不碰库")
    void blankQueryIsSuccessWithNoHits() {
        for (RetrievalResult result : List.of(service.searchByVector("  "), service.searchByKeyword(null))) {
            assertTrue(result.success());
            assertTrue(result.hits().isEmpty());
        }
        verify(vectorRepository, never()).searchByVector(any(), anyInt());
        verify(vectorRepository, never()).searchByKeyword(anyString(), anyDouble(), anyInt());
    }

    // ------------------------------------------------------------ 向量路：四种降级

    @Test
    @DisplayName("未配置嵌入模型时向量路降级为 10006，且不查库")
    void vectorPathDegradesWhenEmbeddingUnavailable() {
        when(embeddingService.isAvailable()).thenReturn(false);

        RetrievalResult result = service.searchByVector("如何重置密码");

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_NOT_CONFIGURED, result.errorCode());
        assertEquals(RetrievalSource.VECTOR, result.source());
        verify(vectorRepository, never()).searchByVector(any(), anyInt());
    }

    @Test
    @DisplayName("向量库为空时降级为 10009，并提示先建索引")
    void vectorPathDegradesWhenStoreIsEmpty() {
        when(vectorRepository.distinctModels()).thenReturn(Set.of());

        RetrievalResult result = service.searchByVector("如何重置密码");

        assertFalse(result.success());
        assertEquals(ErrorCode.VECTOR_STORE_UNAVAILABLE, result.errorCode());
        assertTrue(result.failureDetail().contains("index-on-startup"));
    }

    @Test
    @DisplayName("库中模型与当前配置不一致时降级为 10010，且在调用嵌入接口之前就拦住（不白花钱）")
    void vectorPathStopsOnModelMismatchBeforeCallingVendor() {
        when(vectorRepository.distinctModels()).thenReturn(Set.of("some-other-model"));

        RetrievalResult result = service.searchByVector("如何重置密码");

        assertFalse(result.success());
        assertEquals(ErrorCode.VECTOR_MODEL_MISMATCH, result.errorCode());
        // 这条断言是重点：矛盾在"算之前"就发现了，不该先把查询送去向量化（那是要计费的）
        verify(embeddingService, never()).embed(anyList());
        verify(vectorRepository, never()).searchByVector(any(), anyInt());
    }

    @Test
    @DisplayName("库里混着多个模型的向量时同样判为 10010（混装无法比较）")
    void vectorPathStopsWhenStoreHasMixedModels() {
        when(vectorRepository.distinctModels()).thenReturn(Set.of(MODEL, "another-model"));

        RetrievalResult result = service.searchByVector("如何重置密码");

        assertEquals(ErrorCode.VECTOR_MODEL_MISMATCH, result.errorCode());
        verify(embeddingService, never()).embed(anyList());
    }

    @Test
    @DisplayName("向量库连不上时降级为 10009，不把异常抛给上层")
    void vectorPathDegradesWhenStoreThrows() {
        when(vectorRepository.distinctModels()).thenThrow(new RuntimeException("模拟 PostgreSQL 未启动"));

        RetrievalResult result = service.searchByVector("如何重置密码");

        assertFalse(result.success());
        assertEquals(ErrorCode.VECTOR_STORE_UNAVAILABLE, result.errorCode());
        assertTrue(result.failureDetail().contains("PostgreSQL"));
    }

    @Test
    @DisplayName("查询向量化失败时透传失败原因（调用失败就是调用失败，不该混成库的问题）")
    void vectorPathPropagatesEmbeddingFailureCode() {
        when(vectorRepository.distinctModels()).thenReturn(Set.of(MODEL));
        when(embeddingService.embed(anyList()))
                .thenReturn(EmbeddingResult.failed(ErrorCode.AI_CALL_FAILED, "模拟超时", 12, MODEL));

        RetrievalResult result = service.searchByVector("如何重置密码");

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_CALL_FAILED, result.errorCode());
        verify(vectorRepository, never()).searchByVector(any(), anyInt());
    }

    // ------------------------------------------------------------ 向量路：成功

    @Test
    @DisplayName("成功路径：用嵌入结果作为查询向量，并把配置的 topK 传给仓库")
    void vectorPathUsesEmbeddingResultAsQueryVector() {
        when(vectorRepository.distinctModels()).thenReturn(Set.of(MODEL));
        float[] queryVector = {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed(anyList()))
                .thenReturn(EmbeddingResult.ok(List.of(queryVector), 5, MODEL));
        RetrievalHit hit = hit("faq/01-账户问题.md", 0, 0.87);
        when(vectorRepository.searchByVector(any(), anyInt())).thenReturn(List.of(hit));

        RetrievalResult result = service.searchByVector("如何重置密码");

        assertTrue(result.success());
        assertEquals(1, result.hits().size());
        assertEquals(RetrievalSource.VECTOR, result.source());

        ArgumentCaptor<float[]> captor = ArgumentCaptor.forClass(float[].class);
        verify(vectorRepository).searchByVector(captor.capture(), eq(5));
        // 必须是嵌入返回的那个向量本身：用别的向量去查库，结果就没有意义了
        assertEquals(queryVector, captor.getValue());
    }

    @Test
    @DisplayName("成功路径：把查询文本整体送去向量化，不做任何切分")
    void vectorPathEmbedsWholeQuery() {
        when(vectorRepository.distinctModels()).thenReturn(Set.of(MODEL));
        when(embeddingService.embed(anyList()))
                .thenReturn(EmbeddingResult.ok(List.of(new float[]{1f}), 5, MODEL));
        when(vectorRepository.searchByVector(any(), anyInt())).thenReturn(List.of());

        service.searchByVector("登录时提示验证码异常");

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingService).embed(captor.capture());
        assertEquals(List.of("登录时提示验证码异常"), captor.getValue());
    }

    // ------------------------------------------------------------ 关键词路

    @Test
    @DisplayName("关键词路成功：把配置的 minScore 与 topK 传给仓库")
    void keywordPathPassesConfiguredThresholds() {
        properties.getRetrieval().setTopK(3);
        properties.getRetrieval().setKeywordMinScore(0.05);
        service = new KnowledgeRetrievalServiceImpl(embeddingService, vectorRepository, properties);
        when(vectorRepository.searchByKeyword(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(hit("tickets/01-账户问题.md", 0, 0.2)));

        RetrievalResult result = service.searchByKeyword("验证码异常");

        assertTrue(result.success());
        assertEquals(RetrievalSource.KEYWORD, result.source());
        verify(vectorRepository).searchByKeyword("验证码异常", 0.05, 3);
    }

    @Test
    @DisplayName("嵌入服务不可用时关键词路仍然可用——两条路独立降级，一条断了还有另一条")
    void keywordPathStillWorksWhenEmbeddingIsDown() {
        when(embeddingService.isAvailable()).thenReturn(false);
        when(vectorRepository.searchByKeyword(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(hit("faq/01-账户问题.md", 0, 0.2)));

        RetrievalResult keyword = service.searchByKeyword("验证码异常");
        RetrievalResult vector = service.searchByVector("验证码异常");

        assertTrue(keyword.success(), "关键词路只依赖字符匹配，不该受嵌入服务影响");
        assertEquals(1, keyword.hits().size());
        assertFalse(vector.success(), "向量路依赖嵌入服务，应当降级");
    }

    @Test
    @DisplayName("关键词路查库异常时降级为 10009")
    void keywordPathDegradesWhenStoreThrows() {
        when(vectorRepository.searchByKeyword(anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("模拟 PostgreSQL 未启动"));

        RetrievalResult result = service.searchByKeyword("验证码异常");

        assertFalse(result.success());
        assertEquals(ErrorCode.VECTOR_STORE_UNAVAILABLE, result.errorCode());
        assertTrue(result.hits().isEmpty());
    }

    // ------------------------------------------------------------ 工具

    private static RetrievalHit hit(String sourceFile, int chunkIndex, double score) {
        KnowledgeChunk chunk = new KnowledgeChunk(sourceFile, "测试文档", "测试小节", chunkIndex, "正文");
        return new RetrievalHit(chunk, score);
    }

}
