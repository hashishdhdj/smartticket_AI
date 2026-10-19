package com.smartticket.rag.impl;

import com.smartticket.enums.ErrorCode;
import com.smartticket.rag.FusedHit;
import com.smartticket.rag.KnowledgeChunk;
import com.smartticket.rag.KnowledgeRetrievalService;
import com.smartticket.rag.KnowledgeSearchResult;
import com.smartticket.rag.KnowledgeVectorProperties;
import com.smartticket.rag.RetrievalHit;
import com.smartticket.rag.RetrievalResult;
import com.smartticket.rag.RetrievalSource;
import com.smartticket.rag.RrfFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索编排的单元测试（阶段2 第7天，章程第4周任务 8）。
 *
 * <p>融合算法本身由 {@code RrfFusionTest} 覆盖，这里只测编排要负责的三件事：</p>
 * <ol>
 *   <li><b>一路降级不等于检索失败</b>——另一条路有结果就照常融合；</li>
 *   <li><b>降级事实必须被带出来</b>——否则"结果变少"会被误读成"知识库里没有"；</li>
 *   <li><b>配置的条数生效</b>。</li>
 * </ol>
 */
class KnowledgeSearchServiceImplTest {

    private KnowledgeRetrievalService retrievalService;

    private KnowledgeVectorProperties properties;

    private KnowledgeSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        retrievalService = mock(KnowledgeRetrievalService.class);
        properties = new KnowledgeVectorProperties();
        properties.getFusion().setTopN(3);
        service = new KnowledgeSearchServiceImpl(retrievalService, new RrfFusion(), properties);
    }

    @Test
    @DisplayName("两路都成功：两路都召回的切片排第一，并如实报告来源与两条路的原始结果")
    void mergesBothPathsWhenBothSucceed() {
        RetrievalHit onlyVector = hit("faq/02-支付问题.md", 0, 0.90);
        RetrievalHit both = hit("faq/01-账户问题.md", 0, 0.70);

        when(retrievalService.searchByVector(anyString()))
                .thenReturn(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(onlyVector, both), 120));
        when(retrievalService.searchByKeyword(anyString()))
                .thenReturn(RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(both), 3));

        KnowledgeSearchResult result = service.search("如何重置密码");

        assertEquals(2, result.hits().size());
        FusedHit top = result.hits().get(0);
        assertEquals(0, top.chunk().chunkIndex(), "两路都召回的那条应排第一");
        assertEquals(Set.of(RetrievalSource.VECTOR, RetrievalSource.KEYWORD), top.sources());
        assertTrue(top.fromBothPaths());

        assertTrue(result.fullySuccessful());
        assertTrue(result.degradedSources().isEmpty());

        // 原始结果必须保留：第5周要回答"两路各自的贡献是多少"
        assertEquals(2, result.pathResults().size());
        assertEquals(RetrievalSource.VECTOR, result.pathResults().get(0).source());
        assertEquals(RetrievalSource.KEYWORD, result.pathResults().get(1).source());
    }

    @Test
    void passwordSynonymIsUsedForBothRetrievalPathsWithoutReplacingStateCodes() {
        when(retrievalService.searchByVector(anyString())).thenReturn(RetrievalResult.ok(RetrievalSource.VECTOR,List.of(),1));
        when(retrievalService.searchByKeyword(anyString())).thenReturn(RetrievalResult.ok(RetrievalSource.KEYWORD,List.of(),1));
        service.search("忘记口令，DEGRADED和RULE是什么意思");
        org.mockito.Mockito.verify(retrievalService).searchByVector("忘记密码，DEGRADED和RULE是什么意思");
        org.mockito.Mockito.verify(retrievalService).searchByKeyword("忘记密码，DEGRADED和RULE是什么意思");
    }

    @Test
    @DisplayName("向量路降级时只用关键词路融合，并把降级的那一路如实报出来")
    void keepsWorkingWhenVectorPathDegrades() {
        when(retrievalService.searchByVector(anyString()))
                .thenReturn(RetrievalResult.degraded(RetrievalSource.VECTOR,
                        ErrorCode.AI_NOT_CONFIGURED, "未配置嵌入密钥", 0));
        when(retrievalService.searchByKeyword(anyString()))
                .thenReturn(RetrievalResult.ok(RetrievalSource.KEYWORD,
                        List.of(hit("faq/01-账户问题.md", 0, 0.36)), 4));

        KnowledgeSearchResult result = service.search("重复扣款");

        assertFalse(result.hits().isEmpty(), "关键词路有结果，检索不该整体变空");
        assertEquals(Set.of(RetrievalSource.KEYWORD), result.hits().get(0).sources());
        assertEquals(List.of(RetrievalSource.VECTOR), result.degradedSources());
        assertFalse(result.fullySuccessful());
    }

    @Test
    @DisplayName("两路都降级时返回空结果，并报出两条路都降级了")
    void returnsEmptyWhenBothPathsDegrade() {
        for (RetrievalSource source : List.of(RetrievalSource.VECTOR, RetrievalSource.KEYWORD)) {
            RetrievalResult degraded = RetrievalResult.degraded(source,
                    ErrorCode.VECTOR_STORE_UNAVAILABLE, "模拟 PostgreSQL 未启动", 0);
            if (source == RetrievalSource.VECTOR) {
                when(retrievalService.searchByVector(anyString())).thenReturn(degraded);
            } else {
                when(retrievalService.searchByKeyword(anyString())).thenReturn(degraded);
            }
        }

        KnowledgeSearchResult result = service.search("任意查询");

        assertTrue(result.hits().isEmpty());
        assertEquals(2, result.degradedSources().size());
        assertFalse(result.fullySuccessful());
    }

    @Test
    @DisplayName("按配置的 fusion.top-n 截断融合结果")
    void respectsConfiguredTopN() {
        properties.getFusion().setTopN(1);
        service = new KnowledgeSearchServiceImpl(retrievalService, new RrfFusion(), properties);
        when(retrievalService.searchByVector(anyString())).thenReturn(RetrievalResult.ok(
                RetrievalSource.VECTOR,
                List.of(hit("faq/01-账户问题.md", 0, 0.9), hit("faq/01-账户问题.md", 1, 0.8)), 10));
        when(retrievalService.searchByKeyword(anyString()))
                .thenReturn(RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(), 1));

        assertEquals(1, service.search("任意查询").hits().size());
    }

    @Test
    @DisplayName("空查询不是错误：两路各返回空，检索结果为空但不算降级")
    void blankQueryIsNotAnError() {
        when(retrievalService.searchByVector(anyString()))
                .thenReturn(RetrievalResult.ok(RetrievalSource.VECTOR, List.of(), 0));
        when(retrievalService.searchByKeyword(anyString()))
                .thenReturn(RetrievalResult.ok(RetrievalSource.KEYWORD, List.of(), 0));

        KnowledgeSearchResult result = service.search("   ");

        assertTrue(result.hits().isEmpty());
        assertTrue(result.fullySuccessful(), "空查询不是降级，不该被记成两条路都挂了");
    }

    // ------------------------------------------------------------ 工具

    private static RetrievalHit hit(String sourceFile, int chunkIndex, double score) {
        KnowledgeChunk chunk = new KnowledgeChunk(sourceFile, "测试文档", "测试小节", chunkIndex, "正文");
        return new RetrievalHit(chunk, score);
    }

}
