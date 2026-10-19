package com.smartticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 关闭工单入参。
 *
 * <p>关闭是一次<b>状态流转</b>，能否关闭完全由状态机决定：
 * 按第5天矩阵，只有「待分配」与「已解决」可以流转到「已关闭」，
 * 因此「处理中 → 已关闭」会被拒绝（错误码 10001）。</p>
 *
 * <p>本类不含目标状态：目标固定为「已关闭」，不接受调用方指定。</p>
 */
@Data
public class TicketCloseDTO {

    /** 操作人ID：写入 {@code ticket_log.operator_id}（NOT NULL） */
    @NotNull(message = "操作人ID不能为空")
    @Positive(message = "操作人ID必须为正整数")
    private Long operatorId;

    /** 关闭原因/备注，写入 {@code ticket_log.remark VARCHAR(500)} */
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

}
