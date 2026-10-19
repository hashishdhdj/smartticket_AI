package com.smartticket.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartticket.entity.Ticket;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单出参。
 *
 * <p>只包含"前端可看"的字段：<b>不含</b> {@code deleted}（逻辑删除标记属内部状态，不应外泄）。</p>
 *
 * <p>状态与优先级返回的是数据库存储的英文码（如 {@code PENDING_ASSIGN}），
 * 由前端或后续版本决定是否映射中文。</p>
 */
@Getter
@ToString
public class TicketVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Long id;

    private final Long version;

    private final String ticketNo;

    private final String title;

    private final String description;

    private final String category;

    private final String priority;

    private final String status;

    private final Long creatorId;

    private final Long assigneeId;

    private final String tags;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    /**
     * Jackson 反序列化用构造器（第9天引入详情缓存时需要）。
     *
     * <p>本类字段全为 final 且无 setter，Jackson 无法用常规方式还原对象，
     * 必须显式标注 {@code @JsonCreator} 并逐参数绑定字段名。
     * 把它与下面「实体 → 出参」的构造器分开，是为了让"从数据库转换"与
     * "从缓存还原"两条路径各自独立，互不干扰。</p>
     */
    @JsonCreator
    private TicketVO(@JsonProperty("id") Long id,
                     @JsonProperty("version") Long version,
                     @JsonProperty("ticketNo") String ticketNo,
                     @JsonProperty("title") String title,
                     @JsonProperty("description") String description,
                     @JsonProperty("category") String category,
                     @JsonProperty("priority") String priority,
                     @JsonProperty("status") String status,
                     @JsonProperty("creatorId") Long creatorId,
                     @JsonProperty("assigneeId") Long assigneeId,
                     @JsonProperty("tags") String tags,
                     @JsonProperty("createdAt") LocalDateTime createdAt,
                     @JsonProperty("updatedAt") LocalDateTime updatedAt) {
        this.id = id;
        this.version = version;
        this.ticketNo = ticketNo;
        this.title = title;
        this.description = description;
        this.category = category;
        this.priority = priority;
        this.status = status;
        this.creatorId = creatorId;
        this.assigneeId = assigneeId;
        this.tags = tags;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    private TicketVO(Ticket ticket) {
        this.id = ticket.getId();
        this.version = ticket.getVersion();
        this.ticketNo = ticket.getTicketNo();
        this.title = ticket.getTitle();
        this.description = ticket.getDescription();
        this.category = ticket.getCategory();
        this.priority = ticket.getPriority();
        this.status = ticket.getStatus();
        this.creatorId = ticket.getCreatorId();
        this.assigneeId = ticket.getAssigneeId();
        this.tags = ticket.getTags();
        this.createdAt = ticket.getCreatedAt();
        this.updatedAt = ticket.getUpdatedAt();
    }

    /** 实体 → 出参，映射逻辑集中在此处，避免散落在 Service 各方法里 */
    public static TicketVO from(Ticket ticket) {
        return new TicketVO(ticket);
    }

}
