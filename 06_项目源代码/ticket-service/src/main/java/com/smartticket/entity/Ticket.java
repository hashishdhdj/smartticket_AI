package com.smartticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单主表实体，对应数据库表 ticket。
 *
 * <p>字段与表一一对应；阶段三依照数据设计决策新增 version 并同步增量迁移。</p>
 */
@Data
@TableName("ticket")
public class Ticket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     * <p>type = AUTO 表示使用 MySQL 的自增主键；MyBatis-Plus 默认策略是雪花算法（ASSIGN_ID），
     * 不显式声明就会绕开 AUTO_INCREMENT，因此这里必须写。</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("version")
    private Long version;

    /** 工单编号，业务唯一（对应唯一键 uk_ticket_no） */
    @TableField("ticket_no")
    private String ticketNo;

    /** 工单标题 */
    @TableField("title")
    private String title;

    /** 工单描述（表类型 TEXT，Java 侧同样是 String） */
    @TableField("description")
    private String description;

    /** 工单分类（当前为自由文本，取值集合待第5天确认） */
    @TableField("category")
    private String category;

    /** 优先级：LOW / MEDIUM / HIGH / URGENT；表中为 VARCHAR，Java 侧先用 String 存，枚举转换在第5天做 */
    @TableField("priority")
    private String priority;

    /** 状态：PENDING_ASSIGN / PROCESSING / PENDING_FEEDBACK / RESOLVED / CLOSED / SUSPENDED，合法性由状态机保证 */
    @TableField("status")
    private String status;

    /** 创建人ID */
    @TableField("creator_id")
    private Long creatorId;

    /** 处理人ID，未指派时为 null */
    @TableField("assignee_id")
    private Long assigneeId;

    /** 标签，逗号分隔 */
    @TableField("tags")
    private String tags;

    /**
     * 创建时间。
     * <p>由数据库的 DEFAULT CURRENT_TIMESTAMP 维护，插入时留 null 即可：
     * MyBatis-Plus 默认只把非 null 字段拼进 INSERT 语句，null 字段会被跳过，从而走数据库默认值。</p>
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间，由数据库的 ON UPDATE CURRENT_TIMESTAMP 维护 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记：0 未删除 / 1 已删除。
     * <p>加 @TableLogic 后：查询会自动追加 deleted = 0，deleteById 会被改写成 UPDATE ... SET deleted = 1。</p>
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

}
