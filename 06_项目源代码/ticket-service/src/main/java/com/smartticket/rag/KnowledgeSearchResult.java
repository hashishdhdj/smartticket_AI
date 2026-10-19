package com.smartticket.rag;

import java.util.List;

/**
 * 一次完整检索（双路 + 融合）的结果（阶段2 第7天，章程第4周任务 8）。
 *
 * <p>刻意把两条路的原始结果 {@code pathResults} 一并保留：</p>
 * <ul>
 *   <li>它是"这次检索是否完整"的唯一依据——某一路降级了，融合结果会静默变少，
 *       只看 {@code hits} 分不清"库里确实没有"和"那条路挂了"；</li>
 *   <li>第5周做效果评估时要回答"两路各自的贡献是多少"，需要原始结果；
 *       现在不留，那时就得回头改接口。</li>
 * </ul>
 *
 * @param hits        融合后的结果，已按融合分数降序并截断到配置的条数
 * @param pathResults 两条路的原始结果（含成功/降级状态与耗时）
 * @param costMs      本次检索总耗时（毫秒），含两条路与融合
 */
public record KnowledgeSearchResult(List<FusedHit> hits,
                                    List<RetrievalResult> pathResults,
                                    long costMs) {

    /** 本次检索里降级了的那几条路；为空表示两条路都成功 */
    public List<RetrievalSource> degradedSources() {
        return pathResults.stream()
                .filter(result -> !result.success())
                .map(RetrievalResult::source)
                .toList();
    }

    /** 两条路是否都跑成功了 */
    public boolean fullySuccessful() {
        return degradedSources().isEmpty();
    }

}
