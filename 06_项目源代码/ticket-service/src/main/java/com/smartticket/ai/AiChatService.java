package com.smartticket.ai;

/**
 * AI 对话通道（阶段2 第1天接入）。
 *
 * <p>定位是<b>基础设施</b>，不是业务能力：只负责「把一段 system + user 文本交给模型，拿回文本」。
 * 不做 Prompt 组装、不做 JSON 结构化解析、不碰工单业务——那些属于上层（第2-3天的分类与优先级推荐服务），
 * 也只有分层清楚了，第5天的 Prompt 版本治理才有地方落。</p>
 *
 * <p>三条契约，与阶段2 关键约束逐条对应：</p>
 * <ol>
 *   <li><b>不抛异常</b>：任何失败都通过 {@link AiCallResult} 返回（对应「必须处理模型超时」）；</li>
 *   <li><b>不发无用请求</b>：未配置密钥或总开关关闭时立即返回，不产生无谓的连接等待；</li>
 *   <li><b>可观测</b>：成功与失败都记录耗时与模型名（对应「必须记录模型调用耗时」）。</li>
 * </ol>
 */
public interface AiChatService {

    /**
     * 单轮对话。
     *
     * @param systemPrompt 系统提示词，用于约束模型角色与输出要求
     * @param userPrompt   用户侧输入（工单标题、描述等业务文本）
     * @return 调用结果，永不为 {@code null}
     */
    AiCallResult chat(String systemPrompt, String userPrompt);

    /**
     * AI 通道当前是否可用（已启用 + 已配密钥 + 模型已装配）。
     *
     * <p>给上层用来<b>提前</b>判断走 AI 还是直接走人工：通道不可用时连方法都不用调，
     * 也便于接口里明确告诉调用方「本次是 AI 结果还是人工兜底结果」。</p>
     */
    boolean isAvailable();

}
