package com.smartticket.enums;

import com.smartticket.exception.BusinessException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 工单状态枚举，同时是<b>状态流转规则的唯一裁判</b>。
 *
 * <p>枚举名即数据库中存储的值（第2天表结构：{@code status VARCHAR(20)}），
 * 两边必须逐字一致，否则第6天读写会对不上。</p>
 *
 * <h3>合法流转矩阵（2026-09-17 确认，详见 决策记录 20260917_工单状态机.md）</h3>
 * <pre>
 * 待分配 PENDING_ASSIGN    → 处理中 / 已挂起 / 已关闭
 * 处理中 PROCESSING        → 待反馈 / 已解决 / 已挂起 / 待分配
 * 待反馈 PENDING_FEEDBACK  → 处理中 / 已解决 / 已挂起
 * 已解决 RESOLVED          → 已关闭 / 处理中
 * 已挂起 SUSPENDED         → 处理中
 * 已关闭 CLOSED            → 处理中（重开）
 * </pre>
 *
 * <p>一律拒绝：任何自身流转（X → X）与不在上表中的跳步流转。</p>
 *
 * <p><b>约定</b>：判断状态能否流转只允许走本类的方法，
 * 禁止在 Controller / Service 中另写 if-else 判断，避免规则被复制成多份。</p>
 */
public enum TicketStatus {

    /** 待分配：新建工单的初始状态（与数据库默认值一致） */
    PENDING_ASSIGN("待分配"),

    /** 处理中 */
    PROCESSING("处理中"),

    /** 待反馈 */
    PENDING_FEEDBACK("待反馈"),

    /** 已解决 */
    RESOLVED("已解决"),

    /** 已关闭：允许重开（重开后回到处理中） */
    CLOSED("已关闭"),

    /** 已挂起 */
    SUSPENDED("已挂起");

    private final String description;

    TicketStatus(String description) {
        this.description = description;
    }

    /** 中文描述，用于提示信息与接口返回展示 */
    public String getDescription() {
        return description;
    }

    /**
     * 流转白名单。
     *
     * <p>用静态块构建而非常量构造参数：Java 不允许在枚举常量构造参数里引用其它枚举常量
     * （illegal forward reference），静态块是标准解法。</p>
     */
    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<TicketStatus, Set<TicketStatus>> transitions = new EnumMap<>(TicketStatus.class);
        // 用 Set.of 而不是 EnumSet.of：EnumSet 是可变的，
        // 外层 unmodifiableMap 只保护 Map 结构，挡不住别人拿到集合后 add 篡改规则。
        transitions.put(PENDING_ASSIGN, Set.of(PROCESSING, SUSPENDED, CLOSED));
        transitions.put(PROCESSING, Set.of(PENDING_FEEDBACK, RESOLVED, SUSPENDED, PENDING_ASSIGN));
        transitions.put(PENDING_FEEDBACK, Set.of(PROCESSING, RESOLVED, SUSPENDED));
        transitions.put(RESOLVED, Set.of(CLOSED, PROCESSING));
        transitions.put(SUSPENDED, Set.of(PROCESSING));
        transitions.put(CLOSED, Set.of(PROCESSING));
        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /** 当前状态可合法流转到的状态集合（只读集合） */
    public Set<TicketStatus> allowedNextStatuses() {
        return ALLOWED_TRANSITIONS.get(this);
    }

    /** 判断能否从当前状态流转到目标状态；目标为 null 或自身流转一律返回 false */
    public boolean canTransitionTo(TicketStatus target) {
        return target != null && allowedNextStatuses().contains(target);
    }

    /**
     * 校验流转，非法时抛出业务异常（错误码 10001）。
     *
     * @param target 目标状态
     * @throws BusinessException 流转非法
     */
    public void checkTransitionTo(TicketStatus target) {
        if (!canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "不允许的状态流转：" + describe(this) + " → " + describe(target));
        }
    }

    /**
     * 按数据库中存储的值反查枚举。
     *
     * @param code 库中的状态值，如 {@code PENDING_ASSIGN}
     * @throws BusinessException 值为空或不在已确认的状态集合内（错误码 10002）
     */
    public static TicketStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.UNKNOWN_STATUS_CODE, "工单状态不能为空");
        }
        for (TicketStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw new BusinessException(ErrorCode.UNKNOWN_STATUS_CODE, "未知的工单状态：" + code);
    }

    /** 格式化为「中文名(枚举名)」，便于日志与提示信息定位 */
    private static String describe(TicketStatus status) {
        return status == null ? "null" : status.description + "(" + status.name() + ")";
    }

}
