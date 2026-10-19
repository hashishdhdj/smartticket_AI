package com.smartticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建工单入参。
 *
 * <p>只包含"前端可传"的字段：不含 id、ticket_no、status、assignee_id、created_at、
 * updated_at、deleted —— 这些由服务端或数据库决定。</p>
 *
 * <p>长度校验与数据库列严格对齐，避免"入参数看着合法、写库时才 500"。</p>
 */
@Data
public class TicketCreateDTO {

    /** 标题，对应列 {@code title VARCHAR(200)}，必填 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题长度不能超过 200")
    private String title;

    /** 描述，对应列 {@code description TEXT}，可空 */
    @Size(max = 65535, message = "描述长度超出上限")
    private String description;

    /** 分类，对应列 {@code category VARCHAR(50)}，可空（第5天决策：暂不定义分类枚举） */
    @Size(max = 50, message = "分类长度不能超过 50")
    private String category;

    /**
     * 优先级，对应列 {@code priority VARCHAR(20)}。
     * <p>可空：为空时由服务端取数据库默认值 {@code MEDIUM}。
     * 取值必须与 {@link com.smartticket.enums.TicketPriority} 一致。</p>
     */
    @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT", message = "优先级取值必须是 LOW/MEDIUM/HIGH/URGENT 之一")
    private String priority;

    /** 标签，逗号分隔，对应列 {@code tags VARCHAR(255)}，可空 */
    @Size(max = 255, message = "标签长度不能超过 255")
    private String tags;

    /**
     * 创建人ID，对应列 {@code creator_id BIGINT NOT NULL}。
     * <p>阶段1 没有登录与用户体系，按 2026-09-18 决策由请求体显式传入。</p>
     */
    @NotNull(message = "创建人ID不能为空")
    @Positive(message = "创建人ID必须为正整数")
    private Long creatorId;

}
