package com.smartticket.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合的单元测试（阶段2 第7天，章程第4周任务 8）。
 *
 * <p>融合是"错了也不会报错"的典型：分数算错、名次算错、去重漏了，
 * 结果都只是<b>排序变得不合理</b>，而排序不合理在几十条数据上肉眼很难看出来。
 * 所以这里把每条规则都钉死，尤其是第6天暴露的两件事：</p>
 * <ol>
 *   <li><b>并列要给相同名次</b>——否则字典序会冒充相关性；</li>
 *   <li><b>两路都召回的必须排在只被一路召回的前面</b>——这是 RRF 存在的意义。</li>
 * </ol>
 */
class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    // ------------------------------------------------------------ 基本规则

    @Test
    @DisplayName("只有一路有结果时照常输出，来源只标那一条路")
    void fusesWhenOnlyOnePathReturnsHits() {
        List<FusedHit> hits = fusion.fuse(
                List.of(hit("faq/01-账户问题.md", 0, 0.9)),
                List.of(),
                3);

        assertEquals(1, hits.size());
        assertEquals(Set.of(RetrievalSource.VECTOR), hits.get(0).sources());
        assertFalse(hits.get(0).fromBothPaths());
    }

    @Test
    @DisplayName("两路都召回的切片排在只被一路召回的前面——这正是 RRF 存在的意义")
    void chunkFoundByBothPathsOutranksSinglePathChunk() {
        // 两路都召回了 A；B 只被向量路召回且名次更高（rank 1，比 A 在向量路里的 rank 2 更靠前）
        List<FusedHit> hits = fusion.fuse(
                List.of(hit("faq/01-账户问题.md", 1, 0.9),      // B，向量路 rank 1
                        hit("faq/01-账户问题.md", 0, 0.8)),     // A，向量路 rank 2
                List.of(hit("faq/01-账户问题.md", 0, 0.36)),    // A，关键词路 rank 1
                3);

        assertEquals(2, hits.size());
        FusedHit both = hits.get(0);
        assertEquals(0, both.chunk().chunkIndex(), "两路都召回的那条应当排第一");
        assertTrue(both.fromBothPaths());
        assertEquals(Set.of(RetrievalSource.VECTOR, RetrievalSource.KEYWORD), both.sources());

        // 数值上确认：A = 1/62 + 1/61，B = 1/61，前者更大
        assertEquals(1.0 / 62 + 1.0 / 61, both.fusedScore(), 1e-12);
        assertEquals(1.0 / 61, hits.get(1).fusedScore(), 1e-12);
    }

    // ------------------------------------------------------------ 并列名次（第6天暴露问题）

    @Test
    @DisplayName("并列的分数给相同名次：三条同为 0.0909 的切片贡献完全相等，不让字典序冒充相关性")
    void tiedScoresShareTheSameRank() {
        // 关键词路实测就是这个样子：一次查询里 4 条同分
        List<FusedHit> hits = fusion.fuse(
                List.of(),
                List.of(hit("faq/02-支付问题.md", 0, 0.0909),
                        hit("faq/02-支付问题.md", 1, 0.0909),
                        hit("faq/04-工单流程与通用问题.md", 0, 0.0909),
                        hit("tickets/02-支付问题.md", 0, 0.0667)),
                5);

        assertEquals(4, hits.size());
        double tied = hits.get(0).fusedScore();
        assertEquals(1.0 / 61, tied, 1e-12, "并列项应拿到 rank 1 的贡献");
        for (int i = 0; i < 3; i++) {
            assertEquals(tied, hits.get(i).fusedScore(), 1e-12,
                    "三条同分切片必须拿到完全相同的贡献，实际第 " + i + " 条不同");
        }
        // 第四条分数更低（0.0667 与前三个不同），dense rank 应给 2 而不是 4
        assertEquals(1.0 / 62, hits.get(3).fusedScore(), 1e-12,
                "dense rank：并列占一个名次，下一个不同分数才是 rank+1");
    }

    @Test
    @DisplayName("名次越靠前贡献越大（分数不同时）")
    void higherRankContributesMore() {
        List<FusedHit> hits = fusion.fuse(
                List.of(hit("faq/01-账户问题.md", 0, 0.9), hit("faq/01-账户问题.md", 1, 0.5)),
                List.of(),
                5);

        assertTrue(hits.get(0).fusedScore() > hits.get(1).fusedScore());
    }

    // ------------------------------------------------------------ 去重与截断

    @Test
    @DisplayName("同一条路里出现重复切片时只贡献一次，不让某条路被重复加权")
    void duplicateChunkInOnePathContributesOnce() {
        RetrievalHit duplicated = hit("faq/01-账户问题.md", 0, 0.9);
        List<FusedHit> hits = fusion.fuse(List.of(), List.of(duplicated, duplicated), 5);

        assertEquals(1, hits.size());
        assertEquals(1.0 / 61, hits.get(0).fusedScore(), 1e-12, "重复出现不应叠加分数");
    }

    @Test
    @DisplayName("按 topN 截断；topN 非正数时返回空")
    void respectsTopN() {
        List<RetrievalHit> vectors = List.of(
                hit("faq/01-账户问题.md", 0, 0.9),
                hit("faq/01-账户问题.md", 1, 0.8),
                hit("faq/01-账户问题.md", 2, 0.7));

        assertEquals(2, fusion.fuse(vectors, List.of(), 2).size());
        assertTrue(fusion.fuse(vectors, List.of(), 0).isEmpty());
        assertTrue(fusion.fuse(vectors, List.of(), -1).isEmpty());
        assertEquals(3, fusion.fuse(vectors, List.of(), 99).size(), "topN 大于候选数时返回全部");
    }

    @Test
    @DisplayName("两路都为空时返回空，不抛异常")
    void emptyInputsProduceEmptyResult() {
        assertTrue(fusion.fuse(List.of(), List.of(), 3).isEmpty());
    }

    // ------------------------------------------------------------ 稳定性

    @Test
    @DisplayName("同一输入两次必得同一输出（顺序不会随实现细节漂移）")
    void outputIsStableForTheSameInput() {
        List<RetrievalHit> vectors = List.of(
                hit("faq/02-支付问题.md", 0, 0.5),
                hit("faq/01-账户问题.md", 0, 0.4));

        List<FusedHit> first = fusion.fuse(vectors, List.of(), 5);
        List<FusedHit> second = fusion.fuse(vectors, List.of(), 5);

        assertEquals(first, second);
        // 名次高的分更高，所以这一组其实不涉及并列；真正"分数相等"的情形见下面那个用例
        assertEquals("faq/02-支付问题.md", first.get(0).chunk().sourceFile());
    }

    @Test
    @DisplayName("融合分数真正相等时按来源文件与序号稳定排序（字典序只用于稳定，不参与相关性判断）")
    void tiedFusedScoresFallBackToStableOrdering() {
        // 两条分别来自两路、各自都是一路里的 rank 1，贡献完全相同
        List<FusedHit> hits = fusion.fuse(
                List.of(hit("tickets/01-账户问题.md", 0, 0.9)),
                List.of(hit("faq/01-账户问题.md", 0, 0.9)),
                5);

        assertEquals(2, hits.size());
        assertEquals(hits.get(0).fusedScore(), hits.get(1).fusedScore(), 1e-12);
        assertEquals("faq/01-账户问题.md", hits.get(0).chunk().sourceFile());
        assertEquals("tickets/01-账户问题.md", hits.get(1).chunk().sourceFile());
    }

    // ------------------------------------------------------------ 工具

    private static RetrievalHit hit(String sourceFile, int chunkIndex, double score) {
        KnowledgeChunk chunk = new KnowledgeChunk(sourceFile, "测试文档", "测试小节", chunkIndex, "正文");
        return new RetrievalHit(chunk, score);
    }

}
