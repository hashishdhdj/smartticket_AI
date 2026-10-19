package com.smartticket.ai.impl;

import com.smartticket.ai.AiCallResult;
import com.smartticket.ai.AiChatService;
import com.smartticket.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * {@link AiChatService} 实现：对 Spring AI {@link ChatClient} 的一层薄封装。
 *
 * <p>薄封装的三个理由，缺一个都不如直接用 ChatClient：</p>
 * <ol>
 *   <li><b>把降级做成默认行为</b>：ChatClient 抛异常，本实现转成 {@link AiCallResult}，
 *       上层拿到的永远是"结果"而不是"异常"，AI 挂掉不会顺着调用栈污染工单主流程；</li>
 *   <li><b>把耗时记录收在一处</b>：不必在每个调用点重复写计时，日志格式也统一；</li>
 *   <li><b>不把 Spring AI 类型泄漏到业务层</b>：上层只依赖 {@code AiChatService}，
 *       将来换模型厂商或换框架，改动面被限制在本类。</li>
 * </ol>
 *
 * <p>装配在<b>构造期</b>完成而不是每次调用时构建 ChatClient：一是避免重复构建开销，
 * 二是让"AI 到底能不能用"在启动日志里就能看到，而不是等到第一次调用才发现密钥没配。</p>
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.smartticket.phase3.BudgetLedger budget;

    /** api-key 的占位值，与 application.yml 保持一致；出现它即视为「未配置密钥」 */
    static final String PLACEHOLDER_API_KEY = "not-configured";

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    private final boolean aiEnabled;

    private final String apiKey;

    private final String model;

    /** 装配后的 ChatClient；为 {@code null} 表示通道不可用 */
    private final ChatClient chatClient;

    /** 通道不可用的原因，写进日志与失败明细；可用时为 {@code null} */
    private final String unavailableReason;

    public AiChatServiceImpl(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                             @Value("${smartticket.ai.enabled:true}") boolean aiEnabled,
                             @Value("${spring.ai.openai.api-key:}") String apiKey,
                             @Value("${spring.ai.openai.chat.options.model:}") String model) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.aiEnabled = aiEnabled;
        this.apiKey = apiKey;
        this.model = model;

        String reason = resolveUnavailableReason();
        if (reason == null) {
            this.chatClient = chatClientBuilderProvider.getIfAvailable().build();
            this.unavailableReason = null;
            log.info("AI 通道就绪 | protocol=openai-compatible | model={}", model);
        } else {
            this.chatClient = null;
            this.unavailableReason = reason;
            // 用 warn 而不是 error：未配置 AI 是本项目的合法状态（阶段2 之前就是如此），
            // 但必须显眼，否则会变成"以为接了 AI、其实一直走人工"的静默失效
            log.warn("AI 通道不可用，相关能力将降级为人工处理 | reason={}", reason);
        }
    }

    /**
     * 判断通道为何不可用。
     *
     * @return 不可用的原因；返回 {@code null} 表示一切正常、可以装配 ChatClient
     */
    private String resolveUnavailableReason() {
        if (!aiEnabled) {
            return "AI 总开关已关闭（smartticket.ai.enabled=false）";
        }
        if (!StringUtils.hasText(apiKey) || PLACEHOLDER_API_KEY.equals(apiKey)) {
            return "未配置模型密钥（spring.ai.openai.api-key 仍为占位值，可通过环境变量 AI_API_KEY 注入）";
        }
        if (chatClientBuilderProvider.getIfAvailable() == null) {
            // 正常配置下不会走到这里；触发场景是把 spring.ai.model.chat 设成 none 关掉了模型自动装配
            return "Spring AI 的 ChatClient.Builder 未装配（检查 spring.ai.model.chat 配置）";
        }
        return null;
    }

    @Override
    public boolean isAvailable() {
        return chatClient != null;
    }

    @Override
    public AiCallResult chat(String systemPrompt, String userPrompt) {
        long start = System.currentTimeMillis();

        if (chatClient == null) {
            // debug 级别：通道不可用是启动时就已知的状态，不应该在每次业务调用时刷 warn
            log.debug("AI 通道不可用，直接降级 | reason={}", unavailableReason);
            return AiCallResult.failed(ErrorCode.AI_NOT_CONFIGURED, unavailableReason, elapsedMs(start));
        }

        try {
            var actor=com.smartticket.phase3.ActorContext.current();
            if(actor!=null&&budget!=null)budget.reserve(actor.id(),systemPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+userPrompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+2048);
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            long costMs = elapsedMs(start);

            if (!StringUtils.hasText(content)) {
                // 空内容不算成功：上层要拿它去解析 JSON，空串必然解析失败，
                // 与其让上层报"解析异常"，不如在这里就定性为一次失败的调用
                log.warn("AI 返回内容为空，按调用失败处理 | model={} | cost={}ms", model, costMs);
                return AiCallResult.failed(ErrorCode.AI_CALL_FAILED, "模型返回空内容", costMs);
            }

            log.info("AI 调用成功 | model={} | cost={}ms | answerChars={}", model, costMs, content.length());
            return AiCallResult.ok(content, costMs, model);

        } catch (com.smartticket.phase3.ApiFailure e) {
            if (e.code() == ErrorCode.AI_BUDGET_EXHAUSTED.getCode()) {
                return AiCallResult.failed(ErrorCode.AI_BUDGET_EXHAUSTED, e.getMessage(), elapsedMs(start));
            }
            return AiCallResult.failed(ErrorCode.AI_CALL_FAILED, e.getMessage(), elapsedMs(start));
        } catch (Exception e) {
            // 故意捕获宽泛的 Exception：这里是"可选能力的边界"，任何模型侧异常
            //（连接超时、401 鉴权失败、限流、返回体解析失败…）都应当就地降级，
            // 而不是继续向上传播把工单业务带崩。日志只留类名与 message，
            // 模型 SDK 的完整栈大多是框架内部帧，排障价值低、噪声大。
            long costMs = elapsedMs(start);
            log.warn("AI 调用失败，已降级为人工处理 | model={} | cost={}ms | cause={}: {}",
                    model, costMs, e.getClass().getSimpleName(), e.getMessage());
            String details=String.valueOf(e.getMessage());
            ErrorCode failureCode=(details.contains("401")||details.contains("Unauthorized")||details.contains("403"))
                    ?ErrorCode.AI_AUTH_FAILED:ErrorCode.AI_CALL_FAILED;
            return AiCallResult.failed(failureCode,
                    failureCategory(e) + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), costMs);
        }
    }

    private static String failureCategory(Throwable error) {
        for (int depth=0; error!=null && depth<8; depth++, error=error.getCause()) {
            String message=String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (error instanceof java.net.SocketTimeoutException || error instanceof java.net.http.HttpTimeoutException
                    || message.contains("upstream_timeout") || message.contains("timed out")) return "upstream_timeout";
            if (error instanceof javax.net.ssl.SSLException || error instanceof java.net.ConnectException
                    || message.contains("upstream_transport")) return "upstream_transport";
        }
        return "upstream_error";
    }

    private long elapsedMs(long start) {
        return System.currentTimeMillis() - start;
    }

}
