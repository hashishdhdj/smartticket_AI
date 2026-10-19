package com.smartticket.vo;

import com.smartticket.ai.AiClassificationResult;
import com.smartticket.enums.ErrorCode;

import java.util.List;

/**
 * 工单分析结果（阶段2 第3天）。
 *
 * <h3>为什么"降级"也走成功响应</h3>
 * AI 分析是<b>附加建议</b>，不是主流程的必要环节。AI 没配、挂了、或答得不合规时，
 * 接口返回 {@code code = 0} 而把情况写在 {@link #degraded} 里，而不是返回非 0 错误码，理由是：
 * <ul>
 *   <li>调用方（前端"智能识别"按钮）只需要判断一个布尔值，不必为 AI 单独写一套错误分支；</li>
 *   <li>语义更诚实——请求本身成功了，只是没有建议；把它当"失败"会让前端把一次正常降级
 *       渲染成错误提示，用户体验反而更差；</li>
 *   <li>这正是阶段2 验收标准「AI 失败时不会导致工单创建失败」在契约层的体现。</li>
 * </ul>
 *
 * <h3>两个字段的分工</h3>
 * {@link #degradedReasonCode} 给程序判断（10006 未配置 / 10007 调用失败 / 10008 格式不符，
 * 三者排查方向不同），{@link #degradedReason} 只给用户看固定文案。
 * <b>失败细节不放进响应</b>——它可能含模型 host、原始报错等内部信息，只写日志。
 *
 * <h3>为什么用 record</h3>
 * 这是一次分析结果的不可变快照，没有 setter 需求，也不该被改动。
 * 对象组装只通过两个静态工厂方法，避免出现"降级了却带着分类结果"这种自相矛盾的组合。
 *
 * <p><b>关于 {@code confidence} 与人工处理</b>：本接口把模型的把握度原样透出，
 * 不在此处判定"要不要转人工"——阈值属于第5周（章程第5周任务 3「设置置信度阈值」），
 * 在有评估数据之前定阈值等于拍数字。调用方当前可自行按 confidence 决定是否采纳。</p>
 *
 * <p>项目启用 {@code default-property-inclusion=non_null}，降级时各业务字段为 null，
 * 不会出现在响应里。</p>
 *
 * @param degraded           是否降级（true = 本次没有 AI 建议，应按人工处理）
 * @param degradedReasonCode 降级原因码，见 {@link ErrorCode} 的 AI 段；未降级时为 null
 * @param degradedReason     降级原因的固定文案（对外，不含内部细节）；未降级时为 null
 * @param category           分类标签，取自 {@code TicketCategory}；未降级时必有值
 * @param priority           优先级，取自 {@code TicketPriority}；未降级时必有值
 * @param confidence         把握程度 0~1；未降级时必有值
 * @param keywords           关键词；未降级时必有值
 * @param reason             判断理由；未降级时必有值
 * @param promptVersion      Prompt 版本号，用于把结果与当时的 Prompt 对上（阶段2 约束第 10 条）
 * @param model              实际调用的模型名
 * @param costMs             本次 AI 调用耗时（毫秒），成功失败都记
 */
public record TicketAnalysisVO(boolean degraded,
                               Integer degradedReasonCode,
                               String degradedReason,
                               String category,
                               String priority,
                               Double confidence,
                               List<String> keywords,
                               String reason,
                               String promptVersion,
                               String model,
                               long costMs) {

    /** 分析成功：携带完整的分类与优先级建议 */
    public static TicketAnalysisVO analyzed(AiClassificationResult result,
                                            String promptVersion,
                                            String model,
                                            long costMs) {
        return new TicketAnalysisVO(false, null, null,
                result.category(), result.priority(), result.confidence(),
                result.keywords(), result.reason(),
                promptVersion, model, costMs);
    }

    /**
     * 降级：没有建议，只有原因。
     *
     * @param errorCode 降级原因码（AI_NOT_CONFIGURED / AI_CALL_FAILED / AI_RESPONSE_UNPARSABLE）
     * @param model     模型名；未配置通道时无模型可用，传 null 或空白均可，会被归一为 null
     */
    public static TicketAnalysisVO degraded(ErrorCode errorCode,
                                            String model,
                                            String promptVersion,
                                            long costMs) {
        return new TicketAnalysisVO(true, errorCode.getCode(), errorCode.getMessage(),
                null, null, null, null, null,
                promptVersion, blankToNull(model), costMs);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

}
