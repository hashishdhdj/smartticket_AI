package com.smartticket.rag;

/**
 * 知识库检索编排（阶段2 第7天，章程第4周任务 8）。
 *
 * <p>做三件事：调两条路 → 用 RRF 融合 → 把"这次检索是否完整"一并交代清楚。</p>
 *
 * <p>它是 RAG 对外的检索入口：第5周做"低置信度转人工"与"RAG 返回结构"时，
 * 接的就是这一层，而不是分别去调 {@link KnowledgeRetrievalService} 的两个方法——
 * 否则调用方就要自己记得处理"一路降级了怎么办"，那正是容易漏掉的地方。</p>
 */
public interface KnowledgeSearchService {

    /**
     * 检索知识库并融合两条路的结果。
     *
     * @param query 用户查询文本；为空时返回成功且结果为空（不视为错误）
     */
    KnowledgeSearchResult search(String query);

}
