package com.smartticket.enums;

/**
 * 工单优先级枚举。
 *
 * <p>枚举名即数据库中存储的值（第2天表结构：{@code priority VARCHAR(20) DEFAULT 'MEDIUM'}），
 * 两边必须逐字一致。</p>
 *
 * <p>注意：本枚举当前只承载"取值 + 中文描述"。
 * 由于库中存的是字符串，按优先级排序无法用数据库默认顺序表达，
 * 第6天做分页排序时再决定是否补充排序权重（属未决事项，不提前引入）。</p>
 */
public enum TicketPriority {

    /** 低 */
    LOW("低"),

    /** 中：数据库默认值 */
    MEDIUM("中"),

    /** 高 */
    HIGH("高"),

    /** 紧急 */
    URGENT("紧急");

    private final String description;

    TicketPriority(String description) {
        this.description = description;
    }

    /** 中文描述，用于提示信息与接口返回展示 */
    public String getDescription() {
        return description;
    }

}
