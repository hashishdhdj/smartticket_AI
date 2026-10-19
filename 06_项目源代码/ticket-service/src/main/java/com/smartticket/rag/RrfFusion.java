package com.smartticket.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双路结果的 RRF 融合（阶段2 第7天，章程第4周任务 8）。
 *
 * <h3>为什么用 RRF（Reciprocal Rank Fusion）而不是分数加权</h3>
 * 两条路的分数<b>尺度完全不同</b>且不可比：向量路是余弦相似度（实测 0.56~0.76），
 * 关键词路是字符三元组相似度（实测 0.09~0.36）。直接把两列数加权相加，
 * 等于让"数值天生偏大"的那一路自动获得更高权重——那是尺度的偶然，不是相关性的判断。
 * 想修正就得先做分数归一化，而归一化依赖<b>当次结果分布</b>（同一条切片在这次查询里被归一化成 0.8，
 * 换个查询就可能变成 0.3），不稳定、也不可解释。
 *
 * <p>RRF 只比<b>排名</b>，不比分数：</p>
 *
 * <pre>
 * score(d) = Σ  1 / (k + rank(d))        只对"召回了 d 的那些路"求和
 * </pre>
 *
 * <p>于是"两路都认同"的结果天然排在"只有一路认同"的前面，而两路的分数尺度差异被完全绕开。
 * 这是异源检索融合的标准做法，也是本次采用它的唯一理由。</p>
 *
 * <h3>k 取 60，且刻意不做成配置项</h3>
 * 当前沿用平滑常数 60。项目已有评估集，但尚未完成不同 k 的独立对照实验，
 * 因此暂不外置或宣称该值最优；后续应先冻结题集、比较召回指标，再决定是否调整。
 *
 * <p>k=60 还有一个值得知道的副作用：rank 1 与 rank 5 的贡献分别是 1/61 与 1/65，
 * 差不到 7%。也就是说 <b>RRF 主要看"被几条路、以多高的名次召回"这个组合，对名次的细微差别不敏感。</b>
 * 这正好削弱了第6天发现的问题——关键词路大量并列时名次本身信息量低。</p>
 *
 * <h3>并列名次用 dense rank（第6天暴露问题的处理之一）</h3>
 * 第6天实测：关键词路的分数<b>大量并列</b>（一次查询里 4 条同为 0.0909），
 * 而当时的结果顺序是由 SQL 的 tie-break（{@code source_file, chunk_index}）决定的——
 * 那是<b>字典序，不是相关性</b>。若不处理，等于把字典序当成相关性信号喂给融合。
 *
 * <p>本类对并列给<b>相同名次</b>（dense rank：并列者同名次，下一个不同分数才是名次 +1），
 * 让并列项获得完全相同的贡献——不假装知道它们谁更相关。</p>
 *
 * <p>注意本类里也有一处按 {@code source_file, chunk_index} 的排序，但它的作用完全不同：
 * 只用于<b>融合分数真正相等时保证结果稳定</b>（否则同一输入两次可能给出不同顺序，测试无法断言）。
 * 它不参与相关性判断——这一点与第6天的情形必须区分开。</p>
 */
@Slf4j
@Component
public class RrfFusion {

    /** RRF 的平滑常数，取原论文经验值 60 */
    static final int RRF_K = 60;

    /**
     * 融合两条路的结果。
     *
     * @param vectorHits  向量路的命中，须按分数降序
     * @param keywordHits 关键词路的命中，须按分数降序
     * @param topN        融合后保留几条
     * @return 按融合分数降序的结果；分数相同时按来源文件与序号稳定排序
     */
    public List<FusedHit> fuse(List<RetrievalHit> vectorHits, List<RetrievalHit> keywordHits, int topN) {
        if (topN <= 0) {
            return List.of();
        }

        Map<String, Accumulator> accumulators = new LinkedHashMap<>();
        accumulate(accumulators, vectorHits, RetrievalSource.VECTOR);
        accumulate(accumulators, keywordHits, RetrievalSource.KEYWORD);

        List<FusedHit> fused = accumulators.values().stream()
                .map(accumulator -> new FusedHit(accumulator.chunk, accumulator.score, accumulator.sources))
                .sorted(Comparator.comparingDouble(FusedHit::fusedScore).reversed()
                        .thenComparing(hit -> hit.chunk().sourceFile())
                        .thenComparingInt(hit -> hit.chunk().chunkIndex()))
                .limit(topN)
                .toList();

        log.debug("双路融合完成 | vectorHits={} | keywordHits={} | candidates={} | returned={} | fromBothPaths={}",
                vectorHits.size(), keywordHits.size(), accumulators.size(), fused.size(),
                fused.stream().filter(FusedHit::fromBothPaths).count());
        return fused;
    }

    /** 累加一条路的贡献。dense rank 在这一次遍历里算出来，不需要预先给整列排名 */
    private void accumulate(Map<String, Accumulator> accumulators, List<RetrievalHit> hits, RetrievalSource source) {
        int rank = 0;
        Double previousScore = null;

        for (RetrievalHit hit : hits) {
            if (previousScore == null || Double.compare(hit.score(), previousScore) != 0) {
                // 分数变了才是新的名次；分数相同则沿用上一个名次（dense rank）
                rank++;
                previousScore = hit.score();
            }
            accumulators
                    .computeIfAbsent(keyOf(hit.chunk()), key -> new Accumulator(hit.chunk()))
                    .add(source, 1.0 / (RRF_K + rank));
        }
    }

    /**
     * 切片的身份键。
     *
     * <p>用字符串而不是新建一个 record：这个键只在本类内部的 Map 里用，
     * 定义一个类型会把"仓库层的 ChunkKey"和"融合层的键"绑在一起，反而增加耦合。
     * 组合方式与库上的唯一键 {@code (source_file, chunk_index)} 一致。</p>
     */
    private static String keyOf(KnowledgeChunk chunk) {
        return chunk.sourceFile() + '#' + chunk.chunkIndex();
    }

    /** 一条候选切片的累加器 */
    private static final class Accumulator {

        private final KnowledgeChunk chunk;

        private final EnumSet<RetrievalSource> sources = EnumSet.noneOf(RetrievalSource.class);

        private double score;

        private Accumulator(KnowledgeChunk chunk) {
            this.chunk = chunk;
        }

        /**
         * 累加某条路的贡献。
         *
         * <p>用 {@code sources.add()} 的返回值作为闸门：同一条路对同一条切片的贡献<b>只算一次</b>。
         * 正常情况下一路里不会出现重复切片（SQL 返回的是不同的行），
         * 但写在这里比依赖"上游不会重复"更可靠——真出现重复时宁可少算，也不让某条路被重复加权。</p>
         */
        private void add(RetrievalSource source, double contribution) {
            if (sources.add(source)) {
                score += contribution;
            }
        }

    }

}
