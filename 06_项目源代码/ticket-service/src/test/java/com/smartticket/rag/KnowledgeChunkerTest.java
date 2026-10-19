package com.smartticket.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档切分器的单元测试（阶段2 第4天）。
 *
 * <p>切分是 RAG 里"错了很难发现"的一环：切坏了不会报错，只会在检索时表现为
 * "明明有这条知识却召不回来"。因此这里不测"能跑"，而是把每条切分规则都钉死：
 * 按什么切、在哪里不切、重叠从哪开始、元数据齐不齐。</p>
 */
class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    // ------------------------------------------------------------ 基本切分

    @Test
    @DisplayName("按二级标题切分：每个 ## 小节产出一个切片，小节标题进元数据")
    void splitsBySection() {
        String markdown = """
                # 测试文档

                ## 第一节

                第一节的正文内容。

                ## 第二节

                第二节的正文内容。
                """;

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", markdown);

        assertEquals(2, chunks.size());
        assertEquals("第一节", chunks.get(0).sectionTitle());
        assertEquals("第二节", chunks.get(1).sectionTitle());
        assertTrue(chunks.get(0).content().contains("第一节的正文内容"));
        assertTrue(chunks.get(1).content().contains("第二节的正文内容"));
    }

    @Test
    @DisplayName("文档标题进元数据；标题与首个 ## 之间的前言不参与切分（避免把声明当知识检索出来）")
    void preambleIsExcludedFromChunks() {
        String markdown = """
                # 账户问题 FAQ

                > 语料性质：演示语料，请勿当作生产内容使用。本段是给人看的声明。

                ## 如何重置密码

                在登录页点击忘记密码，通过短信验证码重置。
                """;

        List<KnowledgeChunk> chunks = chunker.split("faq/account.md", markdown);

        assertEquals(1, chunks.size());
        assertEquals("账户问题 FAQ", chunks.get(0).sourceTitle());
        assertFalse(chunks.get(0).content().contains("演示语料"),
                "前言里的声明不该进入切片，否则用户问业务问题时会把声明召回出来");
        assertTrue(chunks.get(0).content().contains("忘记密码"));
    }

    @Test
    @DisplayName("文档没有一级标题时，来源标题回退为文件名（来源信息永不为空）")
    void fallsBackToFileNameWhenNoDocTitle() {
        String markdown = """
                ## 没有文档标题的小节

                正文。
                """;

        List<KnowledgeChunk> chunks = chunker.split("tickets/02-支付问题.md", markdown);

        assertEquals(1, chunks.size());
        assertEquals("02-支付问题", chunks.get(0).sourceTitle());
    }

    @Test
    @DisplayName("来源元数据完整：文件路径 + 文档标题 + 小节标题 + 连续序号（约束要求可追溯来源）")
    void chunkCarriesFullSourceMetadata() {
        String markdown = """
                # 支付问题 FAQ

                ## 重复扣款如何退款

                正文一。

                ## 订单失效怎么办

                正文二。
                """;

        List<KnowledgeChunk> chunks = chunker.split("faq/02-支付问题.md", markdown);

        assertEquals(2, chunks.size());
        assertEquals("faq/02-支付问题.md", chunks.get(0).sourceFile());
        assertEquals("支付问题 FAQ", chunks.get(0).sourceTitle());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals(1, chunks.get(1).chunkIndex(), "序号应在文档内跨小节连续，便于还原顺序");
        assertTrue(chunks.get(0).sourceRef().contains("faq/02-支付问题.md"));
        assertTrue(chunks.get(0).sourceRef().contains("重复扣款如何退款"));
    }

    // ------------------------------------------------------------ 超长小节

    @Test
    @DisplayName("超长小节被切成多块，每块不超过上限（重叠部分除外）")
    void overlongSectionIsSplitIntoMultipleChunks() {
        String section = buildLongSection(12);

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", "# 文档\n\n## 超长小节\n\n" + section);

        assertTrue(chunks.size() > 1, "超过上限的小节应被切成多块，实际只切出 " + chunks.size() + " 块");
        assertTrue(chunks.get(0).content().length() <= KnowledgeChunker.MAX_CHUNK_CHARS,
                "首块不该超上限，实际 " + chunks.get(0).content().length());
        for (KnowledgeChunk chunk : chunks) {
            int limit = KnowledgeChunker.MAX_CHUNK_CHARS + KnowledgeChunker.OVERLAP_CHARS;
            assertTrue(chunk.content().length() <= limit,
                    "切片长度应在上限加重叠之内，实际 " + chunk.content().length() + "，内容=" + chunk.content());
        }
    }

    @Test
    @DisplayName("不在句子中间断开：每块结尾都是句末标点")
    void doesNotBreakInTheMiddleOfSentence() {
        String section = buildLongSection(12);

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", "# 文档\n\n## 超长小节\n\n" + section);

        for (KnowledgeChunk chunk : chunks) {
            String content = chunk.content().strip();
            assertTrue(content.endsWith("。"),
                    "切片应以句末标点结尾，实际结尾=" + content.substring(Math.max(0, content.length() - 20)));
        }
    }

    @Test
    @DisplayName("相邻切片有重叠，且重叠从一个完整句子开始")
    void adjacentChunksOverlapAtSentenceBoundary() {
        String section = buildLongSection(12);

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", "# 文档\n\n## 超长小节\n\n" + section);

        String first = chunks.get(0).content();
        String second = chunks.get(1).content();
        String overlapFirstLine = second.substring(0, second.indexOf('\n'));

        assertTrue(first.contains(overlapFirstLine), "第二块的开头应来自第一块的结尾（重叠语义）");
        assertTrue(overlapFirstLine.endsWith("。"), "重叠必须从完整句子开始，不能是半句");
        assertFalse(first.contains(second), "第二块只应头部重叠，不能整块被第一块包含");
    }

    @Test
    @DisplayName("重叠只在同一个小节内部，不跨小节")
    void overlapDoesNotCrossSections() {
        String longSection = buildLongSection(12);
        String markdown = "# 文档\n\n## 长小节\n\n" + longSection + "\n\n## 短小节\n\n这是一个很短的小节。\n";

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", markdown);

        KnowledgeChunk shortSectionChunk = chunks.stream()
                .filter(chunk -> "短小节".equals(chunk.sectionTitle()))
                .findFirst()
                .orElseThrow();
        assertEquals("这是一个很短的小节。", shortSectionChunk.content(),
                "下一小节的首块不该带上一小节的重叠内容");
    }

    @Test
    @DisplayName("小节的第一个切片不带重叠")
    void firstChunkHasNoOverlap() {
        String markdown = "# 文档\n\n## 短小节\n\n只有一句话。\n";

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", markdown);

        assertEquals("只有一句话。", chunks.get(0).content());
    }

    @Test
    @DisplayName("单句本身超长时按字符硬切，不会因无法断句而产出超长切片")
    void hardSplitsWhenSingleSentenceIsTooLong() {
        String endless = "甲".repeat(KnowledgeChunker.MAX_CHUNK_CHARS * 2 + 100);

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", "# 文档\n\n## 无标点长文本\n\n" + endless);

        assertTrue(chunks.size() >= 3, "应按上限硬切成多块，实际 " + chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            int limit = KnowledgeChunker.MAX_CHUNK_CHARS + KnowledgeChunker.OVERLAP_CHARS;
            assertTrue(chunk.content().length() <= limit,
                    "硬切后仍不应超过上限加重叠，实际 " + chunk.content().length());
        }
    }

    // ------------------------------------------------------------ 边界与异常输入

    @Test
    @DisplayName("只有标题没有正文的小节不产出切片（空切片会白占向量记录并挤掉有用内容）")
    void emptySectionProducesNoChunk() {
        String markdown = """
                # 文档

                ## 只有标题的小节

                ## 有内容的小节

                正文在这里。
                """;

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", markdown);

        assertEquals(1, chunks.size());
        assertEquals("有内容的小节", chunks.get(0).sectionTitle());
    }

    @Test
    @DisplayName("没有任何二级标题的文档返回空列表，不抛异常")
    void documentWithoutSectionsReturnsEmptyList() {
        assertTrue(chunker.split("faq/test.md", "# 只有文档标题\n\n一段没有小节的正文。").isEmpty());
    }

    @Test
    @DisplayName("空内容与 null 输入返回空列表，不抛异常")
    void blankInputReturnsEmptyList() {
        assertTrue(chunker.split("faq/test.md", "").isEmpty());
        assertTrue(chunker.split("faq/test.md", "   \n  ").isEmpty());
        assertTrue(chunker.split("faq/test.md", null).isEmpty());
    }

    @Test
    @DisplayName("HTML 注释被剥离，不进正文（语料里用它标注元信息）")
    void htmlCommentsAreRemoved() {
        String markdown = """
                # 文档

                ## 小节标题

                <!-- 这是一条给人看的备注 -->
                正文内容。
                """;

        List<KnowledgeChunk> chunks = chunker.split("faq/test.md", markdown);

        assertFalse(chunks.get(0).content().contains("<!--"));
        assertFalse(chunks.get(0).content().contains("给人看的备注"));
        assertTrue(chunks.get(0).content().contains("正文内容"));
    }

    // ------------------------------------------------------------ 工具

    /** 构造一段由若干完整句子组成的长文本，每句约 50 字 */
    private String buildLongSection(int sentenceCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= sentenceCount; i++) {
            builder.append("这是第").append(i).append("句用于测试切分的句子，它的长度大约五十个字，")
                    .append("目的是让整段内容超过单块上限，从而必须被继续切开。");
        }
        return builder.toString();
    }

}
