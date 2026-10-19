package com.smartticket.ai;

import java.util.List;

/**
 * AI 分类结果的结构定义（阶段2 第2天）。
 *
 * <p>这是<b>模型输出的契约</b>，不是接口出参。它是阶段2 约束里「AI 输出必须结构化」
 * 「必须定义 JSON Schema」「不允许 AI 自由返回自然语言」三条的落地载体：</p>
 *
 * <ul>
 *   <li>字段固定 5 个，多了少了都视为违规——{@link AiClassificationParser} 用
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} 让"模型多回一个字段"直接失败，
 *       而不是静默忽略。多出字段说明模型没按 Schema 走，此时它的其他字段也不可信；</li>
 *   <li>每个字段的取值边界与 {@code resources/prompts/ai-classification-result.schema.json}
 *       一一对应。两份真值的一致性由 {@code AiClassificationSchemaTest} 断言守护，
 *       不靠人记得同步。</li>
 * </ul>
 *
 * <p>用 record 而非 Lombok 类：这是模型单次输出的快照，天然不可变，不该被改。</p>
 *
 * <p><b>字段均为包装类型而非基本类型</b>：JSON 里字段可能缺失，用 {@code Double} 才能区分
 * "缺字段（null）"与"值为 0"——若用 {@code double}，缺失会被静默当成 0，
 * 而 0 在这里是合法值（把握度为零），二者语义完全不同。</p>
 *
 * @param category   分类标签，必须逐字取自 {@link com.smartticket.enums.TicketCategory}
 * @param priority   优先级，必须逐字取自 {@link com.smartticket.enums.TicketPriority}（LOW/MEDIUM/HIGH/URGENT）
 * @param confidence 把握程度，0 到 1 之间的小数（含端点）
 * @param keywords   关键词，1 到 8 个，每个不超过 30 字
 * @param reason     判断理由，不超过 200 字
 */
public record AiClassificationResult(String category,
                                     String priority,
                                     Double confidence,
                                     List<String> keywords,
                                     String reason) {
}
