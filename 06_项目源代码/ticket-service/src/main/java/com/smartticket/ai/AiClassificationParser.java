package com.smartticket.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.smartticket.enums.TicketCategory;
import com.smartticket.enums.TicketPriority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AI 分类结果的解析与校验（阶段2 第2天）。
 *
 * <p>它是阶段2 约束「必须处理 JSON 解析失败」的落点，也是"结构化输出"从口号变成事实的地方：
 * 模型返回的内容要么通过全部校验、要么抛出 {@link AiResponseInvalidException}，
 * <b>不存在"部分合法就先用着"的中间态</b>。</p>
 *
 * <h3>为什么在这里用严格反序列化，而不是宽松兼容</h3>
 * 用 {@code FAIL_ON_UNKNOWN_PROPERTIES}：模型多返回一个字段，说明它没有按 Schema 走，
 * 那么同一响应里的其他字段同样不可信。静默忽略多余字段会把"模型开始不守规矩"这个信号
 * 吃掉——而这类漂移一旦被忽略，往往要等到线上分类结果变脏才被发现。
 *
 * <h3>置信度的"缺字段"与"值为 0"必须区分</h3>
 * 这也是 {@link AiClassificationResult} 的字段用 {@code Double} 而非 {@code double} 的原因：
 * 缺字段是违规，值为 0 是合法的"完全没把握"，两者处置完全不同。
 *
 * <h3>解析成功后返回规范化的值</h3>
 * 返回值经 trim 与大小写归一（priority 统一大写），可直接入库，不需要调用方再清理一遍。
 */
@Component
public class AiClassificationParser {

    /** 与 {@code ai-classification-result.schema.json} 的 maxItems 对应 */
    static final int MAX_KEYWORDS = 8;

    /** 与 schema 中 keywords.items.maxLength 对应 */
    static final int MAX_KEYWORD_LENGTH = 30;

    /** 与 schema 中 reason.maxLength 对应 */
    static final int MAX_REASON_LENGTH = 200;

    /** 异常信息里回显原文的长度上限，避免把整段长文本反复写进日志 */
    private static final int BRIEF_LENGTH = 200;

    /**
     * 专用 ObjectMapper，刻意不复用 Web 层那个。
     *
     * <p>两个原因：① Web 层的 ObjectMapper 受 {@code spring.jackson.*} 与全局
     * {@code Jackson2ObjectMapperBuilderCustomizer} 影响，AI 输出的解析规则不该被动跟着变；
     * ② 这里的严格策略（拒绝未知字段）是 AI 契约的一部分，要写死在自己的配置里。</p>
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} 本就是 Jackson 的默认值，这里显式声明是为了
     * 表达"这是有意选择的语义"，而不是依赖某个版本的默认行为。</p>
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Markdown 代码块围栏。
     *
     * <p>2026-09-25 实测 DeepSeek 会按要求裸输出 JSON、不加围栏。但这是<b>模型的行为，不是契约</b>：
     * 换厂商、换模型版本、改 Prompt 措辞都可能破坏它。剥离围栏只需一个正则，
     * 而误判的代价是整个分类能力降级，因此保留这层廉价保险。
     * 注意只剥"整段被围栏包住"的形式，不做事后字符串查找——避免把正文里的反引号当围栏。</p>
     */
    private static final Pattern CODE_FENCE =
            Pattern.compile("(?s)^\\s*```(?:json|JSON)?\\s*(.*?)\\s*```\\s*$");

    /**
     * 解析并校验模型返回的分类结果。
     *
     * @param rawText 模型返回的原始文本（{@link AiCallResult#content()}）
     * @return 通过全部校验、且已规范化的分类结果
     * @throws AiResponseInvalidException 内容为空、不是合法 JSON、缺少字段、多出字段，
     *                                    或任一字段取值越界
     */
    public AiClassificationResult parse(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            throw new AiResponseInvalidException("模型返回内容为空");
        }

        String json = unwrapCodeFence(rawText);
        AiClassificationResult raw;
        try {
            raw = MAPPER.readValue(json, AiClassificationResult.class);
        } catch (JsonProcessingException e) {
            throw new AiResponseInvalidException(
                    "返回内容不是合法 JSON 或字段不匹配：" + brief(json) + " | 原因：" + e.getOriginalMessage());
        }

        return validateAndNormalize(raw);
    }

    // ------------------------------------------------------------ 内部

    private String unwrapCodeFence(String rawText) {
        Matcher matcher = CODE_FENCE.matcher(rawText);
        return matcher.matches() ? matcher.group(1) : rawText;
    }

    /**
     * 逐项校验，并返回规范化后的结果。
     *
     * <p>顺序上先校验必填与取值，再校验长度，这样报错信息总是指向"最需要先修的那一项"。</p>
     */
    private AiClassificationResult validateAndNormalize(AiClassificationResult raw) {
        String category = trimToNull(raw.category());
        if (category == null) {
            throw new AiResponseInvalidException("category 缺失或为空");
        }
        if (TicketCategory.fromLabel(category).isEmpty()) {
            throw new AiResponseInvalidException(
                    "category 不在允许清单内：" + category + "，允许值=" + TicketCategory.allLabels());
        }

        String priority = normalizePriority(raw.priority());

        Double confidence = raw.confidence();
        if (confidence == null) {
            throw new AiResponseInvalidException("confidence 缺失");
        }
        if (confidence < 0 || confidence > 1) {
            throw new AiResponseInvalidException("confidence 越界（应在 0~1 之间）：" + confidence);
        }

        List<String> keywords = normalizeKeywords(raw.keywords());

        String reason = trimToNull(raw.reason());
        if (reason == null) {
            throw new AiResponseInvalidException("reason 缺失或为空");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new AiResponseInvalidException(
                    "reason 超长（上限 " + MAX_REASON_LENGTH + "）：实际 " + reason.length() + " 字");
        }

        return new AiClassificationResult(category, priority, confidence, keywords, reason);
    }

    /**
     * 优先级校验与归一。
     *
     * <p>大小写不敏感：Prompt 要求大写，但模型偶尔会返回 {@code high}。
     * 大小写不改变语义，为此把一次分类整体降级不划算，因此接受并统一转成大写——
     * 库里 {@code priority} 列的取值必须是大写，归一在解析层做掉，别留给写库时。</p>
     */
    private String normalizePriority(String priority) {
        String trimmed = trimToNull(priority);
        if (trimmed == null) {
            throw new AiResponseInvalidException("priority 缺失或为空");
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        try {
            TicketPriority.valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new AiResponseInvalidException(
                    "priority 非法：" + trimmed + "，允许值=" + allowedPriorities());
        }
        return upper;
    }

    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            throw new AiResponseInvalidException("keywords 缺失或为空数组（至少 1 个）");
        }
        if (keywords.size() > MAX_KEYWORDS) {
            throw new AiResponseInvalidException(
                    "keywords 数量超限（上限 " + MAX_KEYWORDS + "）：实际 " + keywords.size() + " 个");
        }

        List<String> normalized = new ArrayList<>(keywords.size());
        for (String keyword : keywords) {
            String trimmed = trimToNull(keyword);
            if (trimmed == null) {
                throw new AiResponseInvalidException("keywords 中存在空元素");
            }
            if (trimmed.length() > MAX_KEYWORD_LENGTH) {
                throw new AiResponseInvalidException(
                        "关键词超长（上限 " + MAX_KEYWORD_LENGTH + "）：" + trimmed);
            }
            normalized.add(trimmed);
        }
        return normalized;
    }

    private String allowedPriorities() {
        return Stream.of(TicketPriority.values())
                .map(Enum::name)
                .collect(Collectors.joining("、"));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String brief(String text) {
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= BRIEF_LENGTH ? singleLine : singleLine.substring(0, BRIEF_LENGTH) + "…";
    }

}
