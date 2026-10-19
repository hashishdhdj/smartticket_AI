package com.smartticket.ai;

import com.smartticket.enums.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 链路测试：用 JDK 自带的 {@link HttpServer} 冒充一家「OpenAI 兼容」的模型厂商。
 *
 * <p>为什么值得单写一个类：前面的单元测试用 Stub 顶替了 {@code ChatModel}，
 * 相当于把 Spring AI 的 HTTP 客户端、请求体序列化、响应体反序列化、错误码映射<b>全部跳过</b>了。
 * 而真实世界里踩坑最多的恰恰是这一段——base-url 多写/少写 {@code /v1}、响应字段对不上、
 * 4xx/5xx 没被正确转成异常。本类让请求真的走一遍 HTTP，把这段补上。</p>
 *
 * <p>用假厂商而非真实厂商的理由：① 验证不依赖 API Key、不产生费用、离线可跑；
 * ② 可以稳定构造 500 与超长响应，真实厂商做不到"随叫随坏的故障"。</p>
 */
@SpringBootTest(properties = {
        "smartticket.ai.enabled=true",
        "spring.ai.openai.api-key=sk-stub-key",
        "spring.ai.openai.chat.options.model=stub-model",
        "spring.ai.openai.chat.options.temperature=0.2"
})
class AiOpenAiProtocolTest {

    /** 假厂商收到的请求体，按顺序累积；用于断言"我们到底发出去了什么" */
    private static final List<String> RECEIVED_BODIES = new CopyOnWriteArrayList<>();

    /** 假厂商本次要回的状态码：200 表示正常，其余用于模拟服务端故障 */
    private static volatile int forcedStatus = 200;
    private static volatile String forcedErrorType = "server_error";

    private static final int PORT;

    private static final HttpServer SERVER;

    /*
     * 用静态初始化块而不是 @BeforeAll 起服务：@DynamicPropertySource 注册端口时类必须已初始化，
     * 而 @BeforeAll 的执行时机晚于上下文加载，会拿到还没赋值的端口。
     */
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PORT = SERVER.getAddress().getPort();
            SERVER.createContext("/v1/chat/completions", exchange -> {
                RECEIVED_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = (forcedStatus == 200 ? successBody() : errorBody())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(forcedStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            SERVER.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void pointAtStubVendor(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + PORT);
    }

    @Autowired
    private AiChatService aiChatService;

    @BeforeEach
    void resetStub() {
        forcedStatus = 200;
        forcedErrorType = "server_error";
        RECEIVED_BODIES.clear();
    }

    @AfterAll
    static void stopStubVendor() {
        SERVER.stop(0);
    }

    @Test
    @DisplayName("真实 HTTP 往返：配置的 model/temperature 与提示词都出现在请求体里，响应被正确解析")
    void realHttpRoundTripCarriesConfigurationAndParsesResponse() {
        AiCallResult result = aiChatService.chat("你是工单分类助手", "登录页提示验证码异常，无法登录");

        assertEquals(1, RECEIVED_BODIES.size(), "应当只发出一次请求（重试被关成 1 次）");

        String sent = RECEIVED_BODIES.get(0);
        assertTrue(sent.contains("\"model\":\"stub-model\""), "model 必须来自 spring.ai.openai.chat.options.model");
        assertTrue(sent.contains("\"temperature\":0.2"), "temperature 必须来自配置");
        assertTrue(sent.contains("你是工单分类助手"), "system 提示词必须出现在请求体里");
        assertTrue(sent.contains("登录页提示验证码异常，无法登录"), "user 提示词必须出现在请求体里");

        assertTrue(result.success(), "假厂商返回 200，应当解析成功");
        assertEquals("{\"category\":\"账户问题\"}", result.content(), "响应体里的 content 必须被原样取出");
        assertEquals("stub-model", result.model());
    }

    @Test
    @DisplayName("厂商返回 500 时降级为 AI_CALL_FAILED，不把异常抛给调用方")
    void vendorServerErrorIsDegradedToCallFailed() {
        forcedStatus = 500;

        AiCallResult result = aiChatService.chat("你是工单分类助手", "随便问一句");

        assertFalse(result.success());
        assertEquals(ErrorCode.AI_CALL_FAILED, result.errorCode());
        // 关键点：异常被本层吃掉。上层拿到的永远是结果对象，
        // 因此"模型故障导致工单创建失败"在结构上就不可能发生
    }

    @Test
    @DisplayName("base-url 拼出的路径确实是 /v1/chat/completions（防止 /v1 多写少写）")
    void completionsPathMatchesOpenAiProtocol() {
        aiChatService.chat("sys", "user");

        // 能收到请求本身就是路径正确的证据：假厂商只注册了 /v1/chat/completions，
        // 路径不对会得到 404 → 被降级 → RECEIVED_BODIES 为空，本断言随即失败
        assertEquals(1, RECEIVED_BODIES.size(), "路径不是 /v1/chat/completions，请求没打到假厂商上");
    }

    private static String successBody() {
        return """
                {
                  "id": "chatcmpl-stub-1",
                  "object": "chat.completion",
                  "created": 1758700000,
                  "model": "stub-model",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": "{\\"category\\":\\"账户问题\\"}"},
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 24, "completion_tokens": 12, "total_tokens": 36}
                }
                """;
    }

    private static String errorBody() {
        return """
                {"error": {"message": "upstream failed", "type": "%s", "code": "internal_error"}}
                """.formatted(forcedErrorType);
    }

    @ParameterizedTest
    @ValueSource(strings={"upstream_timeout","upstream_transport"})
    void gatewayFailureCategorySurvivesActualHttpSdk(String category) {
        forcedStatus=502;
        forcedErrorType=category;
        AiCallResult result=aiChatService.chat("sys","synthetic failure");
        assertFalse(result.success());
        assertEquals(ErrorCode.AI_CALL_FAILED,result.errorCode());
        assertTrue(result.failureDetail().contains(category));
        assertEquals(1,RECEIVED_BODIES.size(),"No hidden retries on uncertain-charge failures");
    }

}
