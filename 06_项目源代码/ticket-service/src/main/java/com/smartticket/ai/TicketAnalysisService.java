package com.smartticket.ai;

import com.smartticket.dto.TicketAnalyzeDTO;
import com.smartticket.vo.TicketAnalysisVO;

/**
 * 工单智能分析：一次调用同时给出分类与优先级推荐（阶段2 第3天）。
 *
 * <p>它是 AI 能力对<b>业务</b>的边界，也是整条链路的收口处：</p>
 *
 * <pre>
 * 标题/描述 → 渲染 Prompt（AiPromptFactory）
 *          → 调模型（AiChatService）
 *          → 解析校验（AiClassificationParser）
 *          → 结果或降级（TicketAnalysisVO）
 * </pre>
 *
 * <p><b>契约（与 {@link AiChatService} 一致）</b>：永不抛异常。模型未配置、调用失败、
 * 返回内容不合规，三种情况都以 {@code degraded = true} 的结果返回，由调用方按人工处理路径继续。
 * 把降级做成返回值的形状，是为了让"AI 挂了也不会影响主流程"成为结构上的事实，
 * 而不是要求每个调用点记得写 try/catch。</p>
 *
 * <p><b>只给建议，不写库。</b>本服务不修改工单的分类与优先级——AI 输出的正确率在第5周评估之前
 * 是未知的，让它直接改数据等于把未经验证的结果写进生产数据。是否采纳由人工或调用方决定。</p>
 *
 * <p><b>关于"分类接口"与"优先级推荐接口"合成一个</b>：章程把分类与优先级列为两项能力，
 * 但两者由<b>同一次模型调用</b>产出（v1 Prompt 同时要求 category 与 priority）。
 * 若拆成两个 HTTP 接口，同一工单要分析两次、付两次费，且两端拿到的理由可能不一致。
 * 因此这里合并为一个分析结果，两类能力都在其中。</p>
 */
public interface TicketAnalysisService {

    /**
     * 分析工单内容。
     *
     * @param dto 工单标题与描述
     * @return 分析结果；AI 不可用时返回降级结果而非抛异常
     */
    TicketAnalysisVO analyze(TicketAnalyzeDTO dto);

}
