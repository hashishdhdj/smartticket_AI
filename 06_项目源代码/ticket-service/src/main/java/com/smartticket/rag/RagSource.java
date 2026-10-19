package com.smartticket.rag;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * RAG 回答里的一条来源依据（阶段2 第5周任务 5「记录命中来源」）。
 *
 * <p>章程的阶段2 约束第 7 条要求「RAG 结果必须包含来源」，这个类型就是它的落地：
 * 每一条建议都要能回答"这句话是从哪份文档的哪一节来的"，否则无法人工复核，
 * 也无法在答错时定位是切分问题还是检索问题。</p>
 *
 * <p>比章程建议的 {@code {title, score, summary}} 多带了 {@link #sourceFile()}：
 * 只有小节标题不足以定位（不同文档可能有同名小节），而"可追溯"是硬约束，不是可选装饰。</p>
 *
 * @param sourceFile   语料文件路径，如 {@code faq/01-账户问题.md}
 * @param sectionTitle 小节标题，如 {@code 如何重置登录密码}
 * @param score        该切片的<b>向量路相似度</b>；{@code 0} 表示向量路没有召回它。
 *                     <b>刻意不用融合分</b>——融合分是 {@code 1/(60+名次)}，衡量"排第几"而非"有多相关"，
 *                     实测中负样本与正样本完全重叠（见 {@code 低置信案例集.md}）
 * @param via          该切片被哪几条路召回过。有了它，{@code score=0} 才不会让人误以为"质量为零"，
 *                     而是"这条是关键词路给的，向量路没给分"
 * @param summary      正文摘录（折叠空白后截断）。**第 6 项任务（优化 Prompt）会把它换成模型生成的一句话摘要**，
 *                     现在先用原文摘录占位，保证返回结构字段名稳定
 */
public record RagSource(String sourceFile,
                        String sectionTitle,
                        double score,
                        Set<RetrievalSource> via,
                        String summary) {

    public RagSource {
        // 与 FusedHit 同样用 EnumSet 拷贝而不是 Set.copyOf：
        // EnumSet 的迭代顺序是枚举声明顺序（VECTOR、KEYWORD），日志与测试断言才稳定
        via = via.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(via));
    }

    /** 来源的完整描述，用于日志与前端展示：{@code faq/01-账户问题.md > 如何重置登录密码} */
    public String sourceRef() {
        return sourceFile + " > " + sectionTitle;
    }

}
