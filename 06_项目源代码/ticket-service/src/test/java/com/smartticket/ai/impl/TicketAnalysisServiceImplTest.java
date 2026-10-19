package com.smartticket.ai.impl;

import com.smartticket.ai.AiCallResult;
import com.smartticket.ai.AiChatService;
import com.smartticket.ai.AiClassificationParser;
import com.smartticket.ai.AiPromptFactory;
import com.smartticket.dto.TicketAnalyzeDTO;
import com.smartticket.enums.ErrorCode;
import com.smartticket.vo.TicketAnalysisVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketAnalysisServiceImpl} 的纯单元测试：不连库、不连缓存、不访问模型。
 *
 * <p>只把 {@link AiChatService} 换成 mock（模型是唯一的外部依赖），
 * {@link AiPromptFactory} 与 {@link AiClassificationParser} 用真实实例——
 * 它们是纯函数式的组装与校验逻辑，mock 掉反而会把"模板渲染正确吗、校验真的生效吗"
 * 这两件最该验证的事一起跳过。</p>
 *
 * <p>本类的重点是那张"三种降级各归哪个错误码"的表：这是章程第3周任务 7
 * 「处理AI返回异常」的落地证据。</p>
 */
class TicketAnalysisServiceImplTest {

    private static final String VALID_JSON = """
            {"category":"功能异常","priority":"HIGH","confidence":0.88,
             "keywords":["登录","验证码"],"reason":"核心登录功能不可用。"}
            """;

    private final AiChatService aiChatService = mock(AiChatService.class);

    private final TicketAnalysisServiceImpl service = new TicketAnalysisServiceImpl(
            aiChatService, new AiPromptFactory(), new AiClassificationParser());

    // ------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("分析成功：degraded=false，分类与优先级来自解析结果")
    void analyzeSucceeds() {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok(VALID_JSON, 553L, "deepseek-chat"));

        TicketAnalysisVO vo = service.analyze(dto());

        assertFalse(vo.degraded());
        assertEquals("功能异常", vo.category());
        assertEquals("HIGH", vo.priority());
        assertEquals(0.88, vo.confidence());
        assertEquals(List.of("登录", "验证码"), vo.keywords());
        assertEquals("核心登录功能不可用。", vo.reason());
        assertEquals(AiPromptFactory.PROMPT_VERSION, vo.promptVersion());
        assertEquals("deepseek-chat", vo.model());
        assertEquals(553L, vo.costMs());
        assertNull(vo.degradedReasonCode(), "成功时不应有降级原因");
    }

    @Test
    @DisplayName("送给模型的是模板渲染后的提示词：system 来自模板，user 里带着工单标题与描述")
    void promptsAreRenderedBeforeBeingSent() {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok(VALID_JSON, 100L, "stub-model"));

        service.analyze(dto());

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiChatService).chat(systemCaptor.capture(), userCaptor.capture());

        // system 必须是工厂渲染出的那一份（含分类白名单），而不是模板原文
        assertTrue(systemCaptor.getValue().contains("- 功能异常"),
                "system 提示词应包含渲染后的分类清单");
        assertFalse(systemCaptor.getValue().contains("<!--"),
                "system 提示词不应残留版本注释");

        String userPrompt = userCaptor.getValue();
        assertTrue(userPrompt.contains("登录页提示验证码异常"));
        assertTrue(userPrompt.contains("换浏览器也一样"));
        assertFalse(userPrompt.contains("<title>"), "占位符必须已被替换");
    }

    // ------------------------------------------------------------ 三种降级

    @Test
    @DisplayName("通道未配置：降级为 10006，且不暴露任何分类字段")
    void notConfiguredDegrades() {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.failed(ErrorCode.AI_NOT_CONFIGURED, "未配置模型密钥", 1L));

        TicketAnalysisVO vo = service.analyze(dto());

        assertTrue(vo.degraded());
        assertEquals(ErrorCode.AI_NOT_CONFIGURED.getCode(), vo.degradedReasonCode());
        assertNull(vo.category());
        assertNull(vo.priority());
        assertNull(vo.confidence(), "降级时不应透出把握度");
    }

    @Test
    @DisplayName("模型调用失败：降级为 10007")
    void callFailureDegrades() {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.failed(ErrorCode.AI_CALL_FAILED, "ResourceAccessException: Read timed out", 20000L));

        TicketAnalysisVO vo = service.analyze(dto());

        assertTrue(vo.degraded());
        assertEquals(ErrorCode.AI_CALL_FAILED.getCode(), vo.degradedReasonCode());
        assertEquals(20000L, vo.costMs(), "失败也要记录耗时，否则看不出模型是否变慢");
    }

    @Test
    @DisplayName("模型返回不是 JSON：降级为 10008，异常被就地翻译不向上冒泡")
    void unparsableResponseDegrades() {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok("我认为这属于功能异常，优先级高。", 400L, "deepseek-chat"));

        TicketAnalysisVO vo = service.analyze(dto());

        assertTrue(vo.degraded());
        assertEquals(ErrorCode.AI_RESPONSE_UNPARSABLE.getCode(), vo.degradedReasonCode());
    }

    @Test
    @DisplayName("模型返回合法 JSON 但分类不在白名单：同样降级为 10008")
    void categoryOutsideWhitelistDegrades() {
        String synonym = """
                {"category":"账号问题","priority":"HIGH","confidence":0.9,
                 "keywords":["登录"],"reason":"理由"}
                """;
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.ok(synonym, 380L, "deepseek-chat"));

        TicketAnalysisVO vo = service.analyze(dto());

        assertTrue(vo.degraded());
        assertEquals(ErrorCode.AI_RESPONSE_UNPARSABLE.getCode(), vo.degradedReasonCode());
    }

    @Test
    @DisplayName("降级响应只给固定文案，不把失败细节透出（细节可能含模型 host 与原始报错）")
    void degradedReasonDoesNotLeakInternalDetail() {
        when(aiChatService.chat(anyString(), anyString()))
                .thenReturn(AiCallResult.failed(ErrorCode.AI_CALL_FAILED,
                        "ResourceAccessException: Read timed out; host=api.deepseek.com", 20000L));

        TicketAnalysisVO vo = service.analyze(dto());

        assertEquals(ErrorCode.AI_CALL_FAILED.getMessage(), vo.degradedReason());
        assertFalse(vo.degradedReason().contains("deepseek.com"),
                "内部细节只能进日志，不能进响应体");
    }

    // ------------------------------------------------------------ 工具

    private TicketAnalyzeDTO dto() {
        TicketAnalyzeDTO dto = new TicketAnalyzeDTO();
        dto.setTitle("登录页提示验证码异常");
        dto.setDescription("输入正确验证码仍提示错误，换浏览器也一样");
        return dto;
    }

}
