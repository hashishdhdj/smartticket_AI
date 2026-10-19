package com.smartticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 指派工单入参。
 *
 * <p>语义（2026-09-18 用户决策）：<b>按当前状态决定是否流转</b></p>
 * <ul>
 *   <li>当前为「待分配」：写入处理人，并经状态机流转到「处理中」；</li>
 *   <li>当前为其它未关闭状态（处理中/待反馈/已解决/已挂起）：视为<b>转派</b>，只换处理人，状态不变；</li>
 *   <li>当前为「已关闭」：拒绝（指派不应隐式重开已关闭工单）。</li>
 * </ul>
 *
 * <p>注意：本类不含状态的<b>目标值</b>，状态如何变化由状态机决定，不接受调用方指定。</p>
 */
@Data
public class TicketAssignDTO {

    /** 处理人ID，对应列 {@code assignee_id BIGINT UNSIGNED NULL} */
    @NotNull(message = "处理人ID不能为空")
    @Positive(message = "处理人ID必须为正整数")
    private Long assigneeId;

    /** 操作人ID：写入 {@code ticket_log.operator_id}（NOT NULL） */
    @NotNull(message = "操作人ID不能为空")
    @Positive(message = "操作人ID必须为正整数")
    private Long operatorId;

    /** 备注，写入 {@code ticket_log.remark VARCHAR(500)} */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

}
