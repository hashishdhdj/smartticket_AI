package com.smartticket.rag.impl;

import com.smartticket.enums.ErrorCode;
import com.smartticket.rag.EmbeddingResult;
import com.smartticket.rag.EmbeddingService;
import com.smartticket.rag.KnowledgeRetrievalService;
import com.smartticket.rag.KnowledgeVectorProperties;
import com.smartticket.rag.KnowledgeVectorRepository;
import com.smartticket.rag.RetrievalHit;
import com.smartticket.rag.RetrievalResult;
import com.smartticket.rag.RetrievalSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * {@link KnowledgeRetrievalService} 实现（阶段2 第6天，章程第4周任务 6/7）。
 *
 * <h3>与既有能力一致的三个约定</h3>
 * <ol>
 *   <li><b>不抛异常</b>：任何失败都返回 {@link RetrievalResult#degraded}，RAG 检索挂掉不影响工单主流程；</li>
 *   <li><b>不记查询原文</b>：日志只记字符数。与 {@code TicketAnalysisServiceImpl} 同一取舍——
 *       生产日志不该承担留存用户文本的职责；</li>
 *   <li><b>空查询不是错误</b>：返回成功且结果为空，调用方少一个分支。</li>
 * </ol>
 *
 * <h3>两条路的降级是不对称的（刻意的）</h3>
 * 向量路依赖嵌入服务，关键词路只依赖字符匹配。所以嵌入服务挂掉时，
 * <b>关键词路仍然能用</b>——这正是"双路检索"在可用性上的意义：一条路断了还有另一条。
 */
@Slf4j
@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalService {

    private final EmbeddingService embeddingService;

    private final KnowledgeVectorRepository vectorRepository;

    private final KnowledgeVectorProperties.Retrieval retrievalProperties;

    public KnowledgeRetrievalServiceImpl(EmbeddingService embeddingService,
                                         KnowledgeVectorRepository vectorRepository,
                                         KnowledgeVectorProperties properties) {
        this.embeddingService = embeddingService;
        this.vectorRepository = vectorRepository;
        this.retrievalProperties = properties.getRetrieval();
    }

    // ------------------------------------------------------------ 向量检索

    @Override
    public RetrievalResult searchByVector(String query) {
        long start = System.currentTimeMillis();
        RetrievalSource source = RetrievalSource.VECTOR;

        if (!StringUtils.hasText(query)) {
            return RetrievalResult.ok(source, List.of(), elapsedMs(start));
        }
        if (!embeddingService.isAvailable()) {
            return RetrievalResult.degraded(source, ErrorCode.AI_NOT_CONFIGURED,
                    "向量化通道不可用（未配置嵌入模型密钥），向量检索无法进行", elapsedMs(start));
        }

        try {
            // 校验"查询向量与库中向量同源"。这一步不能省：不同嵌入模型的向量处在不同的向量空间，
            // 直接算余弦距离得到的是没有意义的数字，而且不会报任何错，只会让检索结果不可解释
            Set<String> storedModels = vectorRepository.distinctModels();
            if (storedModels.isEmpty()) {
                return RetrievalResult.degraded(source, ErrorCode.VECTOR_STORE_UNAVAILABLE,
                        "向量库里还没有数据，请先用 --smartticket.vector.index-on-startup=true 建立索引",
                        elapsedMs(start));
            }
            String currentModel = embeddingService.modelName();
            if (storedModels.size() != 1 || !storedModels.contains(currentModel)) {
                return RetrievalResult.degraded(source, ErrorCode.VECTOR_MODEL_MISMATCH,
                        "库中向量来自 " + storedModels + "，当前配置为 " + currentModel
                                + "；不同模型的向量不可比，请重建索引", elapsedMs(start));
            }

            EmbeddingResult embedding = embeddingService.embed(List.of(query));
            if (!embedding.success()) {
                return RetrievalResult.degraded(source, embedding.errorCode(),
                        embedding.failureDetail(), elapsedMs(start));
            }

            List<RetrievalHit> hits = vectorRepository.searchByVector(
                    embedding.embeddings().get(0), retrievalProperties.getTopK());

            long costMs = elapsedMs(start);
            log.info("向量检索完成 | queryChars={} | topK={} | hits={} | topScore={} | model={} | cost={}ms",
                    query.length(), retrievalProperties.getTopK(), hits.size(),
                    topScoreOf(hits), currentModel, costMs);
            return RetrievalResult.ok(source, hits, costMs);

        } catch (Exception e) {
            long costMs = elapsedMs(start);
            log.warn("向量检索失败，已降级为空结果 | queryChars={} | cost={}ms | cause={}: {}",
                    query.length(), costMs, e.getClass().getSimpleName(), e.getMessage());
            return RetrievalResult.degraded(source, ErrorCode.VECTOR_STORE_UNAVAILABLE,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), costMs);
        }
    }

    // ------------------------------------------------------------ 关键词检索

    @Override
    public RetrievalResult searchByKeyword(String query) {
        long start = System.currentTimeMillis();
        RetrievalSource source = RetrievalSource.KEYWORD;

        if (!StringUtils.hasText(query)) {
            return RetrievalResult.ok(source, List.of(), elapsedMs(start));
        }

        try {
            List<RetrievalHit> hits = vectorRepository.searchByKeyword(query,
                    retrievalProperties.getKeywordMinScore(), retrievalProperties.getTopK());

            long costMs = elapsedMs(start);
            log.info("关键词检索完成 | queryChars={} | topK={} | minScore={} | hits={} | topScore={} | cost={}ms",
                    query.length(), retrievalProperties.getTopK(), retrievalProperties.getKeywordMinScore(),
                    hits.size(), topScoreOf(hits), costMs);
            return RetrievalResult.ok(source, hits, costMs);

        } catch (Exception e) {
            // 关键词路不依赖嵌入服务，所以它只在"向量库本身不可用"时才会走到这里
            long costMs = elapsedMs(start);
            log.warn("关键词检索失败，已降级为空结果 | queryChars={} | cost={}ms | cause={}: {}",
                    query.length(), costMs, e.getClass().getSimpleName(), e.getMessage());
            return RetrievalResult.degraded(source, ErrorCode.VECTOR_STORE_UNAVAILABLE,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), costMs);
        }
    }

    // ------------------------------------------------------------ 内部

    private static String topScoreOf(List<RetrievalHit> hits) {
        return hits.isEmpty() ? "-" : String.format("%.4f", hits.get(0).score());
    }

    private long elapsedMs(long start) {
        return System.currentTimeMillis() - start;
    }

}
