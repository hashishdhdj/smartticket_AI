package com.smartticket.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量库的集成测试：真连 PostgreSQL，真写、真读（阶段2 第5、6天，章程第4周任务 5/6/7）。
 *
 * <p>它要证明的是单元测试<b>证明不了</b>的几件事：</p>
 * <ol>
 *   <li>写 vector 列的 JDBC 通道是通的——参数类型是不是真的被当成 {@code vector} 接受。
 *       用字符串拼 SQL 或 mock 掉 JdbcTemplate 都验证不到，必须打真库；</li>
 *   <li>维度确实落到了库里（用 pgvector 的 {@code vector_dims()} 读回来），
 *       只看"写入行数"区分不出"写对了"和"写进去的是错的向量"；</li>
 *   <li>{@code ON CONFLICT ... DO UPDATE} 的行为正确——同一 (文件, 序号) 反复写入不产生重复行，
 *       且后写入的内容会覆盖先前的。这是上层"重复启动不重复计费"赖以成立的地基；</li>
 *   <li>检索 SQL 的算子与函数选对了——{@code <=>} 的语义、{@code word_similarity} 的排序
 *       与门槛过滤，都是写错了也<b>不会报错</b>、只会静默给出错误排序的东西。</li>
 * </ol>
 *
 * <h3>为什么在独立的来源命名空间里造数据</h3>
 * 本类<b>刻意不用真实语料</b>。向量表是开发库里唯一一张"内容由测试产生"的表：
 * 如果测试对真实语料建索引，它写进去的桩向量会<b>覆盖掉真实索引</b>；
 * 而且因为内容哈希没变，下一次真实索引会判定"无需更新"，那个坏数据就再也修不回来了。
 * 所以测试用 {@code __test__/} 前缀造自己的切片，并在每个用例结束后删除
 * ——让 {@code mvn clean test} 在任何时候都可以放心跑。
 *
 * <p>检索类用例的断言都只针对自己插入的 {@code __test__/} 数据在结果中的<b>相对位置</b>，
 * 不假设库里只有这几十条——因为真实语料的 26 条也在同一张表里。</p>
 *
 * <p>"真实语料能否完整落库"由 {@code KnowledgeIndexingServiceTest}（编排逻辑）
 * 与真实环境的索引运行（见决策记录第十节）覆盖，不靠本类。</p>
 *
 * <p><b>依赖 Docker 容器 {@code smartticket-pgvector}（端口 5432）</b>，未启动时本类失败是预期行为：
 * 向量库读写是后续检索功能的前提，不应该被静默跳过。</p>
 */
@SpringBootTest
class KnowledgeVectorIntegrationTest {

    /** 测试专用的来源前缀，与真实语料（faq/、tickets/）完全隔离 */
    private static final String TEST_SOURCE_PREFIX = "__test__/";

    private static final int DIMENSIONS = 1024;

    @Autowired
    private KnowledgeVectorRepository vectorRepository;

    @AfterEach
    void removeTestRows() {
        vectorRepository.deleteBySourcePrefix(TEST_SOURCE_PREFIX);
        assertEquals(0, countTestRows(), "测试数据应被清理干净，否则会污染开发库");
    }

    @Test
    @DisplayName("真实写入：切片连同来源元数据与 1024 维向量一起落库，且能用 pgvector 函数读回维度")
    void writesChunksWithVectorsIntoPostgres() {
        List<KnowledgeVectorRepository.IndexedRow> rows = List.of(
                row("__test__/样本.md", 0, "第一条切片正文", "hash-a"),
                row("__test__/样本.md", 1, "第二条切片正文", "hash-b"),
                row("__test__/另一个.md", 0, "另一份文件的切片", "hash-c"));

        int written = vectorRepository.upsert(rows, "integration-test-model");

        assertEquals(3, written);
        assertEquals(3, countTestRows());

        // 逐条核对维度：这一步同时证明来源元数据可查、以及 vector 列确实按向量存的
        // （不是把字符串塞进了普通文本列）
        for (KnowledgeVectorRepository.IndexedRow row : rows) {
            KnowledgeChunk chunk = row.chunk();
            assertEquals(DIMENSIONS, vectorRepository.vectorDimensionsOf(chunk.sourceFile(), chunk.chunkIndex()),
                    "该切片的向量维度应为 " + DIMENSIONS + "：" + chunk.sourceRef());
        }

        // 模型名与内容哈希必须随行落库：前者是"这批向量出自哪个模型"的指纹，
        // 后者是上层判断"内容有没有变"的依据
        Map<KnowledgeVectorRepository.ChunkKey, KnowledgeVectorRepository.IndexedState> state =
                vectorRepository.loadIndexedState();
        KnowledgeVectorRepository.IndexedState first =
                state.get(new KnowledgeVectorRepository.ChunkKey("__test__/样本.md", 0));
        assertNotNull(first);
        assertEquals("integration-test-model", first.model());
        assertEquals("hash-a", first.contentHash());
    }

    @Test
    @DisplayName("同一切片重复写入不产生重复行，且后写入的内容覆盖先前的内容（ON CONFLICT DO UPDATE）")
    void repeatedUpsertOverwritesInsteadOfDuplicating() {
        vectorRepository.upsert(List.of(
                row("__test__/样本.md", 0, "第一版正文", "hash-v1")), "integration-test-model");
        assertEquals(1, countTestRows());

        // 同一个 (文件, 序号)、不同的内容与哈希
        vectorRepository.upsert(List.of(
                row("__test__/样本.md", 0, "第二版正文", "hash-v2")), "integration-test-model");

        assertEquals(1, countTestRows(), "同一 (文件, 序号) 只应有一行，不能变成两行");

        KnowledgeVectorRepository.IndexedState state = vectorRepository.loadIndexedState()
                .get(new KnowledgeVectorRepository.ChunkKey("__test__/样本.md", 0));
        assertEquals("hash-v2", state.contentHash(), "应覆盖为新内容的哈希，而不是保留旧值");
    }

    // ------------------------------------------------------------ 检索（阶段2 第6天）

    @Test
    @DisplayName("向量检索：用与库中完全相同方向的向量去查，该切片必须排在第一条且相似度为 1")
    void vectorSearchRanksIdenticalVectorFirst() {
        float[] queryVector = uniformVector(1f / 32f);
        vectorRepository.upsert(List.of(
                        new KnowledgeVectorRepository.IndexedRow(
                                chunk("__test__/向量样本.md", 0, "向量检索的样本正文"),
                                "hash-vec", queryVector)),
                "integration-test-model");

        List<RetrievalHit> hits = vectorRepository.searchByVector(queryVector, 3);

        assertFalse(hits.isEmpty(), "库里至少有真实语料，不该查不到东西");
        RetrievalHit top = hits.get(0);
        assertEquals("__test__/向量样本.md", top.chunk().sourceFile(), "方向完全一致的切片必须排第一");
        assertEquals(1.0, top.score(), 1e-6, "余弦相似度应为 1");
        // 分数必须单调不增：否则"取前 K"这件事本身就没有意义了
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).score() >= hits.get(i).score(),
                    "结果应按相似度降序，第 " + i + " 条却比前一条高");
        }
    }

    @Test
    @DisplayName("关键词检索：字面命中的切片排第一，毫无字符重叠的切片被门槛挡在结果之外")
    void keywordSearchMatchesLiteralTokenAndFiltersUnrelated() {
        // 用一个真实语料里绝不可能出现的 ASCII 记号，保证断言与语料内容无关
        vectorRepository.upsert(List.of(
                        new KnowledgeVectorRepository.IndexedRow(
                                chunk("__test__/关键词命中.md", 0, "ZQXONLYMARKER 这是一段只会出现在测试里的正文"),
                                "hash-kw-hit", deterministicVector("kw-hit")),
                        new KnowledgeVectorRepository.IndexedRow(
                                chunk("__test__/关键词无关.md", 0, "毫无字符重叠的另一段正文"),
                                "hash-kw-miss", deterministicVector("kw-miss"))),
                "integration-test-model");

        List<RetrievalHit> hits = vectorRepository.searchByKeyword("ZQXONLYMARKER", 0.0, 5);

        assertFalse(hits.isEmpty());
        RetrievalHit top = hits.get(0);
        assertEquals("__test__/关键词命中.md", top.chunk().sourceFile());
        assertEquals(1.0, top.score(), 1e-6, "查询词在正文中连续出现，三元组相似度应为 1");

        assertTrue(hits.stream().noneMatch(hit -> "__test__/关键词无关.md".equals(hit.chunk().sourceFile())),
                "毫无字符重叠的切片相似度为 0，应被 minScore 门槛挡住，不该占一个名额");
    }

    // ------------------------------------------------------------ 工具

    private int countTestRows() {
        return (int) vectorRepository.loadIndexedState().keySet().stream()
                .filter(key -> key.sourceFile().startsWith(TEST_SOURCE_PREFIX))
                .count();
    }

    private static KnowledgeChunk chunk(String sourceFile, int chunkIndex, String content) {
        return new KnowledgeChunk(sourceFile, "测试文档", "测试小节", chunkIndex, content);
    }

    private static KnowledgeVectorRepository.IndexedRow row(String sourceFile, int chunkIndex,
                                                            String content, String contentHash) {
        return new KnowledgeVectorRepository.IndexedRow(
                chunk(sourceFile, chunkIndex, content), contentHash, deterministicVector(content));
    }

    /** 每个分量都相同的向量；传 1/32 即得到模长为 1 的单位向量（sqrt(1024)/32 = 1） */
    private static float[] uniformVector(float component) {
        float[] vector = new float[DIMENSIONS];
        java.util.Arrays.fill(vector, component);
        return vector;
    }

    /** 由内容决定的确定性向量，保证同一文本必得同一向量 */
    private static float[] deterministicVector(String text) {
        java.util.Random random = new java.util.Random(text.hashCode());
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
        }
        return vector;
    }

}
