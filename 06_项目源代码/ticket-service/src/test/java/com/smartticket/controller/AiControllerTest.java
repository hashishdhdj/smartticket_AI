package com.smartticket.controller;

import com.smartticket.ai.AiCallResult;
import com.smartticket.ai.AiChatService;
import com.smartticket.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 分析接口的集成测试：走真实 MVC 链路（Controller → Service → 解析器），
 * 只把 {@link AiChatService} 换成 mock。
 *
 * <p><b>为什么必须 mock 模型这一层</b>：本机 {@code application-local.yml} 里配着真实密钥，
 * 不 mock 的话每次跑测试都会真的调用厂商接口——既花钱，又让结果依赖网络与模型心情
 * （同样的输入今天返回「功能异常」、明天可能返回「账户问题」，测试随之红绿不定）。
 * 用 {@code @MockitoBean} 顶替后，接口契约与降级行为都被固定住，
 * "真实模型会不会守规矩"则交给 {@code AiRealVendorSmokeTest} 在需要时人工验证。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiControllerTest {

    private static final String VALID_JSON = """
            {"category":"功能异常","priority":"HIGH","confidence":0.88,
             "keywords":["登录","验证码"],"reason":"核心登录功能不可用。"}
            """;

    private static final String VALID_REQUEST = """
            {"title":"登录页提示验证码异常","description":"输入正确验证码仍提示错误"}
            """;

    @MockitoBean
    private AiChatService aiChatService;

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------ 成功

    @Test
    @DisplayName("分析成功：HTTP 200 + code=0 + degraded=false，各字段来自解析结果")
    void analyzeReturnsStructuredResult() throws Exception {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok(VALID_JSON, 553L, "deepseek-chat"));

        mockMvc.perform(post("/api/ai/tickets/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.degraded").value(false))
                .andExpect(jsonPath("$.data.category").value("功能异常"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.confidence").value(0.88))
                .andExpect(jsonPath("$.data.keywords[0]").value("登录"))
                .andExpect(jsonPath("$.data.promptVersion").value("ticket-classify-v1"))
                .andExpect(jsonPath("$.data.model").value("deepseek-chat"));
    }

    // ------------------------------------------------------------ 三种降级：一律 code=0

    @Test
    @DisplayName("AI 调用失败：仍是 code=0，用 degraded 标记说明，而不是返回错误码")
    void callFailureIsMarkedAsDegradedNotError() throws Exception {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.failed(ErrorCode.AI_CALL_FAILED, "Read timed out", 20000L));

        mockMvc.perform(post("/api/ai/tickets/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.degradedReasonCode").value(10007))
                .andExpect(jsonPath("$.data.degradedReason").value(ErrorCode.AI_CALL_FAILED.getMessage()))
                // 业务字段不该出现：项目启用了 non_null，降级时它们是 null，不会进 JSON。
                // 断言"不存在"而不是"等于 null"，才是真的在验证契约
                .andExpect(jsonPath("$.data.category").doesNotExist())
                .andExpect(jsonPath("$.data.priority").doesNotExist())
                .andExpect(jsonPath("$.data.confidence").doesNotExist());
    }

    @Test
    @DisplayName("未配置 AI：降级为 10006")
    void notConfiguredIsMarkedWithOwnCode() throws Exception {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.failed(ErrorCode.AI_NOT_CONFIGURED, "未配置模型密钥", 1L));

        mockMvc.perform(post("/api/ai/tickets/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.degradedReasonCode").value(10006));
    }

    @Test
    @DisplayName("模型返回不合规：降级为 10008（与调用失败的码分开，便于分别排查）")
    void unparsableResponseIsMarkedWithOwnCode() throws Exception {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok("这属于功能异常吧。", 400L, "deepseek-chat"));

        mockMvc.perform(post("/api/ai/tickets/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.degradedReasonCode").value(10008));
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("标题为空：走既有的参数校验通道返回 40000，不进入 AI 层")
    void blankTitleIsRejectedByValidation() throws Exception {
        String blankTitle = """
                {"title":"   ","description":"描述"}
                """;

        mockMvc.perform(post("/api/ai/tickets/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankTitle))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("描述可以为空：描述不是必填项，只有标题必填")
    void descriptionIsOptional() throws Exception {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok(VALID_JSON, 300L, "deepseek-chat"));

        String titleOnly = """
                {"title":"登录页提示验证码异常"}
                """;

        mockMvc.perform(post("/api/ai/tickets/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(titleOnly))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.degraded").value(false));
    }

}
