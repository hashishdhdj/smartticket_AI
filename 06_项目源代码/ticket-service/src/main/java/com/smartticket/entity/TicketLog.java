package com.smartticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单操作日志实体，对应数据库表 ticket_log。
 *
 * <p>共 8 个字段。与 ticket 表的关键差异：本表是"只追加"的审计表，
 * 因此<b>没有</b> deleted（不做逻辑删除）和 updated_at（不做更新），不要照抄 Ticket 的注解。</p>
 */
@Data
@TableName("ticket_log")
public class TicketLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，使用 MySQL 自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属工单ID（对应索引 idx_ticket_id） */
    @TableField("ticket_id")
    private Long ticketId;

    /** 操作人ID */
    @TableField("operator_id")
    private Long operatorId;

    /** 操作类型：CREATE / ASSIGN / UPDATE / CLOSE / STATUS_CHANGE / COMMENT */
    @TableField("action")
    private String action;

    /** 变更前状态，非状态变更类操作时为 null */
    @TableField("from_status")
    private String fromStatus;

    /** 变更后状态，非状态变更类操作时为 null */
    @TableField("to_status")
    private String toStatus;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /** 创建时间，由数据库 DEFAULT CURRENT_TIMESTAMP 维护 */
    @TableField("created_at")
    private LocalDateTime createdAt;

}
