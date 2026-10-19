package com.smartticket.rag.impl;

import com.smartticket.enums.ErrorCode;
import com.smartticket.rag.EmbeddingResult;
import com.smartticket.rag.KnowledgeVectorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 向量化服务的单元测试（阶段2 第5天，章程第4周任务 4）。
 *
 * <p>重点不在"能算出向量"（那是模型的事），而在三件容易出错、且出错后很难察觉的事：</p>
 * <ol>
 *   <li><b>没配密钥时不能发网络请求</b>——否则每次启动都要白等一次连接超时；</li>
 *   <li><b>分批的边界</b>——服务方单次上限是 10 条，第 11 条有没有进对批次；</li>
 *   <li><b>返回与输入必须一一对应</b>——条数或维度对不上时若继续返回，
 *       调用方会拿着错位的向量去写库，而这种错误在检索时只会表现为"结果有点怪"，几乎不可能定位。</li>
 * </ol>
 */
class EmbeddingServiceImplTest {

    private static final int DIMENSIONS = 4;

    private EmbeddingModel embeddingModel;

    private KnowledgeVectorProperties properties;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        properties = new KnowledgeVectorProperties();
        properties.getEmbedding().setApiKey("sk-test-key");
        properties.getEmbedding().setModel("test-embedding");
        properties.getEmbedding().setDimensions(DIMENSIONS);
        properties.getEmbedding().setBatchSize(10);
    }

    // ------------------------------------------------------------ 降级

    @Test
    @DisplayName("未配置密钥时判为不可用，且不发起任何模型调用")
    void placeholderKeyDegradesWithoutCallingModel() {
        properties.getEmbedding().setApiKey("not-configured");
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(embeddingModel, properties);

        assertFalse(service.isAvailable());

        EmbeddingResult result = service.embed(List.of("任意文本"));

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_NOT_CONFIGURED, result.errorCode());
        assertEquals(0, result.costMs(), "未发请求时耗时应为 0，这也是「没有真的调用」的证据");
        verifyNoInteractions(embeddingModel);
    }

    @Test
    @DisplayName("模型抛异常时就地降级为调用失败，不把异常抛给上层")
    void degradesWhenModelThrows() {
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("模拟连接超时"));
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(embeddingModel, properties);

        EmbeddingResult result = service.embed(List.of("文本"));

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_CALL_FAILED, result.errorCode());
        assertTrue(result.failureDetail().contains("RuntimeException"));
    }

    @Test
    @DisplayName("空输入不是错误：返回成功且向量为空，不调用模型")
    void emptyInputIsSuccessWithoutCallingModel() {
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(embeddingModel, properties);

        assertTrue(service.embed(List.of()).success());
        assertTrue(service.embed(null).success());
        verifyNoInteractions(embeddingModel);
    }

    // ------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("返回的向量与入参一一对应且顺序一致")
    void returnsVectorsAlignedWithInputOrder() {
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> vectorsFor(invocation.getArgument(0)));
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(embeddingModel, properties);

        EmbeddingResult result = service.embed(List.of("第一段", "第二段", "第三段"));

        assertTrue(result.success());
        assertEquals(3, result.embeddings().size());
        // 桩实现把"第 i 条"填成首元素 i，据此断言顺序确实保持住了
        assertEquals(0f, result.embeddings().get(0)[0]);
        assertEquals(1f, result.embeddings().get(1)[0]);
        assertEquals(2f, result.embeddings().get(2)[0]);
        assertEquals("test-embedding", result.model());
    }

    @Test
    @DisplayName("按配置的批量分批：25 条文本、批量 10，应发起 3 次调用且总数不缺")
    void splitsIntoConfiguredBatches() {
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> vectorsFor(invocation.getArgument(0)));
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(embeddingModel, properties);

        List<String> texts = IntStream.range(0, 25).mapToObj(i -> "第" + i + "段").toList();
        EmbeddingResult result = service.embed(texts);

        assertTrue(result.success());
        assertEquals(25, result.embeddings().size(), "分批不应丢条");
        // 10 + 10 + 5 = 3 次。若忘了分批，服务方会因超过单次上限而整批失败
        verify(embeddingModel, times(3)).embed(anyList());
    }

    // ------------------------------------------------------------ 返回内容不合约定

    @Test
    @DisplayName("返回条数与输入不一致时判为格式不符：无法判断哪个向量属于哪条文本")
    void failsWhenReturnedCountMismatchesInput() {
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[DIMENSIONS]));
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(embeddingModel, properties);

        EmbeddingResult result = service.embed(List.of("一", "二", "三"));

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_RESPONSE_UNPARSABLE, result.errorCode());
        assertTrue(result.failureDetail().contains("3"));
    }

    @Test
    @DisplayName("返回维度与配置不一致时判为格式不符（写库前就拦住，而不是等 PostgreSQL 报错）")
    void failsWhenReturnedDimensionsMismatchConfig() {
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                vectors.add(new float[DIMENSIONS + 1]);
            }
            return vectors;
        });
        EmbeddingServiceImpl service = new EmbeddingServiceImpl(embeddingModel, properties);

        EmbeddingResult result = service.embed(List.of("一"));

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_RESPONSE_UNPARSABLE, result.errorCode());
        assertTrue(result.failureDetail().contains(String.valueOf(DIMENSIONS + 1)));
    }

    // ------------------------------------------------------------ 工具

    /** 桩实现：第 i 条文本产出「首元素为 i、其余为 0」的向量，便于断言顺序 */
    private static List<float[]> vectorsFor(List<String> batch) {
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            float[] vector = new float[DIMENSIONS];
            vector[0] = i;
            vectors.add(vector);
        }
        return vectors;
    }

}
