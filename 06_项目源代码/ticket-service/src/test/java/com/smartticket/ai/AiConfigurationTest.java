package com.smartticket.ai;

import com.smartticket.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AI 配置装配的集成测试：走真实 Spring 上下文，确认「配置写进 yml 就真的生效」。
 *
 * <p>本项目已经在 <b>Redis 前缀（spring.redis → spring.data.redis）</b> 与
 * <b>Jackson 日期格式</b> 上各踩过一次「配置看着生效、实际没生效」的坑。
 * 本类存在的意义就是把这类静默失效挡在提交之前：属性名打错、前缀写错、
 * 被同名默认值覆盖，都会在这里断言失败，而不是等到面试演示时才发现。</p>
 *
 * <p>这里刻意<b>不配真实 API Key</b>：验证的目标是「没配 AI 时系统依然健康」，
 * 而不是「某家厂商今天可用」。</p>
 *
 * <p>属性里把 api-key 显式钉成占位值，是为了让本测试<b>与本机 application-local.yml 无关</b>：
 * 一旦你在本地填了真实 Key，若不钉住，下面「通道应判为不可用」的断言就会失败——
 * 那是你的配置生效了，不是代码坏了，但测试会误报成红灯。测试属性优先级高于 profile 配置文件，
 * 因此这一行能稳定覆盖本机配置。</p>
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=not-configured")
class AiConfigurationTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private RetryTemplate retryTemplate;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private double temperature;

    @Test
    @DisplayName("Spring AI 自动装配生效：容器里存在 ChatClient.Builder")
    void springAiAutoConfigurationIsActive() {
        // 这条断言保证「集成 Spring AI」不是只加了依赖：starter 的自动配置真的跑起来了，
        // 否则后面所有 AI 能力都会在第一次调用时才发现没有模型可用
        assertNotNull(chatClientBuilder, "ChatClient.Builder 缺失，说明 spring-ai starter 未生效");
    }

    @Test
    @DisplayName("未配置密钥时应用照常启动，AI 通道判为不可用并降级（不抛异常）")
    void placeholderKeyKeepsApplicationHealthy() {
        assertFalse(aiChatService.isAvailable(), "占位密钥下 AI 通道必须判为不可用");

        AiCallResult result = aiChatService.chat("你是什么模型", "随便说点什么");

        // 应用能起来、方法能返回、错误码是"未配置"而不是"调用失败"——
        // 三件事合起来才叫"AI 未接入不影响工单主流程"
        assertFalse(result.success());
        assertEquals(ErrorCode.AI_NOT_CONFIGURED, result.errorCode());
    }

    @Test
    @DisplayName("模型配置确实从 yml 绑定生效（防属性名拼错导致的静默失效）")
    void modelSettingsAreBoundFromYaml() {
        assertEquals("https://api.deepseek.com", baseUrl);
        assertEquals("deepseek-chat", model);
        assertEquals(0.2, temperature);
    }

    @Test
    @DisplayName("重试被关成 1 次：避免默认的 10 次重试把请求线程拖住几分钟")
    void retryTemplateDoesNotMultiplyLatency() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("模拟模型持续故障");
        }));

        // Spring AI 默认 max-attempts=10 且退避为 2s → 10s → 50s…，
        // 真按默认值跑，一次模型故障就足以占住 Web 线程数分钟；这里断言它确实是 1
        assertEquals(1, attempts.get(), "重试次数应为 1（不重试），实际为 " + attempts.get());
    }

}
