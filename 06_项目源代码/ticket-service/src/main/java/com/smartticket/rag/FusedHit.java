package com.smartticket.rag;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 双路融合后的一条结果（阶段2 第7天，章程第4周任务 8）。
 *
 * @param chunk      命中的切片，含来源元数据（满足「检索结果必须可追溯来源」）
 * @param fusedScore RRF 融合分数，<b>越大越靠前</b>。注意它<b>不是</b>相似度，只有相对意义：
 *                   1/(60+1) ≈ 0.0164 这个量级，数值本身不可解释为"相关程度"
 * @param sources    这条被哪几条路召回了。只在一个路里出现说明它只有一个来源支撑；
 *                   两路都在说明两种完全不同的匹配方式（语义 / 字面）都指向了它，可信度更高
 */
public record FusedHit(KnowledgeChunk chunk, double fusedScore, Set<RetrievalSource> sources) {

    public FusedHit {
        // 用 EnumSet 拷贝而不是 Set.copyOf：EnumSet 的迭代顺序是枚举声明顺序（VECTOR、KEYWORD），
        // 结果稳定、日志可读；Set.copyOf 的顺序未定义，会让日志与测试断言随实现变化
        sources = sources.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(sources));
    }

    /** 是否两条路都召回了它——融合后最值得看的一类结果 */
    public boolean fromBothPaths() {
        return sources.size() > 1;
    }

}
