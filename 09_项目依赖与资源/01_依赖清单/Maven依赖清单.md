# Maven 依赖清单

> 项目名称：AI驱动的智能工单服务系统
> 存放位置：`09_项目依赖与资源/01_依赖清单/Maven依赖清单.md`
> 更新日期：2026-10-09（阶段3 第6周：引入 RabbitMQ，见第二节；此前 2026-10-03 阶段2 第5天：引入 PostgreSQL 驱动与 pgvector，嵌入模型选定阿里云百炼 text-embedding-v4）
> 状态说明：待确认 / 待引入 / 已引入
> 说明：本清单只登记「计划引入」的依赖与引入阶段，具体版本在对应阶段冻结后回填。
> 配套文件：`软件版本清单.md`（记录本机软件与中间件版本）

---

## 〇、引入阶段与范围约束

依赖必须按阶段引入，**不允许提前引入后续阶段的依赖**（对应项目章程 2.2 功能优先级与阶段范围约束）。

| 阶段 | 允许引入的依赖范围 |
|---|---|
| 阶段1（最小骨架） | Web、Validation、MyBatis-Plus、MySQL 驱动、Lombok、测试依赖 |
| 阶段2（AI与RAG） | Spring AI、Embedding 相关、pgvector 相关 |
| 阶段3（工程化） | AMQP、Actuator、Micrometer、限流/熔断（可选） |
| 阶段4（收尾） | 测试增强（集成测试、Testcontainers 可选） |

---

## 一、基础依赖（阶段1）

> 版本冻结（2026-09-14，阶段1 第1天）：**Spring Boot 3.3.13**（3.3.x 线最后一个补丁版），对应 `06_项目源代码/ticket-service/pom.xml` 的 parent 版本。
> **升版（2026-09-25，阶段2 第1天）：3.3.13 → 3.5.14。** 起因见第三节：Spring AI 1.x 的 GA 版只支持 Spring Boot 3.4/3.5，
> 留在 3.3.x 只能用 1.0.0-Mx 里程碑版。升版后全量回归 107 个用例 0 失败（其中 1 个真实厂商冒烟测试默认跳过），无回归；3.5.x 仍是 Spring Framework 6.2 + Jackson 2，
> 与阶段1 的 MyBatis-Plus / Redis 用法兼容。连带变化：`mysql-connector-j` 8.3.0 → 9.7.0、`lombok` 1.18.38 → 1.18.46
> （均为 Spring Boot 依赖管理随之提升，实测连接与编译正常）。
> starter 类依赖的版本统一由 Spring Boot 依赖管理提供，不在 `pom.xml` 逐条手写版本号。

| 依赖 | groupId:artifactId | 版本 | 用途 | 状态 |
|---|---|---|---|---|
| Web | `org.springframework.boot:spring-boot-starter-web` | 随 Spring Boot 3.5.14 | REST 接口、内嵌容器 | 已引入 |
| 参数校验 | `org.springframework.boot:spring-boot-starter-validation` | 随 Spring Boot 3.5.14 | JSR-380 参数校验 | 已引入 |
| ORM | `com.baomidou:mybatis-plus-spring-boot3-starter` | 3.5.17（由 `mybatis-plus-bom` 统一管理） | CRUD、分页、SQL 映射 | 已引入 |
| ORM 分页支持 | `com.baomidou:mybatis-plus-jsqlparser` | 3.5.17（同上） | 3.5.9 起分页插件被拆出，`PaginationInnerInterceptor` 依赖它 | 已引入 |
| MySQL 驱动 | `com.mysql:mysql-connector-j` | 9.7.0（由 Spring Boot 3.5.14 管理） | MySQL 连接 | 已引入 |
| Lombok | `org.projectlombok:lombok` | 1.18.46（由 Spring Boot 3.5.14 管理） | 减少样板代码 | 已引入 |
| DevTools（可选） | `org.springframework.boot:spring-boot-devtools` | 随 Spring Boot 3.5.14 | 本地热重启，`optional=true` | 已引入 |

> 注意：MyBatis-Plus 必须使用 **`mybatis-plus-spring-boot3-starter`**（适配 Spring Boot 3）。
> 使用旧的 `mybatis-plus-boot-starter` 会因 `javax` → `jakarta` 命名空间变更导致启动失败。

---

## 二、数据与缓存依赖（阶段1 末 ~ 阶段3）

| 依赖 | groupId:artifactId | 版本 | 用途 | 引入阶段 | 状态 |
|---|---|---|---|---|---|
| Redis | `org.springframework.boot:spring-boot-starter-data-redis` | spring-data-redis 3.5.11、lettuce-core 6.6.0.RELEASE（随 Spring Boot 3.5.14） | 工单详情缓存 | 阶段1（第2周，第8天） | 已引入 |
| 连接池 | `org.apache.commons:commons-pool2` | — | Lettuce 连接池支持 | 阶段1（第2周） | **不引入**（第8天决策：默认 Lettuce 共享连接已够，阶段1 无池化需求，避免过度工程） |
| 消息队列 | `org.springframework.boot:spring-boot-starter-amqp` | spring-boot-starter-amqp 3.5.14（随 Spring Boot）；传递：spring-rabbit 3.2.10、spring-amqp 3.2.10、amqp-client 5.25.0 | RabbitMQ 异步 AI 分析 | 阶段3（第6周） | 已引入 |

---

## 三、AI 与向量依赖（阶段2）

| 依赖 | groupId:artifactId | 版本 | 用途 | 状态 |
|---|---|---|---|---|
| Spring AI BOM | `org.springframework.ai:spring-ai-bom` | **1.1.6** | 统一管理 spring-ai 各模块版本（`dependencyManagement` import） | **已引入**（2026-09-25，阶段2 第1天） |
| 模型接入 | `org.springframework.ai:spring-ai-starter-model-openai` | **1.1.6** | LLM 调用；走 OpenAI 兼容协议，换厂商只改 base-url + model | **已引入**（2026-09-25） |
| ↳ 传递引入 | `spring-ai-openai` / `spring-ai-model` / `spring-ai-client-chat` / `spring-ai-commons` / `spring-ai-retry` | 1.1.6 | ChatClient、Prompt、RetryTemplate 等 | 已引入（传递） |
| PostgreSQL 驱动 | `org.postgresql:postgresql` | **42.7.10**（由 Spring Boot 3.5.14 管理） | 连接 pgvector 所在库 | **已引入**（2026-10-03，阶段2 第5天） |
| 向量检索 | **不引入**：手写 SQL（`ORDER BY embedding <=> ?`） | — | 相似度检索 | **已决策**（2026-10-03）：不用 Spring AI 官方 `spring-ai-starter-vector-store-pgvector`，理由见决策记录第五节 |
| Embedding 模型 | **阿里云百炼 `text-embedding-v4`**，复用已有的 `spring-ai-starter-model-openai`（OpenAI 兼容协议） | 服务端模型，无新依赖 | 文本向量化 | **已决策**（2026-10-03）：DeepSeek 无 embeddings 端点（实测 404），故嵌入另选厂商 |

> **PostgreSQL 驱动刻意不加 `<scope>runtime</scope>`**（MySQL 驱动是加的）：
> 写 `vector` 列要显式构造 `org.postgresql.util.PGobject` 声明参数类型，那是驱动里的类，
> 运行时才可见的话编译不过。用 `?::vector` 拼接替代会依赖服务端把参数当 `unknown` 处理，
> 参数一旦被驱动定性为 `varchar` 就会报 `cannot cast type character varying to vector`。
> 细节见 `01_项目说明/08_决策记录/20261003_RAG向量化与向量库.md` 第九节。

> 约束（对应章程 阶段2 关键约束）：AI 输出必须结构化、必须定义 JSON Schema、必须处理解析失败与超时、RAG 结果必须带来源。

**兼容性验证结论（2026-09-25）**

> 原清单要求：「Spring AI 版本与 Spring Boot 3.3.x 的兼容组合属于**高风险项**，必须在阶段2 开工前先用最小用例验证，验证结果写入决策记录。」
> 本次即为该验证的落地结论——验证结果是「不兼容」，因此先升版再接入。

| 项 | 结论 |
|---|---|
| Spring AI 1.1.6 与 Spring Boot 3.3.13 | **不兼容**。官方兼容矩阵为 Spring Boot 3.4.x / 3.5.x（Spring AI 1.x 基于 Spring Framework 6.2，3.3.x 是 6.1） |
| 处置 | 升 Spring Boot 至 **3.5.14**（3.5.x 线最新补丁版），Spring AI 取 **1.1.6**（1.1.x 线最新 GA） |
| 升级回归 | 全量 107 个用例 0 失败（升版前 92，本期新增 15；其中 1 个真实厂商冒烟测试默认跳过），无既有用例失败 |
| 真实厂商实测 | **已用 DeepSeek 真实密钥调通**（2026-09-25）：success=true、908ms、返回合规 JSON。结论：密钥 / base-url / 模型名 `deepseek-chat` 三者均正确 |
| 备选方案（未采用） | 留在 3.3.13 + Spring AI 1.0.0-Mx 里程碑版：需承担里程碑版 API 变动风险，且该线已不在维护 |
| 详细决策依据 | `01_项目说明/08_决策记录/20260925_阶段2AI接入方案.md` |

---

## 四、可观测性依赖（阶段3）

| 依赖 | groupId:artifactId | 版本 | 用途 | 状态 |
|---|---|---|---|---|
| Actuator | `org.springframework.boot:spring-boot-starter-actuator` | 随 Spring Boot 3.5.x | 健康检查、指标暴露 | 待引入 |
| Micrometer | 随 `spring-boot-starter-actuator` 传递引入 | 随 Spring Boot 3.5.x | AI 调用耗时、Token 消耗指标 | 待引入 |
| OpenTelemetry | 待确认（章程标记为「可选」） | — | 链路追踪 | 可选，不阻塞主线 |

---

## 五、测试依赖（阶段1 起可用）

| 依赖 | groupId:artifactId | 版本 | 用途 | 状态 |
|---|---|---|---|---|
| 测试套件 | `org.springframework.boot:spring-boot-starter-test` | 随 Spring Boot 3.5.14 | 集成测试基础（第3天引入，用于验证 Mapper 扫描与数据库连接） | 已引入 |
| JUnit 5 | `org.junit.jupiter:junit-jupiter` | 随上述 starter 传递引入 | 单元测试 | 已引入 |
| Mockito | `org.mockito:mockito-core` | 随上述 starter 传递引入 | Mock 数据库/Redis | 已引入 |

---

## 六、明确不引入（防止范围失控）

对应项目章程 1.5.2 范围外与阶段约束，以下依赖本期**不引入**：

- Sentinel / Hystrix 等熔断框架（阶段3 如需限流，优先手写简单计数限流）
- ShardingSphere 等分库分表组件
- 工作流引擎（Activiti / Flowable）
- 网关组件（Spring Cloud Gateway）
- 注册中心与配置中心（Nacos / Eureka / Config）
- 模型训练或微调相关框架

---

## 七、版本冻结与回填规则

1. 进入某阶段时，先冻结该阶段依赖的版本，再写入本清单，状态改为「已引入」。
2. 版本号必须在 `06_项目源代码/ticket-service/pom.xml` 中体现，且优先使用 Spring Boot 的 `<dependencyManagement>` 统一管理，不逐条手写版本号。
3. `pom.xml` 必须显式声明以下构建属性（本次盘点发现本机平台编码为 GBK）：
   - `project.build.sourceEncoding=UTF-8`
   - `maven.compiler.release=17`
4. 若要新增本清单之外的依赖，必须先更新本清单并在 AI 对话日志中留痕，禁止直接写入 `pom.xml`。

## 阶段三指标依赖（2026-10-13）

新增io.micrometer:micrometer-core，版本由Spring Boot管理（本机1.15.11）；使用SimpleMeterRegistry与受鉴权的管理员接口，未增加Actuator或新的外部服务。
