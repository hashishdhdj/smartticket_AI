package com.smartticket.vo;

import com.smartticket.entity.TicketLog;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单操作日志出参。
 *
 * <p>与实体字段一致（日志表本身没有需要隐藏的字段），但依然单独建 VO：
 * Entity 只用于数据库交互，禁止直接作为接口出参（架构约束 5）。</p>
 */
@Getter
@ToString
public class TicketLogVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Long id;

    private final Long ticketId;

    private final Long operatorId;

    /** 操作类型：CREATE / ASSIGN / UPDATE / CLOSE / STATUS_CHANGE / COMMENT */
    private final String action;

    private final String fromStatus;

    private final String toStatus;

    private final String remark;

    private final LocalDateTime createdAt;

    private TicketLogVO(TicketLog ticketLog) {
        this.id = ticketLog.getId();
        this.ticketId = ticketLog.getTicketId();
        this.operatorId = ticketLog.getOperatorId();
        this.action = ticketLog.getAction();
        this.fromStatus = ticketLog.getFromStatus();
        this.toStatus = ticketLog.getToStatus();
        this.remark = ticketLog.getRemark();
        this.createdAt = ticketLog.getCreatedAt();
    }

    /** 实体 → 出参 */
    public static TicketLogVO from(TicketLog ticketLog) {
        return new TicketLogVO(ticketLog);
    }

}
