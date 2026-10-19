package com.smartticket.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工单分类的受控取值（阶段2 第2天新增）。
 *
 * <p><b>边界说明（重要）</b>：本枚举约束的是 <b>AI 分类接口的输出</b>，不是工单表字段的约束。
 * 库里 {@code category VARCHAR(50)} 仍是自由文本（2026-09-18 决策：分类诉求未明确前不固化取值），
 * 人工建单可以填任意分类；引入本枚举是为了让阶段2 约束里的「不允许 AI 自由返回」有可校验的白名单——
 * 否则"结构化输出"只是一句口号，模型回什么都会被照单全收写进库。</p>
 *
 * <p><b>为什么用中文标签而不是英文码</b>：</p>
 * <ol>
 *   <li>与项目章程给出的 AI 输出示例一致（示例即 {@code "category": "账户问题"}）；</li>
 *   <li>与库中人工填写的分类风格一致，坐席与运维肉眼可核，不需要额外映射阅读；</li>
 *   <li>2026-09-25 的真实调用实测中，模型对中文标签遵循度良好——实测返回「功能异常」，
 *       正落在本清单内，且未附带任何多余文字。</li>
 * </ol>
 *
 * <p><b>何时应改为英文码</b>：若后续评估集（第5周）发现模型频繁返回同义变体
 * （如把「账户问题」写成「账号问题」）导致降级率偏高，则改为英文码 + 标签映射表。
 * 判断依据是评估数据，不是感觉。</p>
 */
public enum TicketCategory {

    /** 账户问题：登录、注册、密码、权限、验证码 */
    ACCOUNT("账户问题"),

    /** 支付问题：扣款、退款、账单、发票、订单 */
    PAYMENT("支付问题"),

    /** 功能异常：页面报错、功能不可用、数据展示错误 */
    FUNCTION("功能异常"),

    /** 其他：无法归入以上三类，或信息不足以判断 */
    OTHER("其他");

    private final String label;

    TicketCategory(String label) {
        this.label = label;
    }

    /** 中文标签，也是要求模型逐字返回的值 */
    public String getLabel() {
        return label;
    }

    /**
     * 全部标签。
     *
     * <p>供两处共用，这是本枚举存在的主要价值：Prompt 渲染时列出允许取值、
     * 解析结果时校验取值。两处引用同一份清单，不会出现"Prompt 里改了、校验里没改"。</p>
     */
    public static List<String> allLabels() {
        return Arrays.stream(values()).map(TicketCategory::getLabel).collect(Collectors.toList());
    }

    /**
     * 按标签匹配。
     *
     * @return 匹配到的分类；标签为空或不在清单内时返回 {@link Optional#empty()}，
     *         <b>不抛异常</b>——"模型返回了清单外的分类"是可预期的降级场景而非程序错误，
     *         由调用方决定是记日志降级还是走人工
     */
    public static Optional<TicketCategory> fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        String trimmed = label.trim();
        return Arrays.stream(values())
                .filter(category -> category.label.equals(trimmed))
                .findFirst();
    }

}
