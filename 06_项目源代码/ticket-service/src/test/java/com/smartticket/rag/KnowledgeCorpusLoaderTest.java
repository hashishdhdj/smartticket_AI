package com.smartticket.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库语料加载的测试（阶段2 第4天）。
 *
 * <p>本类直接用真实的语料文件跑，属于"对语料本身的质量检查"——
 * 语料写得不合规（比如漏了 {@code ##} 小节、忘了把声明放进前言）不会让程序报错，
 * 只会在检索效果上悄悄变差。这里把几条硬性约定钉死，让语料问题在提交前就暴露。</p>
 *
 * <p>不需要 Spring 上下文：加载器只依赖 classpath 资源，可以直接构造。</p>
 */
class KnowledgeCorpusLoaderTest {

    private static final String CORPUS_DISCLAIMER_FRAGMENT = "语料性质：演示语料";

    private final KnowledgeCorpusLoader loader = new KnowledgeCorpusLoader(new KnowledgeChunker());

    @Test
    @DisplayName("能加载到语料，且切片数量与文件规模相符")
    void loadsCorpus() {
        List<KnowledgeChunk> chunks = loader.loadAll();

        assertFalse(chunks.isEmpty(), "没加载到任何切片，检查语料目录是否存在 .md 文件");
        long faqCount = chunks.stream().filter(chunk -> chunk.sourceFile().startsWith("faq/")).count();
        long ticketCount = chunks.stream().filter(chunk -> chunk.sourceFile().startsWith("tickets/")).count();

        assertTrue(faqCount > 0, "FAQ 语料应有切片");
        assertTrue(ticketCount > 0, "历史工单语料应有切片");

        // 打印规模，作为"语料确实被读进来了"的人工可核对证据
        System.out.println("[知识库规模] 切片总数=" + chunks.size()
                + " | FAQ 切片=" + faqCount
                + " | 历史工单切片=" + ticketCount
                + " | 涉及文档数=" + chunks.stream().map(KnowledgeChunk::sourceFile).distinct().count());
    }

    @Test
    @DisplayName("每条切片的来源元数据都完整（约束要求检索结果可追溯来源）")
    void everyChunkHasCompleteMetadata() {
        for (KnowledgeChunk chunk : loader.loadAll()) {
            assertTrue(chunk.sourceFile() != null && !chunk.sourceFile().isBlank(), "sourceFile 不能为空");
            assertTrue(chunk.sourceTitle() != null && !chunk.sourceTitle().isBlank(),
                    "sourceTitle 不能为空：" + chunk.sourceFile());
            assertTrue(chunk.sectionTitle() != null && !chunk.sectionTitle().isBlank(),
                    "sectionTitle 不能为空：" + chunk.sourceFile());
            assertTrue(chunk.content() != null && !chunk.content().isBlank(),
                    "content 不能为空：" + chunk.sourceRef());
        }
    }

    @Test
    @DisplayName("只加载 faq/ 与 tickets/，语料根目录的说明文件不会被当成知识检索出来")
    void onlyScansCorpusSubdirectories() {
        List<String> sourceFiles = loader.loadAll().stream()
                .map(KnowledgeChunk::sourceFile)
                .distinct()
                .toList();

        for (String sourceFile : sourceFiles) {
            assertTrue(sourceFile.startsWith("faq/") || sourceFile.startsWith("tickets/"),
                    "出现了不应参与切分的文件：" + sourceFile);
        }
        assertFalse(sourceFiles.contains("README.md"),
                "语料根目录的 README 是给人看的说明，不该进知识库");
    }

    @Test
    @DisplayName("全部语料的声明段落都被排除在切片之外（逐条检查，而非抽查）")
    void disclaimerNeverAppearsInAnyChunk() {
        List<String> polluted = loader.loadAll().stream()
                .filter(chunk -> chunk.content().contains(CORPUS_DISCLAIMER_FRAGMENT))
                .map(KnowledgeChunk::sourceRef)
                .collect(Collectors.toList());

        assertTrue(polluted.isEmpty(),
                "以下切片的正文里混入了语料声明，说明前言没有被正确排除：" + polluted);
    }

    @Test
    @DisplayName("切片序号在每份文档内从 0 开始连续递增（顺序可还原）")
    void chunkIndexIsContinuousWithinEachDocument() {
        Map<String, List<KnowledgeChunk>> byFile = loader.loadAll().stream()
                .collect(Collectors.groupingBy(KnowledgeChunk::sourceFile));

        byFile.forEach((sourceFile, chunks) -> {
            for (int i = 0; i < chunks.size(); i++) {
                assertEquals(i, chunks.get(i).chunkIndex(),
                        "序号不连续：" + sourceFile + " 第 " + i + " 条");
            }
        });
    }

    @Test
    @DisplayName("同一份语料两次加载结果完全一致（扫描顺序不稳定会让向量库内容漂移）")
    void loadingIsDeterministic() {
        assertEquals(loader.loadAll(), loader.loadAll(),
                "两次加载结果不一致，说明文件扫描顺序没有稳定下来");
    }

}
