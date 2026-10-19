package com.smartticket.vo;

import com.smartticket.rag.RagAnswer;
import com.smartticket.rag.RagSource;
import com.smartticket.rag.RetrievalSource;

import java.util.List;

/**
 * 提交前知识库检索的结果（阶段2 第5周接线，章程模块三）。
 *
 * <h3>为什么"降级"也走成功响应</h3>
 * 与 {@link TicketAnalysisVO} 同一取舍：检索是<b>附加能力</b>，不是提单流程的必要环节。
 * 知识库没配、向量库挂了、或模型答得不合规时，接口返回 {@code code = 0}，
 * 把情况写在 {@link #needHuman()} 里——调用方只需判断这一个布尔值，
 * 且"没查到知识"与"检索降级"对调用方的处置<b>完全相同</b>：都引导用户继续提单。
 *
 * <p>statusReason区分低分拦截、模型拒答、传输失败与输出校验失败；
 * 只返回稳定原因名称，不暴露模型原始异常或认证信息。</p>
 *
 * <h3>字段分工</h3>
 * <ul>
 *   <li>{@code matched} 与 {@code needHuman} <b>不是反义词</b>：前者看"知识库里有没有候选内容"，
 *       后者看"够不够准"。{@code matched=true} 且 {@code needHuman=true} 表示
 *       "找到了沾边的内容但没把握"，这个中间态是有意义的；</li>
 *   <li>{@code suggestion} 与 {@code similarQuestions} 只在"有把握"时才有值——
 *       低置信度时链路<b>根本不调用模型</b>（防幻觉）；</li>
 *   <li>{@code sources} <b>始终可用</b>：即使不生成建议，人工也能看到"知识库里有哪些相关内容"，
 *       这满足阶段2 约束「检索结果必须可追溯来源」。</li>
 * </ul>
 *
 * <p>项目启用 {@code default-property-inclusion=non_null}，无值的字段不会出现在响应里。</p>
 *
 * @param matched          知识库里是否找到了候选内容
 * @param needHuman        是否必须转人工（置信度不足，或检索降级）。true 时不得直接采纳 AI 内容
 * @param confidence       置信度 = 向量路最高相似度（0~1）；阈值 0.66 的来历见决策记录
 * @param suggestion       推荐方案；低置信度或生成失败时为 null
 * @param similarQuestions 相似问题（章程「返回相似问题」）；低置信度或生成失败时为空
 * @param sources          来源依据，按融合分降序
 * @param degradedSources  本次降级了的那几条检索路（VECTOR / KEYWORD）；都正常时为空
 * @param costMs           本次检索与生成的总耗时（毫秒）
 */
public record KnowledgeAnswerVO(boolean matched,
                                boolean needHuman,
                                Double confidence,
                                String suggestion,
                                List<String> similarQuestions,
                                List<Source> sources,
                                List<String> degradedSources,
                                long costMs,
                                String statusReason) {

    public KnowledgeAnswerVO(boolean matched, boolean needHuman, Double confidence, String suggestion,
                             List<String> similarQuestions, List<Source> sources, List<String> degradedSources, long costMs) {
        this(matched, needHuman, confidence, suggestion, similarQuestions, sources, degradedSources, costMs,
                needHuman ? "HUMAN_REVIEW_REQUIRED" : "ANSWERED");
    }

    /**
     * 一条来源依据。
     *
     * <p>{@code score} 是<b>向量路相似度</b>（不是融合分——融合分衡量"排第几"而非"有多相关"）；
     * 仅由关键词路召回的切片拿不到向量分，此时为 0，
     * 因此 {@code sourceFile} 与 {@code title} 必须同时给出，让人能自己核对。</p>
     */
    public record Source(String sourceFile, String title, Double score, String summary) {

        static Source from(RagSource source) {
            return new Source(source.sourceFile(), source.sectionTitle(), source.score(), source.summary());
        }
    }

    public static KnowledgeAnswerVO of(RagAnswer answer) {
        return new KnowledgeAnswerVO(
                answer.matched(),
                answer.needHuman(),
                answer.confidence(),
                answer.suggestion(),
                answer.similarQuestions(),
                answer.sources().stream().map(Source::from).toList(),
                answer.degradedSources().stream().map(RetrievalSource::name).toList(),
                answer.costMs(), answer.statusReason());
    }

}
