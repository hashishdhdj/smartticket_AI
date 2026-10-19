package com.smartticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 解决工单入参。
 *
 * <p>「解决」是一次<b>状态流转</b>，能否解决完全由状态机决定：
 * 按第5天矩阵，只有「处理中」与「待反馈」可以流转到「已解决」，
 * 因此「待分配 → 已解决」这类跳步会被拒绝（错误码 10001）。</p>
 *
 * <p>本类不含目标状态：目标固定为「已解决」，不接受调用方指定。</p>
 *
 * <p>补此接口的背景：在此之前没有任何接口能到达 RESOLVED 状态，而
 * 「处理中 → 已关闭」又被状态机拒绝（要求先经"解决"环节），
 * 导致处理中的工单无法正常关闭（缺陷登记表 DEF-003）。</p>
 */
@Data
public class TicketResolveDTO {

    /** 操作人ID：写入 {@code ticket_log.operator_id}（NOT NULL） */
    @NotNull(message = "操作人ID不能为空")
    @Positive(message = "操作人ID必须为正整数")
    private Long operatorId;

    /** 解决说明，写入 {@code ticket_log.remark VARCHAR(500)} */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

    /** 可选结构化解决方案；启用知识工作流时保存快照并异步生成待审候选。 */
    @Size(min = 10, max = 2000, message = "解决方案须为10到2000个字符")
    private String solution;

}
