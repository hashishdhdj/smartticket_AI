package com.smartticket.rag.impl;

import com.smartticket.rag.FusedHit;
import com.smartticket.rag.KnowledgeRetrievalService;
import com.smartticket.rag.KnowledgeQueryNormalizer;
import com.smartticket.rag.KnowledgeSearchResult;
import com.smartticket.rag.KnowledgeSearchService;
import com.smartticket.rag.KnowledgeVectorProperties;
import com.smartticket.rag.RetrievalResult;
import com.smartticket.rag.RetrievalSource;
import com.smartticket.rag.RrfFusion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@link KnowledgeSearchService} 实现（阶段2 第7天，章程第4周任务 8）。
 *
 * <h3>两条路串行执行，不是并行</h3>
 * 向量路约 200~700ms（要真实调用嵌入接口），关键词路约 3ms。并行能把总耗时压到前者，
 * 但需要引入线程池与超时控制——那是"调用隔离"的范畴。本项目把并发隔离留到阶段3
 * （与 AI 调用占用 Tomcat 工作线程是同一件事），现在串行足够，且少一层故障面。
 *
 * <h3>一路降级不等于检索失败</h3>
 * 两条路的可用性是不对称的（关键词路不依赖嵌入服务）。所以只要有一条路给出结果，
 * 融合就照常进行，只是结果会变少——{@link KnowledgeSearchResult#degradedSources()}
 * 负责把这件事实说清楚，避免"结果少"被误读成"库里没有"。
 */
@Slf4j
@Service
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    private final KnowledgeRetrievalService retrievalService;

    private final RrfFusion rrfFusion;

    private final KnowledgeVectorProperties.Fusion fusionProperties;

    public KnowledgeSearchServiceImpl(KnowledgeRetrievalService retrievalService,
                                      RrfFusion rrfFusion,
                                      KnowledgeVectorProperties properties) {
        this.retrievalService = retrievalService;
        this.rrfFusion = rrfFusion;
        this.fusionProperties = properties.getFusion();
    }

    @Override
    public KnowledgeSearchResult search(String query) {
        long start = System.currentTimeMillis();

        String retrievalQuery = KnowledgeQueryNormalizer.normalize(query);
        RetrievalResult vectorResult = retrievalService.searchByVector(retrievalQuery);
        RetrievalResult keywordResult = retrievalService.searchByKeyword(retrievalQuery);

        List<FusedHit> hits = rrfFusion.fuse(
                vectorResult.hits(), keywordResult.hits(), fusionProperties.getTopN());

        long costMs = System.currentTimeMillis() - start;
        List<RetrievalSource> degraded = Stream.of(vectorResult, keywordResult)
                .filter(result -> !result.success())
                .map(RetrievalResult::source)
                .toList();

        if (degraded.isEmpty()) {
            log.info("知识库检索完成 | queryChars={} | vectorHits={} | keywordHits={} | fused={} | fromBoth={} | cost={}ms",
                    query == null ? 0 : query.length(),
                    vectorResult.hits().size(), keywordResult.hits().size(), hits.size(),
                    hits.stream().filter(FusedHit::fromBothPaths).count(), costMs);
        } else {
            // 降级不打 error：这是"可选能力的边界"，工单主流程完全不受影响。
            // 但必须留下"哪条路挂了"的明确信息，否则"结果变少"会被误读成"知识库里没有"
            log.warn("知识库检索部分降级 | queryChars={} | degradedSources={} | vectorHits={} | keywordHits={} "
                            + "| fused={} | cost={}ms",
                    query == null ? 0 : query.length(), degraded,
                    vectorResult.hits().size(), keywordResult.hits().size(), hits.size(), costMs);
        }

        return new KnowledgeSearchResult(hits, List.of(vectorResult, keywordResult), costMs);
    }

}
