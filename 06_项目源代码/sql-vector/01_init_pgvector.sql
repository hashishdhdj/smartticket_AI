-- ============================================================================
-- 知识库向量表初始化（阶段2 第5天，章程第4周任务 5）
--
-- 执行时机：由 docker-compose 挂载到 PostgreSQL 容器的 /docker-entrypoint-initdb.d，
--           仅在**数据卷为空**（首次初始化）时执行一次。
--           改了本文件后要重跑，必须先删除数据卷：
--             docker compose down -v pgvector  （只删向量库的卷，不影响 MySQL/Redis）
--
-- 与 MySQL 的关系：本文件里的表属于**独立的知识库向量存储**，
--           与 06_项目源代码/sql/01_init_schema.sql 中的工单表分属两个库、两套连接，
--           互不引用、互不 JOIN。理由见决策记录 20261003_RAG向量化与向量库.md 第三节。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 扩展：pgvector
--
-- pgvector 提供 vector 类型与 <=>（余弦距离）、<->（欧氏距离）、<#>（内积）三个算子。
-- 用 pgvector/pgvector:pg16 官方镜像时扩展已随镜像预装，因此这里只需 CREATE EXTENSION，
-- 不需要编译或额外下载。IF NOT EXISTS 让它可以在重复执行时安全跳过。
-- ----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;

-- ----------------------------------------------------------------------------
-- 1b. 扩展：pg_trgm（阶段2 第6天加入，用于关键词检索）
--
-- 为什么需要它：中文没有空格分词，PostgreSQL 默认的 to_tsvector 会把一整句中文
-- 当成一个 token（实测），全文检索等于没用。pg_trgm 提供的是**字符三元组**相似度，
-- 对中文天然适用——不需要任何分词器，也不需要额外服务。
--
-- 它提供两个不同的函数，选错会得到完全无用的结果（实测）：
--   similarity(a, b)      两者三元组集合的相似度。短查询比对长正文时会被分母稀释，
--                         实测 26 条语料上最高只有 0.019 —— 不能用。
--   word_similarity(a, b) a 与 b 中「某一段连续三元组」的最大相似度，
--                         适合"短查询查长文档"，实测能正确排序。
-- 关键词检索用的是后者，见 KnowledgeVectorRepository。
--
-- pg_trgm 属于 PostgreSQL contrib 模块，pgvector/pgvector:pg16 镜像已内置（实测 1.6）。
-- ----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ----------------------------------------------------------------------------
-- 2. 切片表
--
-- 一行 = 一个切片 = 一个向量。字段分三组：
--   ① 语义内容：content
--   ② 来源元数据：source_file / source_title / section_title / chunk_index
--      —— 对应 Java 侧的 KnowledgeChunk，用于满足「检索结果必须可追溯来源」的约束。
--         必须落到列里（而不是只存在内存），否则检索回来时说不清出处。
--   ③ 运维列：content_hash / model / created_at / updated_at
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id            BIGSERIAL    PRIMARY KEY,

    source_file   VARCHAR(255) NOT NULL,
    source_title  VARCHAR(255) NOT NULL,
    section_title VARCHAR(255) NOT NULL,
    chunk_index   INTEGER      NOT NULL,

    content       TEXT         NOT NULL,

    -- content 的 SHA-256（64 位十六进制）。
    -- 用途：判断"这一条切片的正文有没有变过"，正文没变就不重复调用向量化接口。
    -- 这不是性能优化而是成本控制：embedding 按 token 计费，每次启动都全量重算纯属浪费。
    content_hash  VARCHAR(64)  NOT NULL,

    -- 向量本体。1024 维来自 text-embedding-v4 的默认维度（见决策记录）。
    -- 维度写死在这里：换模型若改变维度，必须改本文件 + 清表重建，
    -- 因为不同维度的向量无法比较，混在一张表里检索结果没有意义。
    embedding     VECTOR(1024) NOT NULL,

    -- 生成该向量的模型名。同一张表里出现多个模型名 = 有人换了模型但没重建，
    -- 检索质量会变得不可解释，因此这一列是排查用的"指纹"。
    model         VARCHAR(100) NOT NULL,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  knowledge_chunk               IS '知识库切片及其向量（阶段2 RAG，演示语料）';
COMMENT ON COLUMN knowledge_chunk.source_file   IS '语料相对路径，如 faq/01-账户问题.md';
COMMENT ON COLUMN knowledge_chunk.chunk_index   IS '该切片在整份文档内的连续序号，从 0 开始';
COMMENT ON COLUMN knowledge_chunk.content_hash  IS 'content 的 SHA-256，用于跳过未变化的切片';
COMMENT ON COLUMN knowledge_chunk.embedding     IS 'text-embedding-v4 生成的 1024 维向量，余弦距离检索';
COMMENT ON COLUMN knowledge_chunk.model         IS '生成该向量的模型名';

-- ----------------------------------------------------------------------------
-- 3. 来源唯一约束
--
-- 为什么是 (source_file, chunk_index) 而不是 content_hash：
--   - 用内容做唯一键会**跨来源去重**——FAQ 与历史工单里若有完全相同的句子，只会留下一条，
--     另一个来源就丢了。而"来源可追溯"要求每个来源各自留痕。
--   - (文件, 序号) 才是切片的自然身份：同一份语料切出的第 i 块，天然唯一。
--     它同时是幂等写入的依据（ON CONFLICT ... DO UPDATE），
--     也是"语料改了、序号没变"时能就地覆盖的保证。
-- ----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_chunk_source
    ON knowledge_chunk (source_file, chunk_index);

-- ----------------------------------------------------------------------------
-- 4. 向量索引
--
-- HNSW + vector_cosine_ops：
--   - 余弦距离与我们使用的 embedding 模型语义一致（text-embedding-v4 的相似度即余弦相似度）；
--     先做过归一化的向量用余弦距离最稳，不必再额外做 L2 归一化。
--   - HNSW 相比 IVFFlat 不需要预先"训练"（IVFFlat 要在有数据之后才能建索引，
--     且对初始数据量敏感，lists 参数选错会显著掉召回）。当前只有 26 条切片，
--     建索引几乎瞬间完成，因此直接选召回质量更好的 HNSW。
--   - m / ef_construction 采用 pgvector 默认值（16 / 64），不做无依据的调参。
--
-- 注意：数据量很小（几十条）时，PostgreSQL 规划器很可能选择顺序扫描而非走索引
--（走索引反而更慢）。这不影响正确性，索引是为将来语料扩充准备的。
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);

-- ----------------------------------------------------------------------------
-- 5. 关键词检索索引（阶段2 第6天加入）
--
-- 用 GIN + gin_trgm_ops：为正文的字符三元组建倒排，服务于两类过滤——
--   ① LIKE / ILIKE 的子串过滤（将来若加"必须包含某词"的硬条件时用得上）；
--   ② pg_trgm 的 `%` 相似度阈值过滤（`content % '关键词'`）。
--
-- 注意：**当前的关键词检索用不到这个索引**。检索是按 word_similarity 对全部切片
-- 排名取前 K，这本质上是一次全表打分，索引帮不上忙；26 条数据下规划器也会
-- 直接选顺序扫描（走索引反而更慢）。
-- 建它的理由与 HNSW 索引相同：为语料扩充后的"带过滤条件的检索"准备，
-- 而不是指望它给现在的查询加速。
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_content_trgm
    ON knowledge_chunk USING gin (content gin_trgm_ops);
