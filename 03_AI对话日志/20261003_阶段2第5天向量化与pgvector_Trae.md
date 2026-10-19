# AI对话日志

## 基本信息

| 项目 | 内容 |
|---|---|
| 日期 | 2026-10-03 |
| 主题 | 阶段2 第5天：文本向量化 + 存入 PostgreSQL/pgvector |
| 使用模型 | Trae |
| 当前阶段 | 阶段2：AI集成与RAG知识库（第4周） |
| 对话目的 | 完成章程第4周任务 4~5：文本向量化、存入 PostgreSQL + pgvector |

---

## 输入内容

用户指令：「开始下一天的工作」（承接第4天结束时的范围约定）。

第4天结束时我给出的范围为：继续章程第4周任务 4~8（向量化、入库、向量检索、关键词检索、合并结果）。
本次完成其中任务 4~5。

**过程中用户做了一次决策**：嵌入模型选哪家（见下）。

---

## AI输出摘要

### 1. 一个必须先回答的事实问题

要"文本向量化"，就得有一个嵌入模型。本项目第1天选了 DeepSeek 做对话模型，
于是有一个自然假设："一家厂商应该够用"。**这个假设是错的。**

我没有去搜文章（搜到的结果里混着第三方代理服务的广告，把自家代理包装成"DeepSeek 的 embedding"），
而是**用项目里已配好的真实 DeepSeek 密钥打了两个请求**：

| 探测 | 结果 |
|---|---|
| `GET /v1/models` | HTTP 200，只有 `deepseek-flash` / `deepseek-v4-pro`，**无任何嵌入模型** |
| `POST /v1/embeddings` | **HTTP 404**，端点不存在 |

结论：DeepSeek 不提供 embedding 能力，向量化必须换一个来源。

### 2. 一次需要用户参与的决策

嵌入模型涉及**注册账号、获取密钥、按量计费**，不是纯技术取舍，因此用选项形式交由用户决定：

| 候选 | 关键信息 |
|---|---|
| **阿里云百炼 `text-embedding-v4`（用户选定）** | 国内直连、OpenAI 兼容、维度可选 1024、有 100 万 token 免费额度 |
| 本地 Ollama + bge-m3 | 零 Key、离线，但需再拉 2~3 GB 镜像与模型 |
| 智谱 `embedding-3` | 国内直连，无相对优势且需另开账号 |
| OpenAI `text-embedding-3-small` | 维度 1536，需海外网络 |

### 3. 产出清单

| 分类 | 文件 | 说明 |
|---|---|---|
| 基础设施 | `08_部署与环境/01_docker/docker-compose.yml` | 新增 `pgvector/pgvector:pg16` 服务（5432、健康检查、数据卷） |
| 基础设施 | `06_项目源代码/sql-vector/01_init_pgvector.sql` | 新建目录：`vector` 扩展 + `knowledge_chunk` 表 + 唯一索引 + HNSW 索引 |
| 配置 | `application.yml` | 新增 `smartticket.vector.*`；把 `spring.ai.model.embedding` 设为 `none` |
| 依赖 | `pom.xml` | 新增 PostgreSQL 驱动（**不加 `runtime` scope**，理由见决策记录第九节） |
| 装配 | `rag/config/EmbeddingConfig.java` | 专用于嵌入的 `EmbeddingModel` bean（独立 base-url/密钥/维度） |
| 服务 | `rag/EmbeddingService.java` + `rag/impl/EmbeddingServiceImpl.java` | 可降级、**分批 ≤10**、逐条校验条数与维度 |
| 服务 | `rag/KnowledgeIndexingService.java` | 编排：切片 → 向量 → 落库；按哈希+模型做增量 |
| 存储 | `rag/KnowledgeVectorRepository.java` | PG 读写；连接池自持有，不注册 `DataSource` bean |
| 配置 | `rag/KnowledgeVectorProperties.java` | `@ConfigurationProperties` 绑定 |
| 触发 | `rag/KnowledgeIndexingRunner.java` | `index-on-startup=true` 时才执行 |
| 结果 | `rag/EmbeddingResult.java` | record，与 `AiCallResult` 同契约（不抛异常） |
| 错误码 | `ErrorCode.java` | 新增 `VECTOR_STORE_UNAVAILABLE(10009)` |

### 4. 三个关键工程取舍

| 取舍 | 结论 | 理由 |
|---|---|---|
| 向量存哪里 | **另起 PostgreSQL**，不动 MySQL | MySQL 8.0 无向量类型/索引；pgvector 是 PG 扩展，扩展不能跨库安装 |
| 第二个数据源怎么接 | 连接池**自持有**，不注册 `DataSource` bean | Spring Boot 的数据源自动配置带 `@ConditionalOnMissingBean`，随便加一个 DataSource bean 会把 MySQL 那套顶掉，整个阶段1 塌掉 |
| 用不用官方 PgVectorStore | **手写 SQL** | 章程要求"实现"检索而非"调用"；且需要按哈希+模型做幂等，官方 upsert 不提供 |

---

## 采纳情况

代码、基础设施、测试全部采纳，用户选定的嵌入厂商即决策记录中的方案。

**未采纳但记录在案的**：官方 `spring-ai-starter-vector-store-pgvector`。不是不知道它存在，
而是三条具体理由（见决策记录第五节），代价与收益在本项目不划算。

---

## 发现问题

| 序号 | 问题 | 处理 |
|---|---|---|
| 1 | **Docker 引擎第 10 次停**（开工时发现 `docker-desktop` 发行版 Stopped，GUI 进程也已退出） | 重新拉起。这已是本对话第十次，Resource Saver 的怀疑需要尽快验证 |
| 2 | **拉不到 `pgvector/pgvector:pg16`**：国内直连 `registry-1.docker.io` 超时 | 探测多个加速器的 443 可达性 → `docker.m.daocloud.io` 可用 → 用它拉取并 `docker tag` 成官方名；同时把 `registry-mirrors` 写进 `daemon.json`（重启 Docker Desktop 后对**所有**镜像生效），并记入 docker README |
| 3 | **自己踩了项目早已记录过的坑**：用 PowerShell 5.1 运行**无 BOM 且含中文**的脚本去追加 YAML，PS 按 GBK 解析，中文注释前的换行被解码器吞掉，导致 `# 注释` 与 `password:` **粘成同一行**，配置直接失效 | 修复方式：**待写入内容放独立 UTF-8 文件，脚本本身只用 ASCII**，用 `File::ReadAllText(utf8)` + `File::WriteAllText(utf8)` 而非 `Get-Content`/`Add-Content`。项目 memory 里早有这条教训，这次是**违反了已有的规则**，不是新发现 |
| 4 | 集成测试首轮失败：`CannotGetJdbcConnectionException` | 根因就是第 3 条——密码行被粘连，PWD 为空。修复 YAML 后即通 |
| 5 | 集成测试第二轮失败：`expected: <26> but was: <0>` | 根因是**测试顺序依赖**：先跑的那个用例已建好索引，后跑的却断言"首次应写入 26 行"。改断言为与顺序无关且更有价值的形态——**逐条核对 26 条切片的向量维度** |
| 6 | 幂等只看内容哈希会漏掉"换嵌入模型" | 实现中途发现：换模型后正文不变 → 哈希相同 → 判为无需更新 → 库里**混装两个模型的向量**且不报错。补上 `model` 列的比对 |
| 7 | 观察到 `ticket` 表当前 0 行 | 开发库内容问题，与本次改动无关（数据卷存在、36 个接口测试全绿）。仅记录，不处理 |
| 8 | **配好密钥后真实调用返回 404** | 排查方式：用**同一把密钥**直接打两个候选 URL —— `.../compatible-mode/v1/embeddings` 返回 200、`.../compatible-mode/v1/v1/embeddings` 返回 404。根因从 `OpenAiApi.Builder` 类常量池读到实证：Spring AI 把 `/v1/embeddings` 写成了**默认完整路径**并拼在 base-url 之后。所以 base-url 必须是**不带 `/v1` 的主机根地址**。厂商文档给的 `/compatible-mode/v1` 照抄就错，而对话侧的 DeepSeek 恰好填的是不带 `/v1` 的写法——同一约定、两种文档写法，容易只在一边踩到。已写进 application.yml / 属性类 / README 三处 |
| 9 | **集成测试会覆盖真实向量**（建好真实索引后回看才发现） | 原测试对**真实语料**调 `indexAll()` 写入桩向量，而 `upsert` 的键 `(source_file, chunk_index)` 与真实索引**共用同一批行**——即 `mvn clean test` 会把真实向量覆盖成假向量，且因为断言（行数、维度）全都照样通过，**测试全绿时看不出来**。修法：测试改用独立来源命名空间 `__test__/`，只测仓库层并在用例后按前缀清理。修复后跑完整套件复验：库里仍是 26 条 `text-embedding-v4`、测试残留 0 条 |

---

## 下一步行动

1. ~~用户到阿里云百炼控制台创建 API Key~~ **已完成（2026-10-04）**，真实调用已跑通，见下方"验证证据"。
2. 第6天起进入任务 6~8：向量检索、关键词检索、合并检索结果（第4周收尾）。
3. 承接既有遗留：Docker Resource Saver 未验证；Token 消耗未采集；输入脱敏待产品/合规判断。

---

## 是否更新约束

| 项 | 结论 |
|---|---|
| `01_项目说明/08_决策记录/` | **新增** `20261003_RAG向量化与向量库.md`（11 节，含 9 项已知限制） |
| `08_部署与环境/01_docker/README.md` | **更新**：服务清单加 pgvector、凭据表加 `POSTGRES_PASSWORD`、新增镜像加速器说明、初始化 SQL 章节改为两套脚本对照、连接方式加 psql 示例、已知限制加 2 条 |
| `08_部署与环境/02_env/.env.example` | **更新**：新增 `POSTGRES_PASSWORD=change_me` |
| `06_项目源代码/ticket-service/README.md` | **更新**：技术栈加 PostgreSQL/pgvector、测试数 172→186、代码结构加 `sql-vector/` 与向量库说明、常用命令加索引命令 |
| `09_项目依赖与资源/01_依赖清单/Maven依赖清单.md` | **更新**：新增 PostgreSQL 驱动（含"不加 runtime scope"的说明） |
| `02_AI约束与规范/AI协作总则.md` | **不更新**。本次未新增禁止事项；但第 3 条问题（PowerShell 编码）**属于已有规则被违反**，不构成新规则 |
| `02_AI约束与规范/03_Prompt模板/README.md` | **不更新**。本次未触碰 Prompt 模板 |

---

## 验证证据

**全量回归**

```text
[INFO] Tests run:   7, Failures: 0 -- EmbeddingServiceImplTest（新增）
[INFO] Tests run:   5, Failures: 0 -- KnowledgeIndexingServiceTest（新增）
[INFO] Tests run:   2, Failures: 0 -- KnowledgeVectorIntegrationTest（新增，真连 PostgreSQL）
[INFO] Tests run:  36, Failures: 0 -- TicketApiTest（加第二个数据源后无回归）
[WARNING] Tests run: 2, Skipped: 2       -- AiRealVendorSmokeTest（真实调用，默认跳过）
[INFO] Tests run: 186, Failures: 0, Errors: 0, Skipped: 2
[INFO] BUILD SUCCESS
```

**基础设施（实测）**

```text
vector 扩展 0.8.7 | knowledge_chunk 存在 | embedding 列 vector(1024)
索引 3 个：pkey(btree id) / uk_knowledge_chunk_source(unique) / idx_knowledge_chunk_embedding(hnsw)
容器 smartticket-pgvector | Up (healthy) | 0.0.0.0:5432->5432/tcp
```

**运行期接线 + 降级（未配嵌入密钥）**

```text
嵌入模型已装配 | model=text-embedding-v4 | dimensions=1024 | baseUrl=https://dashscope.aliyuncs.com/compatible-mode
向量化通道不可用，RAG 检索将返回未命中 | reason=未配置嵌入模型密钥（仍为占位值）
vector-pg-pool - Start completed.（连接池初始化成功）
Started TicketServiceApplication in 3.061 seconds
知识库语料加载完成 | 文件数=7 | 切片数=26
知识库索引已降级 | 切片=26 | reason=向量化通道不可用
知识库索引未完成，RAG 检索将返回未命中 | errorCode=AI_NOT_CONFIGURED | cost=4ms
```

两个既有接口同时实测未受影响：

```text
GET  /api/tickets?page=1&size=1   → HTTP 200 | code=0
POST /api/ai/tickets/analyze      → HTTP 200 | code=0 | category=支付问题 | priority=URGENT | confidence=0.85 | costMs=1144
```

**真实嵌入调用（2026-10-04，配好百炼密钥后）**

```text
向量化通道就绪 | model=text-embedding-v4 | dimensions=1024 | batchSize=10
向量化完成 | model=text-embedding-v4 | texts=26 | batches=3 | dimensions=1024 | cost=1297ms
知识库索引完成 | 切片=26 | 跳过（内容未变）=0 | 本次写入=26 | totalRows=26 | cost=1400ms
```

**幂等（再启动一次）**

```text
知识库索引已是最新，无需重新向量化 | 切片=26 | 全部命中已有内容哈希
知识库索引结束 | 切片=26 | 跳过=26 | 写入=0 | cost=24ms
```

第二次**没有出现"向量化完成"那一行**，即一次嵌入接口都没调；耗时 1400ms → 24ms。

**库里到底存了什么（pgvector 函数读回）**

```text
 rows | models |    model_name     | min_dim | max_dim | files
------+--------+-------------------+---------+---------+-------
   26 |      1 | text-embedding-v4 |    1024 |    1024 |     7

    source_file     | chunk_index | dims |  norm
--------------------+-------------+------+--------
 faq/01-账户问题.md |           0 | 1024 | 1.0000
```

26 行、全部 1024 维、只有一个模型、来源覆盖 7 份文件。
顺带发现 **`norm = 1.0000`**：百炼返回的向量已是 L2 归一化，应用侧不需要再归一化。

**语义验证（库里直接算余弦距离，不调任何接口）**

锚点 = `faq/01-账户问题.md#0`（登录时提示「验证码异常」），与各文件第 0 条比较：

| 余弦距离 | 切片 | 主题 |
|---|---|---|
| 0.0000 | `faq/01#0` 登录时提示「验证码异常」 | 锚点自己 |
| **0.1723** | `tickets/01#0` 登录页反复提示验证码错误，多设备均无法登录 | **账户（同一主题）** |
| 0.3941 | `faq/03#0` 页面一直转圈或白屏 | 功能异常 |
| 0.5106 | `tickets/02#0` 银行卡已扣款但订单列表中没有该订单 | 支付 |
| 0.5255 | `faq/02#0` 支付成功但订单未生成 | 支付 |
| 0.5468 | `tickets/03#0` 订单列表条数与详情页不一致 | 功能异常 |
| 0.6469 | `faq/04#0` 工单的处理流程与时限 | 工单流程 |

同主题以 0.17 排第一，与其余全部（0.39 以上）差距明显，且是**跨文档**匹配。
这条把"向量化"从"写进去 26 行数据"提升为"写进去的东西有语义"——
后者才是第6~8 天做检索的前提。
