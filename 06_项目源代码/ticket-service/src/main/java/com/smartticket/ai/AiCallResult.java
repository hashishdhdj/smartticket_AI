package com.smartticket.ai;

import com.smartticket.enums.ErrorCode;

/**
 * AI 单次调用的结果。
 *
 * <p>刻意<b>不抛异常</b>，而是把「失败」也当成一种正常返回值：AI 是可选增强能力，
 * 模型挂掉不能把工单主流程带崩。这正是阶段2 验收标准里的「AI 失败时不会导致工单创建失败」
 * ——把它做成返回值的形状，调用方就不需要记得写 try/catch，降级是默认行为而不是额外动作。</p>
 *
 * <p>用 record 而不是 Lombok 类：这是一次调用的快照，天然不可变、不需要 setter，
 * 也不该被序列化成对外的 VO。</p>
 *
 * @param success       是否成功拿到模型输出
 * @param content       模型返回的原始文本；失败时为 {@code null}
 * @param errorCode     失败分类（{@link ErrorCode#AI_NOT_CONFIGURED} 或 {@link ErrorCode#AI_CALL_FAILED}）；成功时 {@code null}
 * @param failureDetail 失败细节，<b>只写日志、不返回前端</b>（可能含模型 host、原始报错等内部信息）
 * @param costMs        本次调用耗时（毫秒）。成功失败都记录，用于观察模型侧是否在变慢
 * @param model         实际请求的模型名，便于日志与后续多模型对比；未配置时为空串
 */
public record AiCallResult(boolean success,
                           String content,
                           ErrorCode errorCode,
                           String failureDetail,
                           long costMs,
                           String model) {

    /** 调用成功 */
    public static AiCallResult ok(String content, long costMs, String model) {
        return new AiCallResult(true, content, null, null, costMs, model);
    }

    /** 调用失败（不可用或异常），由调用方按人工处理路径继续 */
    public static AiCallResult failed(ErrorCode errorCode, String failureDetail, long costMs) {
        return new AiCallResult(false, null, errorCode, failureDetail, costMs, "");
    }

}
