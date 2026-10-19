package com.smartticket.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.smartticket.ai.AiResponseInvalidException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 回答内容的解析与校验（{@code rag-answer-v2}）。
 *
 * <p>与 {@code AiClassificationParser} 是同一套路：模型返回的内容要么通过全部校验、
 * 要么抛出 {@link AiResponseInvalidException}，<b>不存在"部分合法就先用着"的中间态</b>。</p>
 *
 * <h3>为什么一个字段不合格就整份作废</h3>
 * 模型少给字段、多给字段、或给出超长内容，都说明它没有按 Prompt 规定的结构走；
 * 那么同一份响应里的其它字段同样不可信。此时正确做法是整份丢弃、降级为"只返回来源"，
 * 而不是把看起来正常的那半个字段留下来——后者会把"模型开始不守规矩"这个信号吃掉。</p>
 *
 * <h3>为什么解析成功后返回规范化的值</h3>
 * 建议与相似问题都会被折叠成单行并 trim：返回体里不该出现换行与多余空白
 * （模型偶尔会用换行来"排版"，那是它在遵守格式上打了折扣）。
 * 调用方拿到即可直接用，不必再清理一遍。</p>
 *
 * <h3>刻意不做的事</h3>
 * 不校验"问句是否以问号结尾"。问号是<b>外观</b>，缺了它不影响这条数据能不能用；
 * 为它把整次回答降级是不划算的。校验只针对"语义与结构"——这与分类解析器
 * 只校验取值与长度、不校验措辞是同一个尺度。
 */
@Component
public class RagAnswerParser {

    /** 建议的长度上限。Prompt 要求 80 字，这里放宽到 200：超长才说明没遵守格式 */
    static final int MAX_SUGGESTION_LENGTH = 200;

    /** 相似问题的条数上限 */
    static final int MAX_QUESTIONS = 5;

    /** 单条相似问题的长度上限 */
    static final int MAX_QUESTION_LENGTH = 40;

    /** 异常信息里回显原文的长度上限，避免把整段长文本反复写进日志 */
    private static final int BRIEF_LENGTH = 200;

    /**
     * 专用 ObjectMapper，刻意不复用 Web 层那个（与分类解析器同一理由）：
     * AI 输出的解析规则不该被 {@code spring.jackson.*} 或 Web 层的定制被动影响。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Markdown 代码块围栏。与分类解析器保留同一层廉价保险：
     * 2026-10-08 实测模型按要求裸输出 JSON，但那是模型行为而非契约，
     * 换厂商或改措辞都可能破坏它，而剥围栏只要一个正则。
     */
    private static final Pattern CODE_FENCE =
            Pattern.compile("(?s)^\\s*```(?:json|JSON)?\\s*(.*?)\\s*```\\s*$");

    /**
     * 解析并校验模型返回的回答内容。
     *
     * @param rawText 模型返回的原始文本（{@code AiCallResult.content()}）
     * @return 通过全部校验、且已规范化的内容
     * @throws AiResponseInvalidException 内容为空、不是合法 JSON、缺少字段、多出字段，或取值越界
     */
    public RagAnswerPayload parse(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            throw new AiResponseInvalidException("模型返回内容为空");
        }

        String json = unwrapCodeFence(rawText);
        RagAnswerPayload raw;
        try {
            raw = MAPPER.readValue(json, RagAnswerPayload.class);
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

    private RagAnswerPayload validateAndNormalize(RagAnswerPayload raw) {
        if (raw.needHuman() == null) {
            throw new AiResponseInvalidException("needHuman 缺失，模型必须明确判断资料是否足以回答当前问题");
        }
        String suggestion = collapse(raw.suggestion());
        if (suggestion == null) {
            throw new AiResponseInvalidException("suggestion 缺失或为空");
        }
        if (suggestion.length() > MAX_SUGGESTION_LENGTH) {
            throw new AiResponseInvalidException(
                    "suggestion 超长（上限 " + MAX_SUGGESTION_LENGTH + "）：实际 " + suggestion.length() + " 字");
        }

        List<String> questions = raw.similarQuestions();
        if (questions == null || questions.isEmpty()) {
            throw new AiResponseInvalidException("similarQuestions 缺失或为空数组（至少 1 条）");
        }
        if (questions.size() > MAX_QUESTIONS) {
            throw new AiResponseInvalidException(
                    "similarQuestions 数量超限（上限 " + MAX_QUESTIONS + "）：实际 " + questions.size() + " 条");
        }

        List<String> normalized = new ArrayList<>(questions.size());
        for (String question : questions) {
            String collapsed = collapse(question);
            if (collapsed == null) {
                throw new AiResponseInvalidException("similarQuestions 中存在空元素");
            }
            if (collapsed.length() > MAX_QUESTION_LENGTH) {
                throw new AiResponseInvalidException(
                        "相似问题超长（上限 " + MAX_QUESTION_LENGTH + "）：" + collapsed);
            }
            normalized.add(collapsed);
        }

        return new RagAnswerPayload(suggestion, List.copyOf(normalized), raw.needHuman());
    }

    /** 折叠空白成一行并 trim；结果为空时返回 null */
    private String collapse(String value) {
        if (value == null) {
            return null;
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.isEmpty() ? null : singleLine;
    }

    private String brief(String text) {
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= BRIEF_LENGTH
                ? singleLine
                : singleLine.substring(0, BRIEF_LENGTH) + "…";
    }

}
