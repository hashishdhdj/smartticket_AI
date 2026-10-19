package com.smartticket.rag;

/**
 * 知识库检索服务（阶段2 第6天，章程第4周任务 6/7）。
 *
 * <p>两条路各自独立、各自可降级：</p>
 * <ul>
 *   <li>{@link #searchByVector} 依赖嵌入服务——没配密钥就召回不到东西；</li>
 *   <li>{@link #searchByKeyword} 只依赖字符匹配，<b>嵌入服务挂了它照样能用</b>。</li>
 * </ul>
 *
 * <p>这个不对称是刻意的：一条路不可用时另一条仍然给出结果，合并层才有的可合。</p>
 *
 * <p>每次返回前 N 条由配置 {@code smartticket.vector.retrieval.top-k} 决定，
 * 不作为方法参数——调用方（第7天的合并层）不需要、也不应该自己挑这个数。</p>
 */
public interface KnowledgeRetrievalService {

    /**
     * 向量检索：把查询文本向量化，取余弦相似度最高的若干条。
     *
     * <p>会先校验"库里的向量与当前嵌入模型是否同源"：不同模型的向量不可比，
     * 直接比会得到没有意义、且不会报错的分数。</p>
     *
     * @param query 用户查询文本；为空时返回成功且结果为空（不是错误）
     */
    RetrievalResult searchByVector(String query);

    /**
     * 关键词检索：按字符三元组相似度（{@code word_similarity}）取最相近的若干条。
     *
     * <p>对字面敏感、对同义改写不敏感，正好与向量检索互补。</p>
     *
     * @param query 用户查询文本；为空时返回成功且结果为空（不是错误）
     */
    RetrievalResult searchByKeyword(String query);

}
