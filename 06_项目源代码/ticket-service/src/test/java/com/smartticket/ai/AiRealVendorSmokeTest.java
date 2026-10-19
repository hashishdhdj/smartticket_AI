package com.smartticket.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实厂商连通性冒烟测试：真调一次模型，用来回答「这把密钥到底能不能用」。
 *
 * <p>为什么必须单独有这么一个测试：启动日志里的「AI 通道就绪」只说明<b>密钥非空</b>，
 * 它证明不了三件事——密钥是否有效、base-url 是否指对、模型名是否存在。
 * 这三件事都会在真实调用时以 401 / 404 / 400 的形式暴露出来，而单元测试与假厂商测试
 * 都无法覆盖（它们用的都是我们自己造的密钥与响应）。</p>
 *
 * <p><b>默认跳过，必须显式开启</b>：本测试会真实调用厂商接口并产生费用。
 * 开关用环境变量而不是系统属性，因为环境变量一定能传到 fork 出来的测试 JVM，
 * 而 {@code -D} 系统属性是否传递取决于 surefire 配置，容易变成"看着开了其实没开"。</p>
 *
 * <pre>
 * PowerShell:  $env:AI_SMOKE = "true"; mvn test -Dtest=AiRealVendorSmokeTest
 * </pre>
 *
 * <p>测试会打印 base-url、模型名、耗时与实际返回，<b>但绝不打印密钥</b>。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AI_SMOKE", matches = "true",
        disabledReason = "默认跳过：本测试会真实调用厂商接口并产生费用，需显式设置 AI_SMOKE=true 开启")
class AiRealVendorSmokeTest {

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiPromptFactory aiPromptFactory;

    @Autowired
    private AiClassificationParser aiClassificationParser;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Test
    @DisplayName("用真实密钥调用一次模型，同时验证密钥、base-url、模型名三者都正确")
    void realVendorCallSucceeds() {
        assumeTrue(aiChatService.isAvailable(),
                "AI 通道不可用（未配密钥或总开关关闭），跳过真实调用 —— 看启动日志里的 reason");

        // 这里刻意用「工单分类」这个第3天要做的真实任务来试，顺便看模型能不能按中文要求回 JSON
        AiCallResult result = aiChatService.chat(
                "你是工单分类助手。只输出 JSON，不要任何解释、不要代码块标记。",
                "工单内容：登录页提示验证码异常，无法登录。"
                        + "请输出字段 category（账户问题/支付问题/功能异常/其他）、"
                        + "priority（HIGH/MEDIUM/LOW）、confidence（0~1 之间的小数）、keywords（字符串数组）。");

        // 打印证据。这是唯一能区分"密钥无效/地址写错/模型名不存在"的信息，失败时靠它定位。
        // 注意：全程不打印密钥本身。
        System.out.println("[真实调用] base-url = " + baseUrl);
        System.out.println("[真实调用] model    = " + model);
        System.out.println("[真实调用] success  = " + result.success()
                + " | cost = " + result.costMs() + "ms"
                + " | errorCode = " + result.errorCode());
        System.out.println("[真实调用] failureDetail = " + result.failureDetail());
        System.out.println("[模型原始返回] " + result.content());

        assertTrue(result.success(),
                () -> "真实调用失败。errorCode=" + result.errorCode()
                        + " | failureDetail=" + result.failureDetail()
                        + "（401 多为密钥无效；404/400 多为 base-url 或模型名不对）");
        assertNotNull(result.content());
        assertFalse(result.content().isBlank(), "模型返回了空内容");
    }

    @Test
    @DisplayName("真实模型按 v1 模板输出，且输出能被解析器接受（Prompt 与解析器的联合验证）")
    void realModelOutputIsAcceptedByParser() {
        assumeTrue(aiChatService.isAvailable(),
                "AI 通道不可用（未配密钥或总开关关闭），跳过真实调用 —— 看启动日志里的 reason");

        String systemPrompt = aiPromptFactory.classificationSystemPrompt();
        String userPrompt = aiPromptFactory.classificationUserPrompt(
                "登录页提示验证码异常，无法登录",
                "今天上午开始，输入正确的验证码后仍然提示验证码错误，换了浏览器和手机都一样，全部无法登录。");

        AiCallResult call = aiChatService.chat(systemPrompt, userPrompt);
        System.out.println("[真实分类] promptVersion = " + AiPromptFactory.PROMPT_VERSION);
        System.out.println("[真实分类] success = " + call.success() + " | cost = " + call.costMs() + "ms");
        System.out.println("[真实分类] 原始返回 = " + call.content());
        assertTrue(call.success(), () -> "真实调用失败：" + call.failureDetail());

        // 关键一步：把真实返回直接喂给解析器。解析器会执行全部校验
        //（字段齐全、无多余字段、分类在清单内、confidence 区间、关键词与理由长度），
        // 因此这一行同时验证了「Prompt 写得足够明确」与「解析器不误杀合规输出」
        AiClassificationResult result = aiClassificationParser.parse(call.content());
        System.out.println("[真实分类] 解析结果 = category=" + result.category()
                + " | priority=" + result.priority()
                + " | confidence=" + result.confidence()
                + " | keywords=" + result.keywords()
                + " | reason=" + result.reason());

        // 解析器保证的是"格式合规"，不保证"分得对"。这两条是轻量的语义合理性检查：
        // 登录/验证码故障显然不是支付问题；若模型真这么分，说明 Prompt 需要改，
        // 而本测试失败正是我们想看到的信号（因此刻意保留，不写成"什么都接受"）
        assertNotEquals("支付问题", result.category(), "登录验证码故障被分到支付问题，Prompt 需要调整");
        assertTrue(result.confidence() > 0, "confidence 为 0 说明模型无法判断，Prompt 信息量可能不足");
    }

}
