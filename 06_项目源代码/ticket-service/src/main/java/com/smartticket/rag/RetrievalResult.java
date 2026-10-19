package com.smartticket.rag;

import com.smartticket.enums.ErrorCode;

import java.util.List;

/**
 * 一路检索的结果（阶段2 第6天）。
 *
 * <p>与 {@code AiCallResult} / {@code EmbeddingResult} / {@code IndexingReport} 同一契约：
 * <b>不抛异常</b>，失败也是一种正常的返回值。RAG 检索挂掉只该让本次检索返回空，
 * 不该把异常抛给工单主流程。</p>
 *
 * <p>{@code success()} 写成方法而不是 record 组件，是为了从结构上杜绝
 * "success=true 但 errorCode 又不为空"这种自相矛盾的组合——只能通过两个静态工厂构造。</p>
 *
 * @param source        本条结果来自哪条路
 * @param hits          命中列表，按 score 降序；失败时为空列表
 * @param errorCode     失败分类；{@code null} 表示成功。故 {@code success()} == {@code errorCode == null}
 * @param failureDetail 失败细节，只写日志、不返回前端
 * @param costMs        本次检索耗时（毫秒）
 */
public record RetrievalResult(RetrievalSource source,
                              List<RetrievalHit> hits,
                              ErrorCode errorCode,
                              String failureDetail,
                              long costMs) {

    public boolean success() {
        return errorCode == null;
    }

    public static RetrievalResult ok(RetrievalSource source, List<RetrievalHit> hits, long costMs) {
        return new RetrievalResult(source, List.copyOf(hits), null, null, costMs);
    }

    public static RetrievalResult degraded(RetrievalSource source, ErrorCode errorCode,
                                           String failureDetail, long costMs) {
        return new RetrievalResult(source, List.of(), errorCode, failureDetail, costMs);
    }

}
