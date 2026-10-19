package com.smartticket.ai.impl;

import com.smartticket.ai.AiCallResult;
import com.smartticket.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatServiceImpl} 的纯单元测试：不连 MySQL、不连 Redis、不访问任何模型服务。
 *
 * <p>用 {@link StubChatModel} 顶替真实厂商模型：Spring AI 的 {@code ChatModel} 只有一个抽象方法
 * {@code call(Prompt)}，自己实现一个即可，比 Mockito mock 更好读，也避免"mock 掉的其实是框架内部行为"。
 * 代价是它不能被 {@code Mockito.verify} 校验，所以 Stub 自己记「被调用了几次、最后一次收到的 Prompt」。</p>
 *
 * <p>这套测试的价值在于：<b>在没有 API Key 的机器上也能证明降级链路是通的</b>。
 * 真实 Key 只能验证"厂商能用"，验证不了"厂商挂了系统会不会崩"——后者才是本类的存在意义。</p>
 */
class AiChatServiceImplTest {

    @Test
    void budgetExhaustionKeepsReasonAndDoesNotDispatchModel() {
        StubChatModel model = stubModelOf(prompt -> chatResponseOf("must not call"));
        AiChatServiceImpl service = new AiChatServiceImpl(providerOf(ChatClient.builder(model)), true, "test-key", "stub-model");
        var budget = mock(com.smartticket.phase3.BudgetLedger.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "budget", budget);
        org.mockito.Mockito.doThrow(new com.smartticket.phase3.ApiFailure(429, 10014, "budget exhausted"))
                .when(budget).reserve(org.mockito.ArgumentMatchers.eq(109014L), org.mockito.ArgumentMatchers.anyInt());
        com.smartticket.phase3.ActorContext.set(new com.smartticket.phase3.ActorContext.Actor(109014, "SUBMITTER"));
        try {
            AiCallResult result = service.chat("system", "user");
            assertFalse(result.success());
            assertEquals(ErrorCode.AI_BUDGET_EXHAUSTED, result.errorCode());
            assertEquals(0, model.callCount());
        } finally { com.smartticket.phase3.ActorContext.clear(); }
    }

    private static final String SYSTEM_PROMPT = "你是工单分类助手";

    private static final String USER_PROMPT = "登录页提示验证码异常，无法登录";

    // ------------------------------------------------------------ 通道不可用的四条路径

    @Test
    @DisplayName("未配置密钥（仍是占位值）时降级为 AI_NOT_CONFIGURED，且不发起模型调用")
    void placeholderApiKeyDegradesWithoutCallingModel() {
        StubChatModel model = stubModelOf(prompt -> chatResponseOf("不该被调用"));

        AiChatServiceImpl service = new AiChatServiceImpl(
                providerOf(ChatClient.builder(model)), true, AiChatServiceImpl.PLACEHOLDER_API_KEY, "stub-model");

        AiCallResult result = service.chat(SYSTEM_PROMPT, USER_PROMPT);

        assertFalse(result.success(), "未配置密钥时必须判为未成功");
        assertEquals(ErrorCode.AI_NOT_CONFIGURED, result.errorCode());
        assertNull(result.content());
        assertFalse(service.isAvailable());
        // 关键断言：不能"先发请求再失败"。密钥都没配，任何网络动作都是浪费，
        // 在离线或流量受限环境里还会变成一次真实的连接超时等待
        assertEquals(0, model.callCount(), "未配置密钥时不允许调用模型");
    }

    @Test
    @DisplayName("空密钥同样按未配置处理，不因缺少密钥而抛异常")
    void blankApiKeyDegrades() {
        StubChatModel model = stubModelOf(prompt -> chatResponseOf("不该被调用"));

        AiChatServiceImpl service = new AiChatServiceImpl(
                providerOf(ChatClient.builder(model)), true, "   ", "stub-model");

        AiCallResult result = service.chat(SYSTEM_PROMPT, USER_PROMPT);

        assertEquals(ErrorCode.AI_NOT_CONFIGURED, result.errorCode());
        assertEquals(0, model.callCount(), "未配置密钥时不允许调用模型");
    }

    @Test
    @DisplayName("总开关关闭时降级，即使密钥已配置")
    void disabledSwitchDegradesEvenWhenKeyConfigured() {
        StubChatModel model = stubModelOf(prompt -> chatResponseOf("不该被调用"));

        AiChatServiceImpl service = new AiChatServiceImpl(
                providerOf(ChatClient.builder(model)), false, "sk-real-key", "stub-model");

        AiCallResult result = service.chat(SYSTEM_PROMPT, USER_PROMPT);

        assertEquals(ErrorCode.AI_NOT_CONFIGURED, result.errorCode());
        assertFalse(service.isAvailable());
        assertEquals(0, model.callCount(), "总开关关闭时不允许调用模型");
    }

    @Test
    @DisplayName("ChatClient.Builder 未装配时降级为 AI_NOT_CONFIGURED，不影响应用启动")
    void missingChatClientBuilderDegrades() {
        AiChatServiceImpl service = new AiChatServiceImpl(emptyProvider(), true, "sk-real-key", "stub-model");

        AiCallResult result = service.chat(SYSTEM_PROMPT, USER_PROMPT);

        // 这条路径保证：即使 spring.ai.model.chat=none 关掉了模型自动装配，
        // 本 Bean 依然能创建成功、应用能启动，只是 AI 能力不可用
        assertEquals(ErrorCode.AI_NOT_CONFIGURED, result.errorCode());
        assertNotNull(result.failureDetail());
        assertTrue(result.failureDetail().contains("ChatClient.Builder"));
        assertFalse(service.isAvailable());
    }

    // ------------------------------------------------------------ 正常调用

    @Test
    @DisplayName("调用成功时返回模型内容、模型名，并把 system/user 提示词都传给了模型")
    void successfulCallReturnsContentAndPassesBothPrompts() {
        StubChatModel model = stubModelOf(prompt -> chatResponseOf("{\"category\":\"账户问题\"}"));

        AiChatServiceImpl service = new AiChatServiceImpl(
                providerOf(ChatClient.builder(model)), true, "sk-unit-test", "stub-model");

        AiCallResult result = service.chat(SYSTEM_PROMPT, USER_PROMPT);

        assertTrue(result.success());
        assertEquals("{\"category\":\"账户问题\"}", result.content());
        assertEquals("stub-model", result.model());
        assertNull(result.errorCode());
        assertTrue(service.isAvailable());
        assertTrue(result.costMs() >= 0, "耗时字段必须被填充，日志要靠它判断模型是否变慢");

        assertEquals(1, model.callCount());
        String sent = model.lastPrompt().getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        assertTrue(sent.contains(SYSTEM_PROMPT), "system 提示词必须真的发给模型");
        assertTrue(sent.contains(USER_PROMPT), "user 提示词必须真的发给模型");
    }

    // ------------------------------------------------------------ 失败被就地降级

    @Test
    @DisplayName("模型超时（连接被拒/读超时）被降级为 AI_CALL_FAILED，不向外抛异常")
    void modelTimeoutIsDegradedNotThrown() {
        StubChatModel model = stubModelOf(prompt -> {
            throw new ResourceAccessException("Read timed out");
        });

        AiChatServiceImpl service = new AiChatServiceImpl(
                providerOf(ChatClient.builder(model)), true, "sk-unit-test", "stub-model");

        AiCallResult result = service.chat(SYSTEM_PROMPT, USER_PROMPT);

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_CALL_FAILED, result.errorCode());
        assertNotNull(result.failureDetail());
        assertTrue(result.failureDetail().contains("Read timed out"));
    }

    @Test
    @DisplayName("模型返回空内容按 AI_CALL_FAILED 处理，不放行给上层去解析")
    void blankModelOutputIsTreatedAsFailure() {
        StubChatModel model = stubModelOf(prompt -> chatResponseOf("   "));

        AiChatServiceImpl service = new AiChatServiceImpl(
                providerOf(ChatClient.builder(model)), true, "sk-unit-test", "stub-model");

        AiCallResult result = service.chat(SYSTEM_PROMPT, USER_PROMPT);

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_CALL_FAILED, result.errorCode());
        assertNotNull(result.failureDetail());
        assertTrue(result.failureDetail().contains("空内容"));
    }

    // ------------------------------------------------------------ 测试替身

    /**
     * 假模型：实现 {@code ChatModel} 唯一的抽象方法，并记录被调用次数与最后一次收到的 Prompt。
     *
     * <p>之所以不交给 Mockito：这里要断言的是"模型有没有被调用"和"提示词内容对不对"，
     * 用一个真实的小对象比 mock 的 stubbing 更直观，也不会因为 Spring AI 内部多加一层包装就失效。</p>
     */
    private static class StubChatModel implements ChatModel {

        private final Function<Prompt, ChatResponse> handler;

        private int callCount;

        private Prompt lastPrompt;

        StubChatModel(Function<Prompt, ChatResponse> handler) {
            this.handler = handler;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.callCount++;
            this.lastPrompt = prompt;
            return handler.apply(prompt);
        }

        int callCount() {
            return callCount;
        }

        Prompt lastPrompt() {
            return lastPrompt;
        }
    }

    private static StubChatModel stubModelOf(Function<Prompt, ChatResponse> handler) {
        return new StubChatModel(handler);
    }

    private static ChatResponse chatResponseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatClient.Builder> providerOf(ChatClient.Builder builder) {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatClient.Builder> emptyProvider() {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

}
