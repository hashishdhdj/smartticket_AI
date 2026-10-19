package com.smartticket.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 分类结果解析与校验的单元测试（阶段2 第2天）。
 *
 * <p>这是阶段2 约束「必须处理 JSON 解析失败」的可执行证据。每条非法输入都断言了
 * <b>具体的失败原因</b>，而不是只断言"抛了异常"——只断言抛异常的话，
 * 哪怕所有校验都被删掉、由别的原因碰巧抛了异常，测试照样绿。</p>
 *
 * <p>合法样例刻意用 2026-09-25 真实调用 DeepSeek 得到的返回结构，
 * 保证测试里的"合法输入"是模型真的会产出的形状，而不是我们想象出来的。</p>
 */
class AiClassificationParserTest {

    /** 真实调用实测返回过的结构（见决策记录 20260925 第六节），字段顺序与取值风格照搬 */
    private static final String VALID_JSON = """
            {"category":"功能异常","priority":"HIGH","confidence":0.95,
             "keywords":["登录","验证码异常"],"reason":"用户无法登录且与验证码异常相关，影响核心功能。"}
            """;

    private final AiClassificationParser parser = new AiClassificationParser();

    // ------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("合法 JSON 各字段被正确解析")
    void parsesValidJson() {
        AiClassificationResult result = parser.parse(VALID_JSON);

        assertEquals("功能异常", result.category());
        assertEquals("HIGH", result.priority());
        assertEquals(0.95, result.confidence());
        assertEquals(List.of("登录", "验证码异常"), result.keywords());
        assertTrue(result.reason().startsWith("用户无法登录"));
    }

    @Test
    @DisplayName("模型用 Markdown 代码块包住 JSON 时仍能解析（实测不加，但换了模型不保证）")
    void unwrapsMarkdownCodeFence() {
        String fenced = "```json\n" + VALID_JSON + "\n```";

        AiClassificationResult result = parser.parse(fenced);

        assertEquals("功能异常", result.category());
    }

    @Test
    @DisplayName("返回值已规范化：trim 空白、优先级统一大写，调用方可直接入库")
    void returnsNormalizedValues() {
        String messy = """
                {"category":"  功能异常  ","priority":" high ","confidence":0.5,
                 "keywords":[" 登录 ","验证码异常  "],"reason":"  理由  "}
                """;

        AiClassificationResult result = parser.parse(messy);

        assertEquals("功能异常", result.category(), "分类标签应去掉前后空白");
        assertEquals("HIGH", result.priority(), "优先级应统一为大写，库里存的就是大写");
        assertEquals(List.of("登录", "验证码异常"), result.keywords(), "关键词元素应逐个 trim");
        assertEquals("理由", result.reason(), "理由应 trim");
    }

    // ------------------------------------------------------------ 结构违规

    @Test
    @DisplayName("多出字段直接拒绝：模型多回字段说明它没按 Schema 走，其他字段也不可信")
    void rejectsAdditionalProperty() {
        String extra = """
                {"category":"功能异常","priority":"HIGH","confidence":0.9,
                 "keywords":["登录"],"reason":"理由","extraField":"模型自己加的"}
                """;

        // 这里必须断言 Jackson 的"未知字段"专用措辞，不能只断言 message 里出现 extraField：
        // 解析失败的 message 会附带原文片段，而原文里本来就有 extraField 这个词，
        // 那样写的话"因缺字段而失败"等其他原因也会让本测试通过（弱断言）
        assertInvalid(extra, "Unrecognized field");
    }

    @Test
    @DisplayName("缺字段被拒绝：confidence 缺失时报错指向 confidence")
    void rejectsMissingField() {
        String missing = """
                {"category":"功能异常","priority":"HIGH","keywords":["登录"],"reason":"理由"}
                """;

        assertInvalid(missing, "confidence 缺失");
    }

    @Test
    @DisplayName("完全不是 JSON 时被拒绝，且错误信息里带上了原文片段便于排查")
    void rejectsNonJsonText() {
        assertInvalid("我认为这个工单属于功能异常，优先级高。", "不是合法 JSON");
    }

    @Test
    @DisplayName("空内容被拒绝")
    void rejectsBlankContent() {
        assertInvalid("   ", "返回内容为空");
    }

    // ------------------------------------------------------------ 取值违规

    @Test
    @DisplayName("分类不在受控清单内时被拒绝，并回显允许值（这是「不允许 AI 自由发挥」的落点）")
    void rejectsCategoryOutsideWhitelist() {
        String synonym = jsonWithCategory("账号问题");

        assertInvalid(synonym, "不在允许清单内");
    }

    @Test
    @DisplayName("优先级非法（不在 LOW/MEDIUM/HIGH/URGENT 内）时被拒绝")
    void rejectsUnknownPriority() {
        String bad = """
                {"category":"功能异常","priority":"VERY_HIGH","confidence":0.9,
                 "keywords":["登录"],"reason":"理由"}
                """;

        assertInvalid(bad, "priority 非法");
    }

    @Test
    @DisplayName("confidence 越界被拒绝：1.5 与 -0.1 都不行")
    void rejectsConfidenceOutOfRange() {
        assertInvalid(jsonWithConfidence("1.5"), "confidence 越界");
        assertInvalid(jsonWithConfidence("-0.1"), "confidence 越界");
    }

    @Test
    @DisplayName("confidence 为 0 是合法值：要能区分「完全没把握」与「字段缺失」")
    void acceptsZeroConfidence() {
        AiClassificationResult result = parser.parse(jsonWithConfidence("0"));

        assertEquals(0.0, result.confidence());
    }

    @Test
    @DisplayName("keywords 为空数组被拒绝")
    void rejectsEmptyKeywords() {
        String empty = """
                {"category":"功能异常","priority":"HIGH","confidence":0.9,"keywords":[],"reason":"理由"}
                """;

        assertInvalid(empty, "keywords 缺失或为空数组");
    }

    @Test
    @DisplayName("keywords 数量超上限被拒绝")
    void rejectsTooManyKeywords() {
        String tooMany = """
                {"category":"功能异常","priority":"HIGH","confidence":0.9,
                 "keywords":["1","2","3","4","5","6","7","8","9"],"reason":"理由"}
                """;

        assertInvalid(tooMany, "keywords 数量超限");
    }

    @Test
    @DisplayName("单个关键词超长被拒绝")
    void rejectsOverlongKeyword() {
        String longKeyword = "长".repeat(AiClassificationParser.MAX_KEYWORD_LENGTH + 1);
        String json = """
                {"category":"功能异常","priority":"HIGH","confidence":0.9,
                 "keywords":["%s"],"reason":"理由"}
                """.formatted(longKeyword);

        assertInvalid(json, "关键词超长");
    }

    @Test
    @DisplayName("reason 超长被拒绝")
    void rejectsOverlongReason() {
        String longReason = "理".repeat(AiClassificationParser.MAX_REASON_LENGTH + 1);
        String json = """
                {"category":"功能异常","priority":"HIGH","confidence":0.9,
                 "keywords":["登录"],"reason":"%s"}
                """.formatted(longReason);

        assertInvalid(json, "reason 超长");
    }

    // ------------------------------------------------------------ 工具

    /**
     * 断言解析失败，且失败原因包含指定片段。
     *
     * <p>刻意校验 message 内容：只断言"抛异常"的话，任何一处校验被误删、
     * 由别的原因抛出同类异常，测试依然会通过。</p>
     */
    private void assertInvalid(String json, String expectedMessagePart) {
        AiResponseInvalidException exception = assertThrows(
                AiResponseInvalidException.class, () -> parser.parse(json));
        assertTrue(exception.getMessage().contains(expectedMessagePart),
                "失败原因应包含「" + expectedMessagePart + "」，实际=" + exception.getMessage());
    }

    private String jsonWithCategory(String category) {
        return """
                {"category":"%s","priority":"HIGH","confidence":0.9,"keywords":["登录"],"reason":"理由"}
                """.formatted(category);
    }

    private String jsonWithConfidence(String confidence) {
        return """
                {"category":"功能异常","priority":"HIGH","confidence":%s,"keywords":["登录"],"reason":"理由"}
                """.formatted(confidence);
    }

}
