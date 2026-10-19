package com.smartticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新工单入参（PUT 语义：全量替换）。
 *
 * <p><b>只允许改 title / description / category / priority / tags</b>：
 * 本类<b>不含</b> status 字段，从结构上杜绝"更新时改状态"（状态只能走状态机，见第5天）。</p>
 *
 * <p>全量替换含义：</p>
 * <ul>
 *   <li>{@code title}、{@code priority} 必填；</li>
 *   <li>{@code description}、{@code category}、{@code tags} 传 null 或不传 = 清空该字段。</li>
 * </ul>
 *
 * <p>注意：正因为要支持"清空字段"，服务层不能用 {@code updateById}
 * （MyBatis-Plus 默认会跳过 null 字段），必须用 UpdateWrapper 显式 set。</p>
 */
@Data
public class TicketUpdateDTO {

    /** 从详情读取的客户端版本；提供时拒绝旧页面覆盖，省略兼容旧调用方。 */
    @jakarta.validation.constraints.PositiveOrZero(message = "expectedVersion不能为负数")
    private Long expectedVersion;

    /** 标题，必填 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题长度不能超过 200")
    private String title;

    /** 描述，传 null 表示清空 */
    @Size(max = 65535, message = "描述长度超出上限")
    private String description;

    /** 分类，传 null 表示清空 */
    @Size(max = 50, message = "分类长度不能超过 50")
    private String category;

    /** 优先级，必填；取值必须与 {@link com.smartticket.enums.TicketPriority} 一致 */
    @NotBlank(message = "优先级不能为空")
    @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT", message = "优先级取值必须是 LOW/MEDIUM/HIGH/URGENT 之一")
    private String priority;

    /** 标签，传 null 表示清空 */
    @Size(max = 255, message = "标签长度不能超过 255")
    private String tags;

    /**
     * 操作人ID：<b>不是</b>被修改的业务字段，而是"谁发起的这次操作"，
     * 用于写入 {@code ticket_log.operator_id}（该列 NOT NULL）。
     */
    @NotNull(message = "操作人ID不能为空")
    @Positive(message = "操作人ID必须为正整数")
    private Long operatorId;

}
