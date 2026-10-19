package com.smartticket.controller;

import com.smartticket.rag.RagAnswer;
import com.smartticket.rag.RagAnswerService;
import com.smartticket.rag.RagSource;
import com.smartticket.rag.RetrievalSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库问答接口的契约测试：走真实 MVC 链路（Controller → VO 映射 → 序列化），
 * 只把 {@link RagAnswerService} 换成 mock。
 *
 * <p><b>为什么 mock 服务层而不是检索层</b>：检索层依赖 PostgreSQL 与真实向量，
 * 结果取决于索引是否建过、建的是哪一版——那会让"接口契约"这个测试目标变成
 * "数据碰巧对不对"。契约就该只验证：路径对不对、参数校验走没走通、
 * 降级时返回什么形态、字段映射有没有错。检索本身由
 * {@code RagAnswerServiceImplTest} 与 {@code RagRetrievalEvaluationTest} 各自覆盖。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeControllerTest {

    private static final String VALID_REQUEST = """
            {"query":"重复扣款如何申请退款"}
            """;

    @MockitoBean
    private RagAnswerService ragAnswerService;

    @Autowired
    private MockMvc mockMvc;

    private void givenAnswer(RagAnswer answer) {
        when(ragAnswerService.answer(anyString())).thenReturn(answer);
    }

    private static RagAnswer answer(boolean matched, double confidence, boolean needHuman,
                                    String suggestion, List<String> questions,
                                    List<RagSource> sources, List<RetrievalSource> degraded) {
        return new RagAnswer(matched, confidence, sources, suggestion, questions, needHuman, degraded, 1234L);
    }

    private static RagSource source(String file, String title, double score) {
        return new RagSource(file, title, score, Set.of(RetrievalSource.VECTOR, RetrievalSource.KEYWORD), "摘录…");
    }

    // ------------------------------------------------------------ 有把握

    @Test
    @DisplayName("有把握：HTTP 200 + code=0 + needHuman=false，建议、相似问题、来源齐备")
    void answerableReturnsFullResult() throws Exception {
        givenAnswer(answer(true, 0.8130, false,
                "请先收集两笔扣款的渠道流水号与金额，提交工单由人工核对后原路退款。",
                List.of("重复扣款如何申请退款？", "订单已失效怎么办？"),
                List.of(source("faq/02-支付问题.md", "重复扣款如何申请退款", 0.8130)),
                List.of()));

        mockMvc.perform(post("/api/ai/knowledge/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.needHuman").value(false))
                .andExpect(jsonPath("$.data.confidence").value(0.8130))
                .andExpect(jsonPath("$.data.suggestion").exists())
                .andExpect(jsonPath("$.data.similarQuestions[0]").value("重复扣款如何申请退款？"))
                .andExpect(jsonPath("$.data.sources[0].sourceFile").value("faq/02-支付问题.md"))
                .andExpect(jsonPath("$.data.sources[0].title").value("重复扣款如何申请退款"))
                .andExpect(jsonPath("$.data.sources[0].score").value(0.8130))
                .andExpect(jsonPath("$.data.degradedSources").isEmpty())
                .andExpect(jsonPath("$.data.costMs").value(1234));
    }

    // ------------------------------------------------------------ 转人工：一律 code=0

    @Test
    @DisplayName("低置信度：matched=true 但 needHuman=true，建议与相似问题不出现（非 null 字段才进 JSON）")
    void lowConfidenceIsMarkedNeedHumanWithoutSuggestion() throws Exception {
        givenAnswer(answer(true, 0.5414, true, null, List.of(),
                List.of(source("faq/02-支付问题.md", "如何申请开具发票", 0.5414)),
                List.of()));

        mockMvc.perform(post("/api/ai/knowledge/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.needHuman").value(true))
                // 来源必须照常返回——不生成建议不等于没有依据可用（阶段2 约束第 7 条）
                .andExpect(jsonPath("$.data.sources[0].title").value("如何申请开具发票"))
                .andExpect(jsonPath("$.data.suggestion").doesNotExist())
                .andExpect(jsonPath("$.data.similarQuestions").isEmpty());
    }

    @Test
    @DisplayName("检索降级：仍是 code=0，needHuman=true 并报出是哪条路挂了")
    void degradedRetrievalIsMarkedWithoutErrorCode() throws Exception {
        givenAnswer(answer(false, 0.0, true, null, List.of(), List.of(),
                List.of(RetrievalSource.VECTOR)));

        mockMvc.perform(post("/api/ai/knowledge/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.needHuman").value(true))
                .andExpect(jsonPath("$.data.degradedSources[0]").value("VECTOR"))
                .andExpect(jsonPath("$.data.suggestion").doesNotExist());
    }

    @Test
    @DisplayName("知识库里没有：matched=false、needHuman=true、来源为空")
    void missReturnsEmptySources() throws Exception {
        givenAnswer(answer(false, 0.0, true, null, List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/ai/knowledge/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.matched").value(false))
                .andExpect(jsonPath("$.data.needHuman").value(true))
                .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("问题为空：走既有的参数校验通道返回 40000，不进入检索层")
    void blankQueryIsRejectedByValidation() throws Exception {
        String blankQuery = """
                {"query":"   "}
                """;

        mockMvc.perform(post("/api/ai/knowledge/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankQuery))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("问题超长：返回 40000（过长会稀释检索，不是'更全'）")
    void overlongQueryIsRejectedByValidation() throws Exception {
        String overlong = """
                {"query":"%s"}
                """.formatted("问".repeat(501));

        mockMvc.perform(post("/api/ai/knowledge/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overlong))
                .andExpect(jsonPath("$.code").value(40000));
    }

}
