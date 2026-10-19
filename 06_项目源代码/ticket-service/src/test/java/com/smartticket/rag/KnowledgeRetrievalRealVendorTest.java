package com.smartticket.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实厂商下的双路检索对照（阶段2 第6天，章程第4周任务 6/7）。
 *
 * <p>它要回答的问题不是"代码能不能跑"（那由单元测试与集成测试覆盖），而是
 * <b>"两条路各自擅长什么"</b>——这是第7天决定怎么融合、第5周怎么评估的前提。</p>
 *
 * <p><b>默认跳过，必须显式开启</b>：向量路会真实调用嵌入接口并产生费用。
 * 用环境变量而不是系统属性，理由同 {@code AiRealVendorSmokeTest}：
 * 环境变量一定能传到 fork 出来的测试 JVM，{@code -D} 则取决于 surefire 配置。</p>
 *
 * <pre>
 * PowerShell:  $env:AI_SMOKE = "true"; mvn test -Dtest=KnowledgeRetrievalRealVendorTest
 * </pre>
 *
 * <p>本类只做<b>结构性断言</b>（成功、非空、降序、来源正确）与少量"错得离谱才算失败"的
 * 轻量语义检查。真正的检索质量评估属于第5周（要有评估集才能算召回率），
 * 现在把质量断言写死会让测试随模型心情变色。测试会打印完整的召回表，供人工判断。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AI_SMOKE", matches = "true",
        disabledReason = "默认跳过：向量路会真实调用嵌入接口并产生费用，需显式设置 AI_SMOKE=true 开启")
class KnowledgeRetrievalRealVendorTest {

    @Autowired
    private KnowledgeRetrievalService retrievalService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private KnowledgeSearchService searchService;

    @Autowired
    private KnowledgeVectorRepository vectorRepository;

    @Autowired
    private KnowledgeVectorProperties properties;

    @Test
    @DisplayName("向量路：口语化的查询也能召回到主题相关的内容（不要求字面重合）")
    void vectorPathHandlesParaphrasedQuery() {
        assumeTrue(embeddingService.isAvailable(),
                "向量化通道不可用（未配嵌入密钥），跳过 —— 看启动日志里的 reason");

        // 刻意用口语化、与语料字面几乎不重合的说法
        String query = "我的账号登不进去了，怎么办";
        RetrievalResult result = retrievalService.searchByVector(query);
        printResult("向量路", query, result);

        assertTrue(result.success(), () -> "向量检索降级：" + result.errorCode() + " | " + result.failureDetail());
        assertFalse(result.hits().isEmpty(), "真实语料里有账户类内容，向量路不该一条都召回不到");
        assertDescending(result);

        // 轻量语义检查：本地语料里"登不进去"只可能对应账户问题。若第一条落在支付或发票上，
        // 说明向量化或检索算子有问题，本测试失败正是想要的信号
        String topSource = result.hits().get(0).chunk().sourceFile();
        assertTrue(topSource.contains("账户问题"),
                "首条命中的是 " + topSource + "，与「账号登不进去」明显无关，说明检索链路有问题");
    }

    @Test
    @DisplayName("关键词路：字面命中的查询能精确落到对应小节")
    void keywordPathMatchesLiteralTerms() {
        String query = "重复扣款如何申请退款";
        RetrievalResult result = retrievalService.searchByKeyword(query);
        printResult("关键词路", query, result);

        assertTrue(result.success(), () -> "关键词检索降级：" + result.errorCode() + " | " + result.failureDetail());
        assertFalse(result.hits().isEmpty(), "这个词在语料里出现过，不该召回不到");
        assertDescending(result);

        String topSource = result.hits().get(0).chunk().sourceFile();
        assertTrue(topSource.contains("支付问题"),
                "首条命中的是 " + topSource + "，与「重复扣款退款」无关，说明匹配函数选错了");
    }

    @Test
    @DisplayName("双路对照：同一个查询，看两路各自召回了什么")
    void sideBySideComparison() {
        assumeTrue(embeddingService.isAvailable(), "向量化通道不可用，无法做对照，跳过");

        // 两个刻意挑选的查询，分别代表两种"难"：
        //   ① 口语化但字面还有零星重合（"扣""订单"）——看关键词路还能不能撑住；
        //   ② 口语化且字面几乎零重合（"登不进去"这个说法在语料里根本没出现过）——看向量路的价值
        for (String query : List.of("钱被扣了但订单没出来", "我的账号登不进去了，怎么办")) {
            printResult("向量路", query, retrievalService.searchByVector(query));
            printResult("关键词路", query, retrievalService.searchByKeyword(query));
        }
        // 本用例只负责把对照结果打出来供人工判断，不做断言：
        // "哪条路更好"要等第5周有评估集才能回答，现在下结论属于凭感觉
    }

    @Test
    @DisplayName("融合对照 + 关键词门槛标定：同一查询下 向量路 / 关键词路(两个门槛) / 融合后")
    void fusionComparisonAndKeywordThreshold() {
        assumeTrue(embeddingService.isAvailable(), "向量化通道不可用，无法做对照，跳过");

        // 三个查询覆盖三种情形：
        //   ① 口语化且字面零重合  ② 字面精确    ③ 口语化但字面有零星重合
        List<String> queries = List.of(
                "我的账号登不进去了，怎么办",
                "重复扣款如何申请退款",
                "钱被扣了但订单没出来");

        for (String query : queries) {
            printKeywordAtThreshold(query, 0.0);
            printKeywordAtThreshold(query, 0.2);
            printFused(query);
        }
        // 与前面的对照用例一样，不做质量断言：门槛该取多少由这张表定，而"哪条路更好"
        // 要等第5周有评估集才能回答
    }

    // ------------------------------------------------------------ 工具

    private void printKeywordAtThreshold(String query, double threshold) {
        List<RetrievalHit> hits = vectorRepository.searchByKeyword(
                query, threshold, properties.getRetrieval().getTopK());
        System.out.println("---------- [关键词路 阈值=" + threshold + "] query = " + query
                + " | hits=" + hits.size());
        int rank = 1;
        for (RetrievalHit hit : hits) {
            System.out.printf("[关键词路 阈值=%.1f] #%d score=%.4f | %s%n",
                    threshold, rank++, hit.score(), hit.chunk().sourceRef());
        }
    }

    private void printFused(String query) {
        KnowledgeSearchResult result = searchService.search(query);
        System.out.println("---------- [融合] query = " + query
                + " | hits=" + result.hits().size()
                + " | 降级来源=" + result.degradedSources()
                + " | cost=" + result.costMs() + "ms");
        int rank = 1;
        for (FusedHit hit : result.hits()) {
            System.out.printf("[融合] #%d fused=%.6f | fromBoth=%s | %s%n",
                    rank++, hit.fusedScore(), hit.fromBothPaths(), hit.chunk().sourceRef());
        }
    }

    private static void printResult(String label, String query, RetrievalResult result) {
        System.out.println("========== [" + label + "] query = " + query);
        System.out.println("[" + label + "] success=" + result.success()
                + " | source=" + result.source()
                + " | hits=" + result.hits().size()
                + " | cost=" + result.costMs() + "ms");
        if (!result.success()) {
            System.out.println("[" + label + "] 降级原因 = " + result.errorCode() + " | " + result.failureDetail());
            return;
        }
        int rank = 1;
        for (RetrievalHit hit : result.hits()) {
            System.out.printf("[%s] #%d score=%.4f | %s%n", label, rank++, hit.score(), hit.chunk().sourceRef());
        }
    }

    private static void assertDescending(RetrievalResult result) {
        for (int i = 1; i < result.hits().size(); i++) {
            assertTrue(result.hits().get(i - 1).score() >= result.hits().get(i).score(),
                    "结果应按相似度降序，第 " + i + " 条却比前一条高");
        }
    }

}
