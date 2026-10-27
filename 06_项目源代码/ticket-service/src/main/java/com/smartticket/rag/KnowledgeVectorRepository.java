package com.smartticket.rag;

import jakarta.annotation.PreDestroy;
import com.smartticket.knowledge.KnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库向量表的读写（阶段2 第5天，章程第4周任务 5）。
 *
 * <h3>为什么手写 SQL，而不是用 Spring AI 的 PgVectorStore</h3>
 * 官方 {@code spring-ai-starter-vector-store-pgvector} 确实能用，代码量也更少。这里没用它，
 * 是三个具体原因，而不是"不知道有这个东西"：
 * <ol>
 *   <li><b>章程第6~7 周的任务是"实现向量检索"与"实现关键词检索"</b>。用 {@code similaritySearch()}
 *       是"调用"，写 {@code ORDER BY embedding <=> ? LIMIT k} 是"实现"。关键词检索更是任何
 *       现成 VectorStore 都不提供，必然要自己写 SQL——那不如两边用同一套访问方式。</li>
 *   <li><b>幂等控制</b>：这里要按 {@code content_hash} 跳过未变化的切片，避免每次启动都重复调用
 *       按量计费的嵌入接口。官方 store 的 upsert 语义不提供"内容没变就不重算"这一层。</li>
 *   <li><b>不引入新依赖</b>：官方 starter 会连带引入它自己的建模与配置，而本项目只需要一张表和几条 SQL。</li>
 * </ol>
 *
 * <h3>为什么连接池由本类自己持有，而不是注册成 Spring 的 DataSource bean</h3>
 * Spring Boot 对数据源的自动配置带 {@code @ConditionalOnMissingBean(DataSource.class)}：
 * 容器里一旦出现<b>任何</b> DataSource bean（哪怕是给 PostgreSQL 用的第二个），
 * MySQL 那一个的自动配置就会退让。要恢复它就必须手工声明 MySQL 数据源并加 {@code @Primary}，
 * 而本类不想因为"加一个向量库"去改动阶段1 已经稳定（且带防雪崩参数）的主数据源配置
 * （见缺陷登记表 DEF-002 与 {@code connection-timeout: 5000} 的决策）。
 *
 * <p>所以这里的连接池是<b>普通字段</b>，不向容器暴露任何 {@code DataSource} / {@code JdbcTemplate} 类型的 bean，
 * 主数据源的装配完全不受影响。</p>
 */
@Slf4j
@Repository
public class KnowledgeVectorRepository {

    private final HikariDataSource dataSource;

    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.transaction.support.TransactionTemplate publicationTx;
    private final com.smartticket.experience.graph.ExperienceGraphStore graphs;
    private static final String SEARCHABLE = "(source_file NOT LIKE 'resolved-ticket/%' OR EXISTS ("
            + "SELECT 1 FROM knowledge_publication p WHERE p.source_file=knowledge_chunk.source_file AND p.state='ACTIVE'))";

    public KnowledgeVectorRepository(KnowledgeVectorProperties properties) {
        this(properties,new com.smartticket.experience.graph.ExperienceGraphStore(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeVectorRepository(KnowledgeVectorProperties properties,com.smartticket.experience.graph.ExperienceGraphStore graphs) {
        this.graphs=graphs;
        KnowledgeVectorProperties.Datasource config = properties.getDatasource();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("vector-pg-pool");
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setMaximumPoolSize(config.getMaximumPoolSize());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeoutMs());
        // 关键：-1 = 启动时不做连接校验。
        // 默认值（1ms）会让容器在**应用启动阶段**就去连一次 PostgreSQL，连不上直接启动失败——
        // 那样"没起 PostgreSQL 就跑不起来服务"，违背本项目「RAG 是可选增强」的一贯约定。
        // 设为 -1 后，连接失败被推迟到真正读写向量库的那一刻，再由上层降级。
        hikariConfig.setInitializationFailTimeout(-1);

        this.dataSource = new HikariDataSource(hikariConfig);
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
        this.publicationTx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(this.dataSource));

        log.info("向量库连接池已初始化 | pool={} | url={} | maxPoolSize={}",
                hikariConfig.getPoolName(), config.getUrl(), config.getMaximumPoolSize());
    }

    /** 切片的自然身份，与表上的唯一键 {@code (source_file, chunk_index)} 一一对应 */
    public record ChunkKey(String sourceFile, int chunkIndex) {
    }

    /**
     * 库里某一行的"已索引状态"。
     *
     * @param contentHash 写入时正文的 SHA-256
     * @param model       生成该行向量的模型名
     */
    public record IndexedState(String contentHash, String model) {
    }

    /** 一行待写入的记录：切片 + 其正文哈希 + 其向量 */
    public record IndexedRow(KnowledgeChunk chunk, String contentHash, float[] embedding) {
    }

    /**
     * 读出库里每一行已索引的状态（正文哈希 + 向量来自哪个模型）。
     *
     * <p>模型名必须一起读出来，否则会漏掉一种很隐蔽的错误：
     * 换了嵌入模型之后，正文一个字没变 → 哈希仍然相同 → 判为"无需更新"，
     * 于是库里同时躺着两个模型产出的向量。不同模型的向量空间不可比，
     * 混在一起检索的结果无法解释，而且不会报任何错。把模型名纳入比对，这种混装就不可能发生。</p>
     *
     * @return key 为 (文件, 序号)
     */
    public Map<ChunkKey, IndexedState> loadIndexedState() {
        Map<ChunkKey, IndexedState> states = new HashMap<>();
        // 显式转型为 RowCallbackHandler：表达式 lambda 同时满足 RowCallbackHandler（void）
        // 与 ResultSetExtractor（有返回值）两个重载，不点明会编译不过
        jdbcTemplate.query(
                "SELECT source_file, chunk_index, content_hash, model FROM knowledge_chunk",
                (RowCallbackHandler) rs -> states.put(
                        new ChunkKey(rs.getString("source_file"), rs.getInt("chunk_index")),
                        new IndexedState(rs.getString("content_hash"), rs.getString("model"))));
        return states;
    }

    /**
     * 批量写入或就地更新切片。
     *
     * <p>用 {@code ON CONFLICT (source_file, chunk_index) DO UPDATE}：同一份语料的第 i 块
     * 天然只有一行，语料改了内容但序号没变时就地覆盖，不会产生重复行。</p>
     *
     * @param model 生成这些向量的模型名，写进 {@code model} 列作为"这批向量出自哪个模型"的指纹
     * @return 实际提交的行数
     */
    public int upsert(List<IndexedRow> rows, String model) {
        if (rows.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO knowledge_chunk
                    (source_file, source_title, section_title, chunk_index, content, content_hash, embedding, model)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_file, chunk_index) DO UPDATE SET
                    source_title  = EXCLUDED.source_title,
                    section_title = EXCLUDED.section_title,
                    content       = EXCLUDED.content,
                    content_hash  = EXCLUDED.content_hash,
                    embedding     = EXCLUDED.embedding,
                    model         = EXCLUDED.model,
                    updated_at    = now()
                """;

        int[] affected = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                IndexedRow row = rows.get(i);
                KnowledgeChunk chunk = row.chunk();
                ps.setString(1, chunk.sourceFile());
                ps.setString(2, chunk.sourceTitle());
                ps.setString(3, chunk.sectionTitle());
                ps.setInt(4, chunk.chunkIndex());
                ps.setString(5, chunk.content());
                ps.setString(6, row.contentHash());
                // 用 PGobject 显式声明类型为 vector，而不是 "?::vector" 字符串拼接：
                // 后者依赖服务端把参数当 unknown 处理，参数一旦被驱动定性为 varchar 就会报
                // "cannot cast type character varying to vector"。把类型说明白，不依赖隐式转换。
                ps.setObject(7, toPgVector(row.embedding()));
                ps.setString(8, model);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });

        int total = 0;
        for (int count : affected) {
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    /**
     * 按来源文件前缀删除行，返回删除条数。
     *
     * <p>两个用途：① 集成测试用它清理自己写入的合成数据（见下）；
     * ② 将来语料文件下线时，可按前缀清掉该文件的遗留切片
     * （{@link #upsert} 只增改不删，语料删掉某个小节后那一行会变成孤儿）。</p>
     *
     * <p><b>为什么测试需要它</b>：向量表是开发库里唯一一张"内容由测试产生"的表。
     * 若测试直接对真实语料建立索引，它写进去的桩向量会<b>覆盖掉真实索引</b>——
     * 更糟的是因为内容哈希没变，下一次真实索引会判定"无需更新"从而不再修复它。
     * 因此测试必须在独立的来源命名空间里造数据，并在结束后删除。</p>
     */
    public int deleteBySourcePrefix(String prefix) {
        return jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE source_file LIKE ?", prefix + "%");
    }

    /** 表里现有多少行。用于验证"索引确实写进去了" */
    public int count() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM knowledge_chunk", Integer.class);
        return count == null ? 0 : count;
    }

    /** One bounded QA is one chunk. Catalog fence, replacement and cleanup commit in one PG transaction. */
    public boolean publish(KnowledgeSource source,String question,String answer,float[] embedding,String model) {
        return publish(source,question,answer,embedding,model,null);
    }

    public boolean publish(KnowledgeSource source,String question,String answer,float[] embedding,String model,
                           com.smartticket.experience.graph.ExperienceGraph.Plan graph) {
        String content=KnowledgeSource.content(question,answer);
        String hash=KnowledgeSource.hash(content);
        return Boolean.TRUE.equals(publicationTx.execute(status->{
            if(!publicationFence(source,"ACTIVE",hash)) return false;
            deleteBySourcePrefix(source.prefix());
            var chunk=new KnowledgeChunk(source.file(),"已审核演示工单经验",question,0,content);
            upsert(List.of(new IndexedRow(chunk,hash,embedding)),model);
            graphs.replace(jdbcTemplate,source,hash,graph);
            return true;
        }));
    }

    public boolean retract(KnowledgeSource source) {
        return Boolean.TRUE.equals(publicationTx.execute(status->{
            if(!publicationFence(source,"RETRACTED",null)) return false;
            deleteBySourcePrefix(source.prefix());
            graphs.retract(jdbcTemplate,source);
            return true;
        }));
    }

    public boolean publicationMatches(KnowledgeSource source,String hash,String model) {
        Integer count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_publication p JOIN knowledge_chunk c"
                + " ON c.source_file=p.source_file WHERE p.candidate_id=CAST(? AS uuid) AND p.revision=? AND p.state='ACTIVE'"
                + " AND p.content_hash=? AND c.content_hash=? AND c.model=?",Integer.class,
                source.candidateId(),source.revision(),hash,hash,model);
        return count!=null&&count==1;
    }

    public boolean graphMatches(KnowledgeSource source,com.smartticket.experience.graph.ExperienceGraph.Plan graph) {
        return graphs.matches(jdbcTemplate,source,graph);
    }

    /** Repair missing graph evidence using the committed text vector; no embedding call. */
    public boolean repairGraph(KnowledgeSource source,String hash,String model,com.smartticket.experience.graph.ExperienceGraph.Plan graph) {
        return Boolean.TRUE.equals(publicationTx.execute(status->{
            var row=jdbcTemplate.queryForList("SELECT revision,state,content_hash FROM knowledge_publication WHERE candidate_id=CAST(? AS uuid) FOR UPDATE",source.candidateId());
            if(row.size()!=1||((Number)row.get(0).get("revision")).longValue()!=source.revision()||!"ACTIVE".equals(row.get(0).get("state"))
                    ||!java.util.Objects.equals(hash,row.get(0).get("content_hash"))||!publicationMatches(source,hash,model))return false;
            graphs.replace(jdbcTemplate,source,hash,graph);return true;
        }));
    }

    public com.smartticket.experience.graph.ExperienceGraph.Snapshot graph(KnowledgeSource source) {
        return graphs.read(jdbcTemplate,source);
    }

    private boolean publicationFence(KnowledgeSource source,String state,String hash) {
        return jdbcTemplate.update("INSERT INTO knowledge_publication(candidate_id,revision,source_file,state,content_hash)"
                + " VALUES(CAST(? AS uuid),?,?,?,?) ON CONFLICT(candidate_id) DO UPDATE SET revision=EXCLUDED.revision,"
                + "source_file=EXCLUDED.source_file,state=EXCLUDED.state,content_hash=EXCLUDED.content_hash,updated_at=now()"
                + " WHERE knowledge_publication.revision<EXCLUDED.revision OR (knowledge_publication.revision=EXCLUDED.revision"
                + " AND knowledge_publication.state=EXCLUDED.state AND knowledge_publication.content_hash IS NOT DISTINCT FROM EXCLUDED.content_hash)",
                source.candidateId(),source.revision(),source.file(),state,hash)>0;
    }

    /**
     * 读取某条切片已存向量的维度；不存在返回 0。
     *
     * <p>存在的意义是给出"维度确实落到了库里、且与配置一致"的直接证据——
     * 只看写入行数无法区分"写进去了"和"写进去的是错的向量"。</p>
     */
    public int vectorDimensionsOf(String sourceFile, int chunkIndex) {
        List<Integer> dimensions = jdbcTemplate.queryForList(
                "SELECT vector_dims(embedding) FROM knowledge_chunk WHERE source_file = ? AND chunk_index = ?",
                Integer.class, sourceFile, chunkIndex);
        return dimensions.isEmpty() || dimensions.get(0) == null ? 0 : dimensions.get(0);
    }

    // ------------------------------------------------------------ 检索（阶段2 第6天）

    /** 读出库里出现过的全部嵌入模型名。用于检索前校验"查询向量与库中向量是否同源" */
    public Set<String> distinctModels() {
        return new LinkedHashSet<>(
                jdbcTemplate.queryForList("SELECT DISTINCT model FROM knowledge_chunk WHERE "+SEARCHABLE, String.class));
    }

    /**
     * 向量检索：取余弦相似度最高的若干条。
     *
     * <p>{@code <=>} 是 pgvector 的<b>余弦距离</b>（0 表示完全相同、越大越远）。
     * 这里用 {@code 1 - 距离} 换算成<b>相似度</b>再返回，是为了让两条检索路的分数方向一致
     * （都是"越大越相关"）——否则第7天做融合时还得先记住"这路的分越小越好、那路的分越大越好"，
     * 那是很容易写错的地方。</p>
     *
     * <p>{@code ORDER BY} 里直接用距离表达式而不是别名：这样 PostgreSQL 可以用
     * HNSW 索引做近邻扫描；若按别名的相似度降序排，索引就用不上了。</p>
     */
    public List<RetrievalHit> searchByVector(float[] queryVector, int topK) {
        String sql = """
                SELECT source_file, source_title, section_title, chunk_index, content,
                       1 - (embedding <=> ?) AS score
                FROM knowledge_chunk
                WHERE %s
                ORDER BY embedding <=> ?
                LIMIT ?
                """.formatted(SEARCHABLE);
        // 同一个向量要绑两次：一次算分、一次排序。分别构造，避免依赖驱动能否复用同一个 PGobject
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> toHit(rs),
                toPgVector(queryVector), toPgVector(queryVector), normalizeTopK(topK));
    }

    /**
     * 关键词检索：取字符三元组相似度（{@code word_similarity}）最高的若干条。
     *
     * <p>用 {@code word_similarity(query, content)} 而不是 {@code similarity(query, content)}：
     * 后者是两者三元组集合的相似度，短查询比对长正文时会被分母稀释（实测 26 条语料上最高只有 0.019，
     * 完全没有区分度）；前者算的是"查询与正文中某一段连续三元组"的最大相似度，适合短查询查长文档。</p>
     *
     * <p><b>比对的是"小节标题 + 正文"，不只是正文</b>（第5周任务 7）。理由：
     * 小节标题本来就是"用户会怎么问"的原话（FAQ 的标题就是问题的浓缩），
     * 而这一路做的正是**字面匹配**——把标题排除在外，等于把最可能逐字命中的那段文本丢掉了。
     * 评估集里 Q06「支付成功但订单未生成怎么办」此前排在融合结果第 2~3 位，
     * 正是因为只拿正文去比：该节正文写的是"未支付订单"这类措辞，与查询词的重合度反而更高。</p>
     *
     * <p>注意与**向量路**的分工：同样的"把标题算进来"在向量路上是<b>负结果</b>
     * （实测把正负样本的分数空档从 0.043 压到 0.0075，见 {@code KnowledgeIndexingService} 的说明）,
     * 在这里却是对症的——**字面的事交给字面的路去做**。</p>
     *
     * <p>{@code ORDER BY} 带上 {@code source_file, chunk_index} 作为并列时的次序：
     * 三元组相似度很容易出现并列（实测多个切片同分），不指定次序的话同一查询两次结果可能不同，
     * 测试也就无法稳定断言。</p>
     *
     * <p>子查询是为了让那条拼接表达式只写一次：PostgreSQL 不允许在 {@code WHERE} 里引用 SELECT 别名，
     * 不套一层就得把 {@code section_title || ' ' || content} 抄三遍，改一处漏一处是迟早的事。</p>
     *
     * @param minScore 相似度门槛，低于它的切片不进入结果；用 {@code >} 比较，
     *                 因此取 0 表示"至少得有一个字符三元组命中"
     */
    public List<RetrievalHit> searchByKeyword(String query, double minScore, int topK) {
        String sql = """
                SELECT source_file, source_title, section_title, chunk_index, content, score
                FROM (
                    SELECT source_file, source_title, section_title, chunk_index, content,
                           word_similarity(?, section_title || ' ' || content) AS score
                    FROM knowledge_chunk
                    WHERE %s
                ) candidate
                WHERE score > ?
                ORDER BY score DESC, source_file, chunk_index
                LIMIT ?
                """.formatted(SEARCHABLE);
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> toHit(rs),
                query, minScore, normalizeTopK(topK));
    }

    private RetrievalHit toHit(ResultSet rs) throws SQLException {
        KnowledgeChunk chunk = new KnowledgeChunk(
                rs.getString("source_file"),
                rs.getString("source_title"),
                rs.getString("section_title"),
                rs.getInt("chunk_index"),
                rs.getString("content"));
        return new RetrievalHit(chunk, rs.getDouble("score"));
    }

    /** 把 topK 兜到合法区间：至少取 1 条，配置写错时也不至于让 SQL 报错 */
    private static int normalizeTopK(int topK) {
        return Math.max(1, topK);
    }

    private PGobject toPgVector(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            // Float.toString 对极小值会输出科学计数法（如 1.0E-4），pgvector 的输入解析器接受这种写法
            literal.append(vector[i]);
        }
        literal.append(']');

        PGobject pgVector = new PGobject();
        try {
            pgVector.setType("vector");
            pgVector.setValue(literal.toString());
        } catch (SQLException e) {
            // setValue 只在值为 null 或非法时抛；这里传的是自己拼的字面量，正常不会发生。
            // 转成运行时异常是为了让下面的检索方法不必声明 SQLException，接口保持干净
            throw new IllegalStateException("构造 vector 参数失败：" + e.getMessage(), e);
        }
        return pgVector;
    }

    @PreDestroy
    void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("向量库连接池已关闭");
        }
    }

}
