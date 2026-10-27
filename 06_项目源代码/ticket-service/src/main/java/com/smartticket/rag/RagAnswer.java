package com.smartticket.rag;

import java.util.List;

/**
 * 一次 RAG 回答（阶段2 第5周任务 3/4/5：置信度、低置信转人工、来源）。
 *
 * <p>字段对齐 {@code 01_项目说明/项目章程.md} 的「阶段2 RAG 返回结构建议」：</p>
 *
 * <pre>
 * { "matched": true, "confidence": 0.72,
 *   "sourceDocuments": [ { "title": …, "score": …, "summary": … } ],
 *   "suggestion": "…", "needHuman": false }
 * </pre>
 *
 * <h3>matched 与 needHuman 不是一对反义词</h3>
 * <ul>
 *   <li>{@link #matched()}：知识库里**有没有候选内容**（{@code sources} 是否为空）；</li>
 *   <li>{@link #needHuman()}：**置信度是否够**（{@code confidence < 阈值}）。</li>
 * </ul>
 * 于是存在有意义的中间态：{@code matched=true} 且 {@code needHuman=true}——
 * "找到了沾边的内容，但没把握，请人工看一眼"。若把两者做成互补，这个态就没法表达。
 *
 * @param matched         是否在知识库里找到了候选内容
 * @param confidence      置信度，取**向量路最高相似度**（0~1）。理由见 {@link RagSource#score()}
 * @param sources         来源依据，按融合分降序；{@code matched=false} 时为空
 * @param suggestion      建议方案。**仅在"有把握"时生成**（{@code needHuman=false} 且 {@code sources} 非空）；
 *                        其余情况为 {@code null}——包括模型调用失败或返回内容不合规时，
 *                        此时 {@code sources} 仍然可用。
 *                        低置信度时**根本不调用模型**，而不是"生成了不返回"（防幻觉，也省一次调用）
 * @param similarQuestions 相似问题（章程「返回相似问题」的落地）：知识库里还有的、与之相近的问句。
 *                        由模型基于命中的小节生成，与 {@link #suggestion()} 同一次调用产出，
 *                        因此失败时同样是空列表
 * @param needHuman       是否必须转人工。{@code true} 时**不得**给出 AI 自拟的建议（防幻觉）
 * @param degradedSources 本次检索降级了的那几条路。保留它是为了回答
 *                        "置信度低是因为知识库里没有，还是因为一条路挂了"——两者的处置完全不同
 * @param costMs          本次回答的总耗时（毫秒），从进入检索到产出结果；用于观察"用户在提单页等多久"
 */
public record RagAnswer(boolean matched,
                        double confidence,
                        List<RagSource> sources,
                        String suggestion,
                        List<String> similarQuestions,
                        boolean needHuman,
                        List<RetrievalSource> degradedSources,
                        long costMs,
                        String statusReason,
                        com.smartticket.assistance.HandoffDecision decision) {

    public RagAnswer(boolean matched,double confidence,List<RagSource> sources,String suggestion,
            List<String> similarQuestions,boolean needHuman,List<RetrievalSource> degradedSources,long costMs,String statusReason) {
        this(matched,confidence,sources,suggestion,similarQuestions,needHuman,degradedSources,costMs,statusReason,null);
    }

    /** Compatibility constructor for callers that do not yet provide a diagnostic reason. */
    public RagAnswer(boolean matched, double confidence, List<RagSource> sources, String suggestion,
                     List<String> similarQuestions, boolean needHuman, List<RetrievalSource> degradedSources, long costMs) {
        this(matched, confidence, sources, suggestion, similarQuestions, needHuman, degradedSources, costMs,
                needHuman ? "HUMAN_REVIEW_REQUIRED" : "ANSWERED");
    }

    public RagAnswer {
        sources = List.copyOf(sources);
        similarQuestions = List.copyOf(similarQuestions);
        degradedSources = List.copyOf(degradedSources);
    }

}
