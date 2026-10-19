package com.smartticket.rag;

import com.smartticket.enums.ErrorCode;

import java.util.List;

/**
 * 一次向量化调用的结果（阶段2 第5天，章程第4周任务 4）。
 *
 * <p>与 {@code AiCallResult} 同一契约：<b>不抛异常</b>，把失败也做成一种返回值。
 * 理由是同一个——嵌入是可选增强能力，服务不可用时应当让知识库检索返回"未命中"，
 * 而不是把异常抛给工单主流程。</p>
 *
 * @param success       是否成功拿到向量
 * @param embeddings    与入参<b>一一对应、顺序一致</b>的向量列表；失败时为空列表
 * @param errorCode     失败分类：10006 未配置 / 10007 调用失败 / 10008 返回内容不合约定；成功时为 {@code null}
 * @param failureDetail 失败细节，只写日志、不返回前端
 * @param costMs        本次调用总耗时（毫秒），含全部批次
 * @param model         实际请求的模型名
 */
public record EmbeddingResult(boolean success,
                              List<float[]> embeddings,
                              ErrorCode errorCode,
                              String failureDetail,
                              long costMs,
                              String model) {

    public static EmbeddingResult ok(List<float[]> embeddings, long costMs, String model) {
        return new EmbeddingResult(true, List.copyOf(embeddings), null, null, costMs, model);
    }

    public static EmbeddingResult failed(ErrorCode errorCode, String failureDetail, long costMs, String model) {
        return new EmbeddingResult(false, List.of(), errorCode, failureDetail, costMs, model);
    }

}
