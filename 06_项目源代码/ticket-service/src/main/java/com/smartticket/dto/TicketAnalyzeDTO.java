package com.smartticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单内容分析的入参（阶段2 第3天）。
 *
 * <p>只收文本，<b>不收工单 id</b>：这样在工单还没创建时就能调用（例如用户填完标题描述、
 * 点"智能识别"来自动预填分类与优先级），而如果需要"对已存在工单做分析"，
 * 调用方先查详情再把文本传进来即可，AI 层不必依赖工单表。</p>
 *
 * <p>长度校验与 {@link TicketCreateDTO} 逐字对齐：同一份工单文本，
 * 走建单接口与走分析接口的合法性判断不该有两套标准。</p>
 */
@Data
public class TicketAnalyzeDTO {

    /** 标题，对应列 {@code title VARCHAR(200)}，必填 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题长度不能超过 200")
    private String title;

    /**
     * 描述，可空。
     *
     * <p>上限 65535 与列 {@code description TEXT} 对齐，但这只是"接口收得下"；
     * 真正送给模型前会再截断一次（见 {@code AiPromptFactory}），
     * 因为"能收"与"该全发给模型"是两件事。</p>
     */
    @Size(max = 65535, message = "描述长度超出上限")
    private String description;

}
