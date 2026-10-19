package com.smartticket.rag;

/**
 * 面向业务的 RAG 回答（阶段2 第5周任务 3/4/5）。
 *
 * <p>与 {@link KnowledgeSearchService} 的分工：</p>
 * <ul>
 *   <li>{@link KnowledgeSearchService} 解决的是<b>技术问题</b>——两条路怎么查、怎么融合；</li>
 *   <li>本接口解决的是<b>业务问题</b>——查出来的东西<b>值不值得采信</b>，要不要转人工。</li>
 * </ul>
 *
 * <p>分成两层而不是塞进一个类：置信度判定依赖的参数（阈值）会随评估结果调整，
 * 而检索与融合是相对稳定的算法，把两者耦合会让"调阈值"变成"动检索代码"。</p>
 */
public interface RagAnswerService {

    /**
     * 依据知识库回答一个问题。
     *
     * <p>不抛异常：检索降级、知识库无内容都属于正常返回值（与 {@code AiCallResult}
     * 等既有契约一致）。调用方只需看 {@link RagAnswer#needHuman()} 决定下一步。</p>
     */
    RagAnswer answer(String query);

}
