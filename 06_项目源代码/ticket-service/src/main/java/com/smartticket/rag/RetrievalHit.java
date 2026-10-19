package com.smartticket.rag;

/**
 * 一条检索命中（阶段2 第6天）。
 *
 * <p>刻意<b>不带</b>"来自哪条路"的字段：那一路是由 {@link RetrievalResult#source()} 表达的。
 * 若这里再存一份，就出现了两个真相，而且可能不一致（仓库填 VECTOR、服务实际跑的是关键词路）。
 * 第7天做双路合并时才会需要"逐条注明来源"，那时再为合并结果专门定义类型。</p>
 *
 * @param chunk 命中的切片，含完整的来源元数据——这是「检索结果必须可追溯来源」的落地
 * @param score 相似度，<b>越大越相关</b>。两条路都统一成这个方向，融合时不必再记"谁越大越好"
 */
public record RetrievalHit(KnowledgeChunk chunk, double score) {

}
