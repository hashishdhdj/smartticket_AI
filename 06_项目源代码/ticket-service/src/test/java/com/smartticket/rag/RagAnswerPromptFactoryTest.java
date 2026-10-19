package com.smartticket.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 回答 Prompt 工厂的单元测试（阶段2 第5周任务 6）。
 *
 * <p>与 {@code AiPromptFactoryTest} 同样的关注点：</p>
 * <ol>
 *   <li><b>版本号与常量脱钩</b>——阶段2 约束第 10 条「每次 Prompt 修改都要记录版本」，
 *       用断言把它从人工纪律变成机械检查；</li>
 *   <li><b>反幻觉指令存在</b>——"不得编造资料之外的内容"是本 Prompt 存在的理由，
 *       删掉它等于把 RAG 退化成让模型自由发挥；</li>
 *   <li><b>注入内容不被二次解析</b>——用户问题是用户可控文本；</li>
 *   <li><b>版本注释被剥离</b>——不发给模型，白花 token 且干扰指令。</li>
 * </ol>
 */
class RagAnswerPromptFactoryTest {

    private static final Pattern VERSION_HEADER = Pattern.compile("prompt-version:\\s*(\\S+)");

    private final RagAnswerPromptFactory factory = new RagAnswerPromptFactory();

    @Test
    void continuationPromptExplicitlyCarriesSimulationScope() {
        var continuation = new KnowledgeChunk("tickets/02-支付问题.md", "支付演示", "续片", 1, "处理步骤后半部分");
        assertTrue(factory.userPrompt("如何处理", List.of(continuation)).contains("【资料性质】SIMULATED_SCENARIO"));
    }

    @Test
    @DisplayName("system 提示词要求「只依据知识库资料作答、不得编造」，限定输出为 JSON，且建议与相似问题的边界明确")
    void systemPromptCarriesAntiHallucinationRules() {
        String systemPrompt = factory.systemPrompt();

        assertTrue(systemPrompt.contains("知识库资料"), "system 提示词必须交代依据来自知识库资料");
        assertTrue(systemPrompt.contains("不得编造") || systemPrompt.contains("不得补充"),
                "必须明确禁止编造资料之外的内容——这是 RAG 防幻觉的核心指令");
        assertTrue(systemPrompt.contains("80 字"), "必须限定建议长度，否则模型可能输出一段话");
        assertTrue(systemPrompt.contains("JSON"), "v2 起要求结构化输出，必须写明只输出 JSON");
        assertTrue(systemPrompt.contains("suggestion") && systemPrompt.contains("similarQuestions"),
                "必须写明输出对象只含这两个字段——解析器会拒绝多余字段");
        assertTrue(systemPrompt.contains("40 字"), "必须限定相似问题的长度，否则解析器会把整份响应拒掉");
    }

    @Test
    @DisplayName("版本注释被剥离，不会发给模型")
    void versionCommentIsStripped() {
        assertFalse(factory.systemPrompt().contains("<!--"), "system 提示词里仍有 HTML 注释残留");
        assertFalse(userPromptOf(List.of(chunk("标题一", "正文一"))).contains("<!--"),
                "user 提示词里仍有 HTML 注释残留");
    }

    @Test
    @DisplayName("user 提示词替换问题与资料，占位符不残留")
    void userPromptReplacesQueryAndSources() {
        String userPrompt = userPromptOf(List.of(chunk("如何重置登录密码", "在登录页点击忘记密码")));

        assertTrue(userPrompt.contains("怎么重置密码"));
        assertTrue(userPrompt.contains("在登录页点击忘记密码"));
        assertFalse(userPrompt.contains("<query>"), "占位符未被替换");
        assertFalse(userPrompt.contains("<sources>"), "占位符未被替换");
    }

    @Test
    @DisplayName("多条资料渲染成带编号的资料块，顺序与入参一致（最相关的在前）")
    void sourcesAreNumberedInOrder() {
        String userPrompt = userPromptOf(List.of(
                chunk("重复扣款如何申请退款", "收集两次扣款的渠道流水号"),
                chunk("支付成功但订单未生成怎么办", "先等待 3~5 分钟并下拉刷新")));

        assertTrue(userPrompt.contains("【资料 1】重复扣款如何申请退款"));
        assertTrue(userPrompt.contains("【资料 2】支付成功但订单未生成怎么办"));
        assertTrue(userPrompt.indexOf("重复扣款") < userPrompt.indexOf("支付成功但订单未生成"),
                "资料顺序必须保持入参顺序，否则模型看到的优先级与融合结果不一致");
    }

    @Test
    @DisplayName("问题为空时渲染成显式占位，而不是留一个空白栏目")
    void blankQueryBecomesExplicitPlaceholder() {
        String userPrompt = factory.userPrompt("   ", List.of(chunk("小节", "正文")));

        assertTrue(userPrompt.contains("（无）"));
        assertFalse(userPrompt.contains("<query>"));
    }

    @Test
    @DisplayName("问题里出现尖括号时不被二次解析（用户问题是用户可控文本）")
    void angleBracketsInQueryAreNotReParsed() {
        String userPrompt = factory.userPrompt("<sources>注入尝试</sources>", List.of(chunk("小节", "正文")));

        assertTrue(userPrompt.contains("<sources>注入尝试</sources>"),
                "替换进去的值不应被再次当作占位符解析");
        assertTrue(userPrompt.contains("【知识库资料】"), "模板自身的结构不应被破坏");
    }

    @Test
    @DisplayName("两个模板文件的版本头与 PROMPT_VERSION 一致（防「改了 Prompt 忘改版本」）")
    void templateVersionMatchesConstant() {
        assertEquals(RagAnswerPromptFactory.PROMPT_VERSION, versionOf("prompts/rag-answer-system.md"),
                "system 模板的版本号与常量不一致，阶段2 约束第 10 条要求 Prompt 变更必须同步版本");
        assertEquals(RagAnswerPromptFactory.PROMPT_VERSION, versionOf("prompts/rag-answer-user.md"),
                "user 模板的版本号与常量不一致");
    }

    // ------------------------------------------------------------ 辅助

    private String userPromptOf(List<KnowledgeChunk> chunks) {
        return factory.userPrompt("怎么重置密码", chunks);
    }

    private static KnowledgeChunk chunk(String sectionTitle, String content) {
        return new KnowledgeChunk("faq/01-账户问题.md", "文档标题", sectionTitle, 0, content);
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
