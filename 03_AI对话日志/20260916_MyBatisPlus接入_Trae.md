# AI对话日志

## 基本信息

| 项目 | 内容 |
|---|---|
| 日期 | 2026-09-16 |
| 主题 | 阶段1 第3天：接入 MyBatis-Plus，创建工单实体与 Mapper |
| 使用模型 | Trae |
| 当前阶段 | 阶段1：环境搭建与最小骨架 |
| 对话目的 | 让 Spring Boot 应用具备数据访问能力：引入 MyBatis-Plus、配置数据源、把 `ticket` / `ticket_log` 两张表映射为 Java 实体与 Mapper，并完成"Mapper 被扫描 + 连库成功"的验证 |

---

## 输入内容

用户先要求先交底再执行：

```
我需要详细了解第三天的任务安排：1.前序工作和第三天工作的联系；2.第三天工作在整个项目中的作用；
3.第三天工作的具体执行步骤，哪些是我来执行的，是否需要写代码；4.第三天工作的详细介绍。
具体原则：一定要让我完全弄懂每一步的执行，摸清楚数据结构，相互之间的关联。
```

交底后用户给出的 4 项决策：

| 决策项 | 用户选择 |
|---|---|
| `mybatis-plus-jsqlparser`（分页依赖）何时引 | **现在就加** |
| 如何验证"Mapper 扫描到 + 连库成功" | **加 `spring-boot-starter-test` 写测试** |
| 应用连库密码放哪 | **写进 `application-local.yml`（已被 gitignore）** |
| 开发期 SQL 日志 | **用日志级别开（`com.smartticket.mapper: debug`）** |

补充信息：用户反馈"IDEA 里看到的 JDK 版本是 25"。

本次生效的约束（来自 `01_项目说明/阶段一指导.md` 第3天、`02_AI约束与规范/AI协作总则.md`、`Maven依赖清单.md`）：

| 约束 | 来源 |
|---|---|
| MyBatis-Plus 必须用 `mybatis-plus-spring-boot3-starter` | Maven依赖清单 一 |
| 版本优先用 `dependencyManagement` 统一管理，不逐条手写 | Maven依赖清单 七.2 |
| 实体类使用 Lombok，使用 `@TableName`/`@TableId`/`@TableField`，逻辑删除字段为 `deleted` | 阶段一指导 第3天 |
| 不要实现 Service 和 Controller | 阶段一指导 第3天 |
| 不得擅自新增数据库字段 | AI协作总则 第5条 |
| 必须给出验证方式，且覆盖异常与边界 | AI协作总则 第8条 |

---

## AI输出摘要

### 1. 依赖（Step 1）

`pom.xml` 采用 **`mybatis-plus-bom:3.5.17`** 统一版本，依赖只写坐标不写版本号：

- `com.baomidou:mybatis-plus-spring-boot3-starter`（Boot 3 专用，用旧 starter 会因 `javax`→`jakarta` 启动失败）
- `com.baomidou:mybatis-plus-jsqlparser`（3.5.9 起分页插件被拆出，`PaginationInnerInterceptor` 依赖它）

版本来源为 Maven Central 实测：`mybatis-plus-spring-boot3-starter` / `mybatis-plus-jsqlparser` / `mybatis-plus-bom` 三个构件 latest 均为 **3.5.17**（2026-07-08 发布），不是凭记忆填写。

### 2. 配置（Step 2）

`application.yml`（会提交）新增数据源与 MyBatis-Plus 配置；`application-local.yml`（gitignore）承载真实密码与 SQL 日志开关。

关键项及其必要性：

| 配置 | 不写会怎样 |
|---|---|
| `db-config.id-type: auto` | MyBatis-Plus 默认雪花算法，会绕开 MySQL 自增主键 |
| `logic-delete-field: deleted` | 查询不带 `deleted = 0`，已删除数据会被查出来 |
| `map-underscore-to-camel-case: true` | `ticket_no` → `ticketNo` 映射失败 |
| JDBC URL 的 `allowPublicKeyRetrieval=true` | MySQL 8 默认 `caching_sha2_password`，非 SSL 连接报 `Public Key Retrieval is not allowed` |
| `serverTimezone=Asia/Shanghai` | 时间戳与容器 `TZ` 不一致，差 8 小时 |

### 3. 实体（Step 4）

`Ticket`（13 字段）与 `TicketLog`（8 字段）逐字段对应第2天的表结构。三个关键点：

1. `@TableId(type = IdType.AUTO)` —— 必须显式声明；
2. `@TableLogic` 放在 `deleted` 上；
3. 时间戳字段**不做自动填充**，插入时留 null，由数据库 `DEFAULT` / `ON UPDATE` 维护（依赖 MyBatis-Plus 默认只拼非 null 字段的策略）。

`TicketLog` **故意没有** `deleted` 与 `updatedAt`：日志表是 append-only 的审计表。

### 4. Mapper 与扫描（Step 5）

`TicketMapper` / `TicketLogMapper` 只 `extends BaseMapper<T>`，无实现类（MyBatis 运行时用 JDK 动态代理生成）。启动类加 `@MapperScan("com.smartticket.mapper")`，优于逐个接口写 `@Mapper`。

### 5. 验证证据（Step 6/7）

测试 3 个用例全绿：

```text
HikariPool-1 - Start completed.

c.s.mapper.TicketMapper.selectCount    : ==> Preparing: SELECT COUNT( * ) AS total FROM ticket WHERE deleted=0
c.s.mapper.TicketLogMapper.selectCount : ==> Preparing: SELECT COUNT( * ) AS total FROM ticket_log
c.s.mapper.TicketMapper.selectById     : ==> Preparing: SELECT id,ticket_no,...,deleted FROM ticket WHERE id=? AND deleted=0

Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

启动验证：

```text
The following 1 profile is active: "local"
Tomcat started on port 8080 (http) with context path '/'
Started TicketServiceApplication in 2.107 seconds
```

---

## 采纳情况

| 内容 | 是否采纳 | 原因 |
|---|---|---|
| 用 BOM 统一 MyBatis-Plus 版本 | 是 | 官方要求 starter 与 jsqlparser 版本一致，且符合清单"版本交给 dependencyManagement"的规则 |
| 现在就引入 `mybatis-plus-jsqlparser` | 是（用户决策） | 提前消除 3.5.9+ 分页拆包带来的隐患 |
| 加 `spring-boot-starter-test` 做验收 | 是（用户决策） | 单纯启动无法证明"扫描到 Mapper"和"连上库"，且第12天本就要用 |
| 密码放 `application-local.yml` | 是（用户决策） | 该文件已被 gitignore，兼顾便利与"仓库零密码" |
| `application.yml` 里密码留 `${MYSQL_PASSWORD:}` 占位 | 是 | 保留环境变量注入路径，不写死任何密码 |
| SQL 日志用 logging level 而非 `StdOutImpl` | 是（用户决策） | 输出可控，生产可关 |
| `@MapperScan` 而非逐接口 `@Mapper` | 是 | 新增 Mapper 零改动 |
| 复用第1天已建的 `entity/`、`mapper/` 空包 | 是 | 第1天放 `.gitkeep` 的价值在此体现 |
| 删除已不再为空的 `src/test/java/.gitkeep` | 是 | 目录已有真实内容，占位文件失去意义 |
| 把 `maven.compiler.release=17` 加回 pom | 是 | 该属性曾被手动删除；实测父 POM 已推导为 17（功能无影响），但清单 §七.3 要求显式声明，为避免"文档与实现不一致"而补回 |
| 未实现 Service / Controller | 是 | 阶段一指导第3天明确禁止 |

---

## 发现的问题

1. **`Connection refused` 导致首次测试失败（高频环境坑）。**
   首次 `mvn test` 报 `MyBatisSystemException → CannotGetJdbcConnectionException → ConnectException: Connection refused`，根因是 **Docker Desktop 引擎未启动**（`npipe:////./pipe/dockerDesktopLinuxEngine` 找不到），与代码和密码无关。
   本次沉淀的**分层判据**（可直接用于后续排错）：

   | 报错关键字 | 含义 | 排查方向 |
   |---|---|---|
   | `Connection refused` | 网络层不通 | 容器是否运行、端口映射、防火墙 |
   | `Access denied for user` | 认证失败 | 用户名/密码不一致 |
   | `Unknown database` | 库不存在 | 库名拼写、库是否创建 |
   | `Unknown column` | 代码与表结构不一致 | 实体字段 vs 表字段 |

   另注：首次失败时 3 个用例中的"Bean 注入"用例**是通过的**（它不访问数据库），恰好证明 `@MapperScan` 已生效、问题纯在连接层。

2. **"应用启动成功" ≠ "连上数据库"。**
   `spring-boot:run` 日志中**没有** `HikariPool` 行，因为 HikariCP 懒加载：启动过程无数据库访问就不建连接池。因此第3天的验收必须包含一次真实查询，不能只看启动日志。

3. **IDEA 的 Project SDK 是 25，与项目约定（17）不一致。**
   已要求用户在 IDEA 中把 Project SDK / Maven Runner JRE 统一改为项目内 JDK 17。风险：Spring Boot 3.3.13 官方支持到 Java 23，25 属未保证组合，会出现"命令行能过、IDEA 里挂"的假故障。

4. **`docker compose up -d` 只重启未重建，SQL 挂载点变更尚未生效。**
   输出为 `Container smartticket-mysql Started` 而非 `Recreated`，说明新挂载（`../../06_项目源代码/sql`）还没应用。数据卷已初始化，当前不影响使用；如需生效要用 `--force-recreate`。

5. **密码存在两处，必须保持一致。**
   Docker 的 `.env`（容器初始化/healthcheck 用）与应用 `application-local.yml`（Spring 连库用）各存一份，指向同一实例。这是两套配置系统，无法合并，只能靠约定；任何一处改动都要同步。

6. **手动改动 pom 引入的一致性风险。**
   会话中发现 `maven.compiler.release=17` 被手工删除。功能上父 POM 会推导出同样的值，但与 `Maven依赖清单` §七.3 的明文规则冲突。已补回并记录，避免"文档说一套、实现另一套"。

---

## 下一步行动

- [x] Step 1 依赖引入 + 清单回填（`已引入` / 3.5.17）
- [x] Step 2 数据源与 MyBatis-Plus 配置
- [x] Step 3 用户填写 `application-local.yml` 密码
- [x] Step 4 两个实体类
- [x] Step 5 两个 Mapper + `@MapperScan`
- [x] Step 6 启动验证（Tomcat 8080 / profile local / Java 17.0.20.1）
- [x] Step 7 `mvn test` 3/3 通过，SQL 日志证明逻辑删除生效
- [ ] Step 8 用户在 IDEA 中逐行评审本次改动的 8 个文件
- [ ] IDEA 中 Project SDK 与 Maven Runner JRE 统一为 JDK 17
- [ ] 建议 Docker Desktop 开启开机自启，避免再次出现 `Connection refused`
- [ ] 需要时执行 `docker compose up -d --force-recreate` 让 SQL 新挂载点生效
- [ ] 第4天：统一返回格式 `Result<T>` + 全局异常处理
- [ ] 第12天：补充更完整的单元测试（当前仅 3 个冒烟用例）

---

## 是否需要更新AI约束

- **否**（本次无新增禁止事项）
- 可选建议（非阻塞）：
  1. `06_禁止事项清单.md` 增加"禁止在会被提交的文档/配置中记录真实密码，统一用占位符"；
  2. `02_技术栈约束.md` 明确"构建与运行统一使用 JDK 17，IDEA 的 Project SDK 与 Maven Runner JRE 必须一致"；
  3. 阶段验收规则补充"涉及数据访问的阶段，验收必须包含至少一次真实查询，不得仅以'启动成功'判定通过"。

---

## 面试价值

- 是否可作为踩坑案例：**是**
  1. `Connection refused` vs `Access denied` 的分层判据——一眼定位问题在"服务没起"还是"密码不对"，比盲试配置高效得多；
  2. "Spring Boot 启动成功但连不上数据库"——因为连接池懒加载，这是个典型的假阳性验收陷阱；
  3. MyBatis-Plus 3.5.9 把分页依赖拆包，不引 `mybatis-plus-jsqlparser` 就找不到 `PaginationInnerInterceptor`。
- 是否可作为技术决策案例：**是**
  1. 为什么用 BOM 而不是逐条写版本号（starter 与 jsqlparser 版本必须一致）；
  2. 为什么 `IdType` 必须显式设为 `AUTO`（默认雪花算法会绕开 MySQL 自增）；
  3. 为什么日志表实体刻意不带 `deleted`/`updated_at`（审计数据 append-only，字段会诱导误用）。
