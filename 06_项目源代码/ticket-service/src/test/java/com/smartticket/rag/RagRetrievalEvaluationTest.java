package com.smartticket.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索效果评估（阶段2 第5周任务 2「测试召回结果」）。
 *
 * <p>读 {@code 07_测试与质量/04_AI评估集/RAG问答评估集.csv}，对每题真实调一次
 * {@link KnowledgeSearchService#search}，回答三个问题：</p>
 * <ol>
 *   <li><b>Recall@3</b>：该命中的题，正确来源有没有进前三；</li>
 *   <li><b>MRR</b>：命中的题，正确来源平均排在第几；</li>
 *   <li><b>负样本得分</b>：语料里没有答案的题，最高分是多少——这是阈值标定（任务 3）的上界。</li>
 * </ol>
 *
 * <p><b>为什么把每题分数也落盘</b>：任务 3 要"标定"置信度阈值而不是拍脑袋，
 * 依据就是"正样本的得分分布 vs 负样本的得分分布"。只打印一个 Recall 数字，
 * 标定时还得重跑一遍接口（又一次费用）。所以本测试把每题的两路分数一并写到
 * {@code target/rag-eval/rag-eval-raw.csv}。</p>
 *
 * <p><b>默认跳过，必须显式开启</b>：32 题每题都要调一次嵌入接口，会产生费用。</p>
 *
 * <pre>
 * PowerShell:  $env:AI_SMOKE = "true"; mvn test -Dtest=RagRetrievalEvaluationTest
 * 指定别的评估集：mvn test -Dtest=RagRetrievalEvaluationTest -Drag.eval.csv=路径
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AI_SMOKE", matches = "true",
        disabledReason = "默认跳过：本测试会真实调用嵌入接口并产生费用，需显式设置 AI_SMOKE=true 开启")
class RagRetrievalEvaluationTest {

    /** 评估集位置。默认按仓库布局推导；可用 -Drag.eval.csv 覆盖。 */
    private static final Path CSV_PATH = Paths.get(
            System.getProperty("rag.eval.csv", "../../07_测试与质量/04_AI评估集/RAG问答评估集.csv"));

    /** 每题原始数据落盘位置（构建产物，不入库）。 */
    private static final Path RAW_OUT = Paths.get("target/rag-eval/rag-eval-raw.csv");

    /** 判定命中只看融合结果的前几条——与配置 fusion.top-n=3 一致。 */
    private static final int JUDGE_TOP_N = 3;

    @Autowired
    private KnowledgeSearchService searchService;

    @Test
    @DisplayName("用评估集真实跑一遍检索：算 Recall@3 / MRR，并落盘每题分数供阈值标定")
    void evaluateRetrieval() throws IOException {
        List<EvalCase> cases = loadCases(CSV_PATH);
        assertTrue(cases.size() >= 20,
                "评估集至少 20 条（章程阶段2验收标准），实际只有 " + cases.size());

        List<Row> rows = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (EvalCase item : cases) {
            rows.add(judge(item, searchService.search(item.query())));
        }
        long totalMs = System.currentTimeMillis() - start;

        // 关键前置断言：任何一路降级都会让召回率虚低，此时的数据不能用来标定阈值。
        // （第7天的教训：只看融合结果分不清"库里没有"与"那条路挂了"。）
        long degraded = rows.stream().filter(row -> !row.degraded().isEmpty()).count();
        assertEquals(0, degraded,
                "有 " + degraded + " 条检索发生降级，本次评估数据不可用（降级会让召回率虚低）");

        report(rows, totalMs);
        saveRaw(rows);
    }

    // ---------------------------------------------------------------- 判定

    private Row judge(EvalCase item, KnowledgeSearchResult result) {
        List<FusedHit> hits = result.hits();

        int rank = 0;
        for (int i = 0; i < Math.min(hits.size(), JUDGE_TOP_N); i++) {
            KnowledgeChunk chunk = hits.get(i).chunk();
            if (item.matches(chunk.sourceFile(), chunk.sectionTitle())) {
                rank = i + 1;
                break;
            }
        }

        return new Row(
                item,
                rank,
                hits.isEmpty() ? 0 : hits.get(0).fusedScore(),
                top1Score(result, RetrievalSource.VECTOR),
                top1Score(result, RetrievalSource.KEYWORD),
                hits.isEmpty() ? "" : hits.get(0).chunk().sourceRef(),
                result.degradedSources());
    }

    private static double top1Score(KnowledgeSearchResult result, RetrievalSource source) {
        return result.pathResults().stream()
                .filter(path -> path.source() == source && path.success())
                .map(path -> path.hits().isEmpty() ? 0 : path.hits().get(0).score())
                .findFirst()
                .orElse(0.0);
    }

    // ---------------------------------------------------------------- 输出

    private void report(List<Row> rows, long totalMs) {
        List<Row> positives = rows.stream().filter(row -> row.item().expectHit()).toList();
        List<Row> negatives = rows.stream().filter(row -> !row.item().expectHit()).toList();

        System.out.println();
        System.out.println("==================== 逐题结果 ====================");
        System.out.printf("%-5s %-6s %-11s %-5s %-5s %-9s %-9s%n",
                "id", "expect", "style", "命中", "名次", "向量top1", "融合top1");
        for (Row row : rows) {
            System.out.printf("%-5s %-6s %-11s %-5s %-5s %-9.4f %-9.5f%n",
                    row.item().id(),
                    row.item().expectHit() ? "hit" : "miss",
                    row.item().style(),
                    row.item().expectHit() ? (row.rank() > 0 ? "是" : "否") : "-",
                    row.item().expectHit() ? (row.rank() > 0 ? String.valueOf(row.rank()) : "-") : "-",
                    row.vectorTop1(),
                    row.fusedTop1());
            if (row.item().expectHit() && row.rank() != 1) {
                System.out.println("      期望: " + String.join(" | ", row.item().expectedRefs()));
                System.out.println("      实得#1: " + row.top1Source());
            }
        }

        long hitCount = positives.stream().filter(row -> row.rank() > 0).count();
        long top1Count = positives.stream().filter(row -> row.rank() == 1).count();
        double mrr = positives.stream().mapToDouble(row -> row.rank() > 0 ? 1.0 / row.rank() : 0).average().orElse(0);

        System.out.println();
        System.out.println("==================== 汇总 ====================");
        System.out.printf("样本          : 正 %d / 负 %d%n", positives.size(), negatives.size());
        System.out.printf("Recall@3      : %d/%d = %.2f%n", hitCount, positives.size(), ratio(hitCount, positives.size()));
        System.out.printf("Top-1 命中率  : %d/%d = %.2f%n", top1Count, positives.size(), ratio(top1Count, positives.size()));
        System.out.printf("MRR           : %.4f%n", mrr);

        System.out.println();
        System.out.println("---- 负样本得分（阈值必须高于这里的最高分）----");
        for (Row row : negatives) {
            System.out.printf("%-5s 向量top1=%.4f  融合top1=%.5f  | %s%n",
                    row.item().id(), row.vectorTop1(), row.fusedTop1(), row.item().query());
        }
        System.out.printf("负样本最高分  : 向量top1=%.4f | 融合top1=%.5f%n",
                negatives.stream().mapToDouble(Row::vectorTop1).max().orElse(0),
                negatives.stream().mapToDouble(Row::fusedTop1).max().orElse(0));

        System.out.println();
        System.out.printf("总耗时        : %d ms（%d 题）%n", totalMs, rows.size());
        System.out.println("原始数据已写入 : " + RAW_OUT.toAbsolutePath());
        System.out.println("==============================================");
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private void saveRaw(List<Row> rows) throws IOException {
        Files.createDirectories(RAW_OUT.getParent());
        StringBuilder builder = new StringBuilder(
                "id,category,style,expect,expectHit,rank,fusedTop1,vectorTop1,keywordTop1,top1Source,query\n");
        for (Row row : rows) {
            builder.append(row.item().id()).append(',')
                    .append(row.item().category()).append(',')
                    .append(row.item().style()).append(',')
                    .append(row.item().expectHit() ? "hit" : "miss").append(',')
                    .append(row.item().expectHit() ? (row.rank() > 0) : false).append(',')
                    .append(row.rank()).append(',')
                    .append(String.format("%.5f", row.fusedTop1())).append(',')
                    .append(String.format("%.4f", row.vectorTop1())).append(',')
                    .append(String.format("%.4f", row.keywordTop1())).append(',')
                    .append(csv(row.top1Source())).append(',')
                    .append(csv(row.item().query()))
                    .append('\n');
        }
        Files.writeString(RAW_OUT, builder.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        return value.contains(",") ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    // ---------------------------------------------------------------- 读取

    private static List<EvalCase> loadCases(Path path) throws IOException {
        assertTrue(Files.exists(path), "评估集不存在：" + path.toAbsolutePath());
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        List<EvalCase> cases = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = (i == 0 ? stripBom(lines.get(i)) : lines.get(i)).trim();
            if (line.isEmpty() || i == 0) {
                continue;   // 跳过表头与空行
            }
            // 不实现完整 CSV 语法：评估集约定"字段内不出现半角逗号"，
            // 一旦有人写进来，下面这句会直接报错提醒——比静默错位好。
            String[] parts = line.split(",", -1);
            assertEquals(9, parts.length,
                    "第 " + (i + 1) + " 列数不对（应为 9）。评估集约定字段内不出现半角逗号： " + line);

            String expected = parts[5].trim();
            List<String> refs = expected.isEmpty()
                    ? List.of()
                    : Arrays.stream(expected.split("\\|")).map(String::trim).toList();

            cases.add(new EvalCase(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim(),
                    "hit".equals(parts[4].trim()), refs, parts[6].trim()));
        }
        return cases;
    }

    /** 评估集为 UTF-8 with BOM（便于 Excel 打开）；不去掉的话表头首列会变成 \uFEFFid。 */
    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    // ---------------------------------------------------------------- 类型

    /** 一条评估样本 */
    private record EvalCase(String id, String category, String query, String style,
                            boolean expectHit, List<String> expectedRefs, String note) {

        /** 命中判定：来源文件与小节标题都对上才算（与评估集里 `文件#小节` 的写法一致） */
        boolean matches(String sourceFile, String sectionTitle) {
            return expectedRefs.contains(sourceFile + "#" + sectionTitle);
        }
    }

    /** 一条样本的实测结果 */
    private record Row(EvalCase item, int rank, double fusedTop1, double vectorTop1,
                       double keywordTop1, String top1Source, List<RetrievalSource> degraded) {

    }

}
