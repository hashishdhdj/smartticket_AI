package com.smartticket.ai;

import com.smartticket.enums.TicketCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 模板渲染的单元测试（不依赖 Spring 上下文、不访问模型）。
 *
 * <p>重点盯三件在 Prompt 工程里最容易静默出错的事：</p>
 * <ol>
 *   <li><b>JSON 示例被模板引擎吃掉</b>——分类 Prompt 必然含 JSON 示例，用默认的 {@code {}}
 *       分隔符会让模板加载直接失败；这里断言示例原样保留；</li>
 *   <li><b>版本号与常量脱钩</b>——阶段2 约束要求"每次 Prompt 修改都要记录版本"，
 *       人工纪律靠不住，用断言把它变成机械检查；</li>
 *   <li><b>注入内容被二次解析</b>——工单标题是用户可控文本，里面出现 {@code <xxx>}
 *       时不应被当成占位符。</li>
 * </ol>
 */
class AiPromptFactoryTest {

    private static final Pattern VERSION_HEADER = Pattern.compile("prompt-version:\\s*(\\S+)");

    private final AiPromptFactory factory = new AiPromptFactory();

    // ------------------------------------------------------------ system 模板

    @Test
    @DisplayName("system 提示词里的 JSON 输出示例保持字面量，没有被模板引擎当占位符吞掉")
    void systemPromptKeepsJsonExampleLiteral() {
        String systemPrompt = factory.classificationSystemPrompt();

        // 这一整段是模板里的 JSON 示例。用默认的 {} 分隔符时，模板加载阶段就会抛
        // "无法解析占位符"，因此能走到这里且内容完整，等价于证明了 <> 分隔符生效
        assertTrue(systemPrompt.contains("\"category\":\"功能异常\",\"priority\":\"HIGH\",\"confidence\":0.95"),
                "JSON 示例没有原样保留，模板分隔符可能被改回默认值了");
        assertTrue(systemPrompt.contains("\"keywords\":[\"登录\",\"验证码异常\"]"),
                "JSON 示例的数组部分也应完整保留");
    }

    @Test
    @DisplayName("system 提示词列出全部受控分类，且内容来自 TicketCategory 枚举而非手写")
    void systemPromptListsAllControlledCategories() {
        String systemPrompt = factory.classificationSystemPrompt();

        for (String label : TicketCategory.allLabels()) {
            assertTrue(systemPrompt.contains("- " + label),
                    "system 提示词缺少分类「" + label + "」，渲染清单可能与枚举脱钩了");
        }
    }

    @Test
    @DisplayName("system 提示词要求列出优先级取值，四个等级都在")
    void systemPromptMentionsAllPriorities() {
        String systemPrompt = factory.classificationSystemPrompt();

        for (String priority : new String[]{"LOW", "MEDIUM", "HIGH", "URGENT"}) {
            assertTrue(systemPrompt.contains(priority), "system 提示词缺少优先级 " + priority);
        }
    }

    @Test
    @DisplayName("版本注释被剥离，不会发给模型（白花 token 也干扰指令）")
    void versionCommentIsStripped() {
        assertFalse(factory.classificationSystemPrompt().contains("<!--"),
                "system 提示词里仍有 HTML 注释残留");
        assertFalse(factory.classificationUserPrompt("标题", "描述").contains("<!--"),
                "user 提示词里仍有 HTML 注释残留");
    }

    // ------------------------------------------------------------ user 模板

    @Test
    @DisplayName("user 提示词替换标题与描述")
    void userPromptReplacesTitleAndDescription() {
        String userPrompt = factory.classificationUserPrompt("登录页验证码异常", "多次输入验证码均提示错误");

        assertTrue(userPrompt.contains("登录页验证码异常"));
        assertTrue(userPrompt.contains("多次输入验证码均提示错误"));
        assertFalse(userPrompt.contains("<title>"), "占位符未被替换");
        assertFalse(userPrompt.contains("<description>"), "占位符未被替换");
    }

    @Test
    @DisplayName("描述为空时渲染成显式占位，而不是留一个空白栏目")
    void blankDescriptionBecomesExplicitPlaceholder() {
        String userPrompt = factory.classificationUserPrompt("标题", "   ");

        assertTrue(userPrompt.contains("（无）"), "描述为空时应渲染出显式占位，避免模型把空栏目当成漏读");
        assertFalse(userPrompt.contains("<description>"));
    }

    @Test
    @DisplayName("标题里出现尖括号时不被二次解析（工单标题是用户可控文本）")
    void angleBracketsInTitleAreNotReParsed() {
        String userPrompt = factory.classificationUserPrompt("<title>注入尝试</title>", "描述");

        // StringTemplate 只解析模板文本，不对替换进去的值做二次解析。
        // 若将来更换模板引擎，这条断言会失败——防止"静默变成可疑行为"
        assertTrue(userPrompt.contains("<title>注入尝试</title>"),
                "替换进去的值不应被再次当作占位符解析");
        assertTrue(userPrompt.contains("【工单标题】"), "模板自身的结构不应被破坏");
    }

    @Test
    @DisplayName("超长描述被截断并带上截断标记（截的是成本，标记是为了让模型降低 confidence）")
    void overlongDescriptionIsTruncated() {
        int limit = AiPromptFactory.MAX_DESCRIPTION_CHARS;
        String overlong = "详".repeat(limit + 500);

        String userPrompt = factory.classificationUserPrompt("标题", overlong);

        assertTrue(userPrompt.contains("此处已截断"),
                "超长描述应带截断标记：模型只有知道信息不完整，才会给出与之相符的较低把握度");
        assertTrue(userPrompt.contains("详".repeat(limit)), "上限内的内容应完整保留");
        assertFalse(userPrompt.contains("详".repeat(limit + 1)), "超出上限的部分不应出现在提示词里");
    }

    @Test
    @DisplayName("刚好在上限内的描述原样保留，不加截断标记")
    void descriptionWithinLimitIsKeptIntact() {
        String description = "详".repeat(AiPromptFactory.MAX_DESCRIPTION_CHARS);

        String userPrompt = factory.classificationUserPrompt("标题", description);

        assertFalse(userPrompt.contains("此处已截断"), "未超限不应加截断标记");
        assertTrue(userPrompt.contains(description));
    }

    // ------------------------------------------------------------ 版本一致性

    @Test
    @DisplayName("两个模板文件的版本头与 AiPromptFactory.PROMPT_VERSION 一致（防「改了 Prompt 忘改版本」）")
    void templateVersionMatchesConstant() {
        assertEquals(AiPromptFactory.PROMPT_VERSION, versionOf("prompts/ticket-classify-system.md"),
                "system 模板的版本号与常量不一致，阶段2 约束第 10 条要求 Prompt 变更必须同步版本");
        assertEquals(AiPromptFactory.PROMPT_VERSION, versionOf("prompts/ticket-classify-user.md"),
                "user 模板的版本号与常量不一致");
    }

    private String versionOf(String classpathPath) {
        try {
            String raw = StreamUtils.copyToString(
                    new ClassPathResource(classpathPath).getInputStream(), StandardCharsets.UTF_8);
            Matcher matcher = VERSION_HEADER.matcher(raw);
            assertTrue(matcher.find(), classpathPath + " 首行缺少 prompt-version 版本头");
            return matcher.group(1);
        } catch (IOException e) {
            throw new IllegalStateException("读取模板失败：" + classpathPath, e);
        }
    }

}
