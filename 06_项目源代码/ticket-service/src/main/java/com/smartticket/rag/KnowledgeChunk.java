package com.smartticket.rag;

/**
 * 知识库切片：文档切分后得到的可检索单元（阶段2 第4天）。
 *
 * <p>字段分成两组，用途完全不同：</p>
 * <ul>
 *   <li><b>正文</b>（{@link #content()}）——被向量化、被检索、被拼进 Prompt 的部分；</li>
 *   <li><b>来源元数据</b>（{@link #sourceFile()}、{@link #sourceTitle()}、{@link #sectionTitle()}、
 *       {@link #chunkIndex()}）——<b>不进向量</b>，但必须全程随行。</li>
 * </ul>
 *
 * <p>来源元数据不是"顺手记一下"，而是阶段2 约束「检索结果必须可追溯来源」的落地：
 * RAG 的输出要能回答"这条建议是从哪份文档的哪一节来的"，否则无法人工复核，
 * 也无法在召回错误时定位是切分问题还是检索问题。因此这些字段在写入向量库时
 * 必须作为列一起存下去，而不是只存在内存里。</p>
 *
 * <p>用 record：切片一旦生成就不应被修改——它的 {@code chunkIndex} 与内容是一体的，
 * 改了内容而没改序号会让"来源"指向错位。</p>
 *
 * @param sourceFile   语料文件相对于知识库根目录的路径，如 {@code faq/01-账户问题.md}
 * @param sourceTitle  文档一级标题，如 {@code 账户问题 FAQ}；文档无一级标题时回退为文件名
 * @param sectionTitle 二级标题（小节名），如 {@code 登录时提示「验证码异常」怎么办}
 * @param chunkIndex   该切片在<b>整份文档内</b>的连续序号，从 0 开始（跨小节连续，便于还原顺序）
 * @param content      切片正文，非空
 */
public record KnowledgeChunk(String sourceFile,
                             String sourceTitle,
                             String sectionTitle,
                             int chunkIndex,
                             String content) {

    /**
     * 来源的完整描述，用于日志与检索结果的展示。
     *
     * <p>形如 {@code faq/01-账户问题.md # 账户问题 FAQ > 如何重置登录密码}。</p>
     */
    public String sourceRef() {
        return sourceFile + " # " + sourceTitle + " > " + sectionTitle;
    }

}
