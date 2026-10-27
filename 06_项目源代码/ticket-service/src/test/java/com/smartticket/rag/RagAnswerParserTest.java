package com.smartticket.rag;

import com.smartticket.ai.AiResponseInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagAnswerParser} 单元测试（{@code rag-answer-v3}）。
 *
 * <p>它是"结构化输出"的执法点：这里要证明的是——<b>凡是模型不按结构返回的，都被挡住</b>，
 * 而不是"大多数情况能解析"。每一个"抛异常"的用例都对应一种真实的模型跑偏方式。</p>
 */
class RagAnswerParserTest {

    private final RagAnswerParser parser = new RagAnswerParser();

    @Test void rejectsDuplicateDecisionAndTrailingJson() {
        assertThrows(AiResponseInvalidException.class,()->parser.parse("{\"suggestion\":\"建议\",\"similarQuestions\":[\"问题\"],\"needHuman\":false,\"needHuman\":true}"));
        assertThrows(AiResponseInvalidException.class,()->parser.parse("{\"suggestion\":\"建议\",\"similarQuestions\":[\"问题\"],\"needHuman\":false} {}"));
    }

    @Test
    void preservesSemanticAbstention() {
        var payload = parser.parse("{\"suggestion\":\"资料不包含该记录，请人工核实\",\"similarQuestions\":[\"如何查询工单\"],\"needHuman\":true,\"handoff\":{\"state\":\"INSUFFICIENT_EVIDENCE\",\"reason\":\"CRITICAL_FACTS_MISSING\",\"detail\":\"缺少用户要求的真实退款处理记录\"}}");
        assertTrue(payload.needHuman());
    }

    @Test
    void rejectsMissingAbstentionDecision() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"suggestion\":\"建议\",\"similarQuestions\":[\"问题\"]}"));
    }

    @Test
    void rejectsStringInsteadOfBooleanDecision() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"suggestion\":\"建议\",\"similarQuestions\":[\"问题\"],\"needHuman\":\"false\"}"));
    }

    // ------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("合规 JSON：建议与相似问题都被解析出来")
    void parsesValidPayload() {
        RagAnswerPayload payload = parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"引导用户点击忘记密码重置\",\"similarQuestions\":[\"忘记密码怎么办？\",\"账号锁了怎么办？\"]}");

        assertEquals("引导用户点击忘记密码重置", payload.suggestion());
        assertEquals(List.of("忘记密码怎么办？", "账号锁了怎么办？"), payload.similarQuestions());
    }

    @Test
    @DisplayName("整段被 Markdown 代码块包住时，剥掉围栏后仍能解析（廉价保险，不依赖模型永远守规矩）")
    void unwrapsCodeFence() {
        RagAnswerPayload payload = parser.parse(
                "```json\n{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[\"问题？\"]}\n```");

        assertEquals("建议", payload.suggestion());
        assertEquals(List.of("问题？"), payload.similarQuestions());
    }

    @Test
    @DisplayName("建议与相似问题里的换行、多余空白被折叠成一行")
    void collapsesWhitespace() {
        RagAnswerPayload payload = parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"先做 A，\\n\\n  再做 B\",\"similarQuestions\":[\"  问题一\\n怎么办？ \"]}");

        assertEquals("先做 A， 再做 B", payload.suggestion());
        assertEquals(List.of("问题一 怎么办？"), payload.similarQuestions());
    }

    @Test
    @DisplayName("问句末尾没有问号不算违规——问号是外观，缺了不影响这条数据能不能用")
    void missingQuestionMarkIsAccepted() {
        RagAnswerPayload payload = parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[\"忘记密码怎么办\"]}");

        assertEquals(List.of("忘记密码怎么办"), payload.similarQuestions());
    }

    // ------------------------------------------------------------ 结构不合规

    @Test
    @DisplayName("内容为空或不是 JSON：抛异常（模型没答话或答非所问）")
    void rejectsBlankAndNonJson() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(null));
        assertThrows(AiResponseInvalidException.class, () -> parser.parse("   "));
        assertThrows(AiResponseInvalidException.class, () -> parser.parse("建议：引导用户重置密码。"));
        assertThrows(AiResponseInvalidException.class, () -> parser.parse("[1,2,3]"));
    }

    @Test
    @DisplayName("多出一个字段就整份拒绝：多字段说明它没按结构走，同一响应里别的字段同样不可信")
    void rejectsUnknownField() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[\"问题？\"],\"extra\":1}"));
    }

    @Test
    @DisplayName("缺少 suggestion 或 similarQuestions：抛异常")
    void rejectsMissingField() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"similarQuestions\":[\"问题？\"]}"));
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"建议\"}"));
    }

    @Test
    @DisplayName("suggestion 为空串或纯空白：抛异常")
    void rejectsBlankSuggestion() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"\",\"similarQuestions\":[\"问题？\"]}"));
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"   \",\"similarQuestions\":[\"问题？\"]}"));
    }

    @Test
    @DisplayName("suggestion 超过 200 字：抛异常")
    void rejectsOverlongSuggestion() {
        String tooLong = "建".repeat(RagAnswerParser.MAX_SUGGESTION_LENGTH + 1);
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"" + tooLong + "\",\"similarQuestions\":[\"问题？\"]}"));
    }

    @Test
    @DisplayName("similarQuestions 为空数组：抛异常（章程要求必须返回相似问题）")
    void rejectsEmptyQuestions() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[]}"));
    }

    @Test
    @DisplayName("相似问题超过 5 条：抛异常")
    void rejectsTooManyQuestions() {
        String questions = IntStream.rangeClosed(1, RagAnswerParser.MAX_QUESTIONS + 1)
                .mapToObj(i -> "\"问题" + i + "？\"")
                .collect(Collectors.joining(","));
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[" + questions + "]}"));
    }

    @Test
    @DisplayName("相似问题中存在空元素：抛异常")
    void rejectsBlankQuestionElement() {
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[\"问题？\",\"  \"]}"));
    }

    @Test
    @DisplayName("单条相似问题超过 40 字：抛异常")
    void rejectsOverlongQuestion() {
        String tooLong = "问".repeat(RagAnswerParser.MAX_QUESTION_LENGTH + 1);
        assertThrows(AiResponseInvalidException.class, () -> parser.parse(
                "{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[\"" + tooLong + "\"]}"));
    }

    @Test
    @DisplayName("异常信息里带原文摘要，便于定位是模型返了什么（但不能把整段长文本写进日志）")
    void exceptionCarriesBrief() {
        AiResponseInvalidException e = assertThrows(AiResponseInvalidException.class,
                () -> parser.parse("{\"needHuman\":false,\"suggestion\":\"建议\",\"similarQuestions\":[]}"));

        assertTrue(e.getMessage().contains("similarQuestions"), "报错应指向具体哪个字段");
    }

}
