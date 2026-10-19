package com.smartticket.rag;

import com.smartticket.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 知识库索引编排（阶段2 第5天）：把「切片 → 向量 → 落库」串起来。
 *
 * <h3>为什么按内容哈希做增量，而不是每次全量重算</h3>
 * 全量重算在功能上是对的，但每次启动都要把全部切片重新送进嵌入接口——<b>那是按 token 计费的</b>。
 * 语料是静态资源，绝大多数启动时一个字都没变，重复计算纯属浪费（第5周还要反复跑评估，会更明显）。
 *
 * <p>做法：对每条切片的 {@link #embeddingText(KnowledgeChunk)} 算 {@code SHA-256}，
 * 与库里 {@code (source_file, chunk_index)} 那一行的 {@code content_hash} 比对，
 * 只把"新出现的"和"内容变了的"送去向量化。</p>
 *
 * <h3>哈希的对象必须与送进嵌入接口的文本一致</h3>
 * "发出去的正文"与"用来判断是否需要重建的哈希"**共用同一个方法** {@link #embeddingText(KnowledgeChunk)}，
 * 而不是各写一遍。因为不一致会踩一个**不会报错的坑**：改了嵌入配方后原文一个字没变 →
 * 哈希相同 → 判为"无需更新" → **库里留着一批用旧配方算出来的向量**，而检索质量悄悄退回原样。
 * 共用同一个方法后，改配方会自动触发全量重建，不需要另记什么版本号。
 *
 * <h3>当前嵌入配方：只发正文（标题不进向量）</h3>
 * 第5周任务 7 实测过"标题 + 正文"一起嵌入，**结论是不采纳**：它不但没修好标题类查询的排序，
 * 还把正负样本的分数空档从 0.043 压到 0.0075，等于让置信度阈值失效。
 * 原因见 {@link KnowledgeVectorRepository#searchByKeyword} 附近的说明——
 * 标题是"用户会怎么问"的原话，那不是向量路该干的活（那是关键词路该干的）。
 *
 * <h3>失败一律降级，不抛异常</h3>
 * 与 {@code AiChatService} 同一约定：向量库没起、嵌入密钥没配、嵌入接口挂了，
 * 都只让本次索引失败，不影响工单接口，也不阻止应用启动。
 */
@Slf4j
@Service
public class KnowledgeIndexingService {

    private final KnowledgeCorpusLoader corpusLoader;

    private final EmbeddingService embeddingService;

    private final KnowledgeVectorRepository vectorRepository;

    public KnowledgeIndexingService(KnowledgeCorpusLoader corpusLoader,
                                    EmbeddingService embeddingService,
                                    KnowledgeVectorRepository vectorRepository) {
        this.corpusLoader = corpusLoader;
        this.embeddingService = embeddingService;
        this.vectorRepository = vectorRepository;
    }

    /**
     * 一次索引结果。
     *
     * @param success       本次是否完整跑完（含"没有变化、无需写入"这种正常情况）
     * @param totalChunks   语料切出的切片总数
     * @param skipped       因内容未变化而跳过的条数
     * @param written       实际写入/更新的行数
     * @param errorCode    失败分类；成功时为 {@code null}
     * @param failureDetail 失败细节，只写日志
     * @param costMs        本次索引总耗时（毫秒）
     */
    public record IndexingReport(boolean success,
                                 int totalChunks,
                                 int skipped,
                                 int written,
                                 ErrorCode errorCode,
                                 String failureDetail,
                                 long costMs) {

        static IndexingReport ok(int totalChunks, int skipped, int written, long costMs) {
            return new IndexingReport(true, totalChunks, skipped, written, null, null, costMs);
        }

        static IndexingReport degraded(int totalChunks, int skipped, ErrorCode errorCode,
                                       String failureDetail, long costMs) {
            return new IndexingReport(false, totalChunks, skipped, 0, errorCode, failureDetail, costMs);
        }

    }

    /**
     * 建立/刷新全部知识库切片索引。
     *
     * @return 索引报告；<b>任何失败都以 degraded 报告返回，不抛异常</b>
     */
    public IndexingReport indexAll() {
        long start = System.currentTimeMillis();

        List<KnowledgeChunk> chunks = corpusLoader.loadAll();
        if (chunks.isEmpty()) {
            // 语料为空通常是打包问题（knowledge/ 没进 jar），不是正常状态，但不该让应用起不来
            log.warn("知识库语料为空，本次索引无事可做 | hint=检查 resources/knowledge/ 是否随包发布");
            return IndexingReport.ok(0, 0, 0, elapsedMs(start));
        }

        if (!embeddingService.isAvailable()) {
            String detail = "向量化通道不可用（未配置嵌入模型密钥）";
            log.warn("知识库索引已降级 | 切片={} | reason={}", chunks.size(), detail);
            return IndexingReport.degraded(chunks.size(), 0, ErrorCode.AI_NOT_CONFIGURED, detail, elapsedMs(start));
        }

        try {
            List<KnowledgeChunk> pending = collectPending(chunks);

            if (pending.isEmpty()) {
                // 这是"索引已经是最新"的正常结果，不是失败。日志要能让人一眼看出
                //"不是没跑，而是没必要跑"，否则会被误当成索引没生效
                log.info("知识库索引已是最新，无需重新向量化 | 切片={} | 全部命中已有内容哈希", chunks.size());
                return IndexingReport.ok(chunks.size(), chunks.size(), 0, elapsedMs(start));
            }

            List<String> texts = pending.stream().map(KnowledgeIndexingService::embeddingText).toList();
            EmbeddingResult embedding = embeddingService.embed(texts);
            if (!embedding.success()) {
                log.warn("知识库索引已降级 | 待向量化={} | errorCode={} | detail={}",
                        pending.size(), embedding.errorCode(), embedding.failureDetail());
                return IndexingReport.degraded(chunks.size(), chunks.size() - pending.size(),
                        embedding.errorCode(), embedding.failureDetail(), elapsedMs(start));
            }

            List<KnowledgeVectorRepository.IndexedRow> rows = new ArrayList<>(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                KnowledgeChunk chunk = pending.get(i);
                rows.add(new KnowledgeVectorRepository.IndexedRow(chunk, sha256Hex(embeddingText(chunk)),
                        embedding.embeddings().get(i)));
            }

            int written = vectorRepository.upsert(rows, embedding.model());
            long costMs = elapsedMs(start);

            log.info("知识库索引完成 | 切片={} | 跳过（内容未变）={} | 本次写入={} | totalRows={} | model={} | cost={}ms",
                    chunks.size(), chunks.size() - pending.size(), written, vectorRepository.count(),
                    embedding.model(), costMs);
            return IndexingReport.ok(chunks.size(), chunks.size() - pending.size(), written, costMs);

        } catch (Exception e) {
            // 覆盖 PostgreSQL 不可达、表不存在、类型不符等情况。
            // 捕获宽泛的 Exception 是刻意的：这里是"可选能力的边界"，任何问题都应就地降级
            long costMs = elapsedMs(start);
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("知识库索引已降级，RAG 检索将返回未命中 | 切片={} | cost={}ms | cause={}",
                    chunks.size(), costMs, detail);
            return IndexingReport.degraded(chunks.size(), 0, ErrorCode.VECTOR_STORE_UNAVAILABLE, detail, costMs);
        }
    }

    /**
     * 挑出"库里没有"或"内容变了"或"换过嵌入模型"的切片——这些才需要重新向量化。
     *
     * <p>第三个条件是必要的：换模型后正文可能一个字没变，只比哈希会判为"无需更新"，
     * 结果库里混着两个模型的向量，而不同模型的向量空间不可比。</p>
     *
     * <p>比对的哈希取自 {@link #embeddingText(KnowledgeChunk)}（与实际发送的文本是同一个方法），
     * 这样**改嵌入配方也会触发重建**——理由见类注释里那个"不会报错的坑"。</p>
     */
    private List<KnowledgeChunk> collectPending(List<KnowledgeChunk> chunks) {
        Map<KnowledgeVectorRepository.ChunkKey, KnowledgeVectorRepository.IndexedState> indexed =
                vectorRepository.loadIndexedState();
        String currentModel = embeddingService.modelName();

        List<KnowledgeChunk> pending = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeVectorRepository.ChunkKey key =
                    new KnowledgeVectorRepository.ChunkKey(chunk.sourceFile(), chunk.chunkIndex());
            KnowledgeVectorRepository.IndexedState state = indexed.get(key);
            if (state == null
                    || !sha256Hex(embeddingText(chunk)).equals(state.contentHash())
                    || !currentModel.equals(state.model())) {
                pending.add(chunk);
            }
        }
        return pending;
    }

    /**
     * 送进嵌入接口的文本——**当前配方：只发正文**。
     *
     * <p>这个方法是"嵌入配方"的唯一落点：发送与哈希都走它，因此改这里就会自动触发全量重建。</p>
     *
     * <p>第5周任务 7 试过把小节标题也拼进来（{@code sectionTitle + "\n" + content}），
     * 评估集实测为**负结果**：标题类查询的排序没修好，反而让正负样本分数空档从 0.043 压到 0.0075。
     * 详情见 {@code 20261008_RAG切片与嵌入配方实验.md}。**不要"顺手"再加回去。**</p>
     */
    private static String embeddingText(KnowledgeChunk chunk) {
        return chunk.content();
    }

    /**
     * 嵌入文本的 SHA-256（小写十六进制）。
     *
     * <p>用哈希而不是直接比对全文：哈希是定长 64 字符，可以建索引、可以让"是否变化"的判断
     * 不依赖大字段比较，也让库里多出一列可读的"内容指纹"便于排查。</p>
     *
     * <p>入参来自 {@link #embeddingText(KnowledgeChunk)}——库里的列名仍叫 {@code content_hash}，
     * 但它指纹的是**真正被向量化的那份文本**。</p>
     */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算内容指纹", e);
        }
    }

    private long elapsedMs(long start) {
        return System.currentTimeMillis() - start;
    }

}
