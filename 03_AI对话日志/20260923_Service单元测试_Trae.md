# AI对话日志

## 基本信息

| 项目 | 内容 |
|---|---|
| 日期 | 2026-09-23 |
| 主题 | 阶段1 第12天：编写基础单元测试（JUnit 5 + Mockito） |
| 使用模型 | Trae |
| 当前阶段 | 阶段1：环境搭建与最小骨架（第2周） |
| 对话目的 | 补上 Service 层的**纯单元测试**，让核心业务分支可以脱离 Docker 秒级验证 |

---

## 输入内容

用户要求「开始第十二天的测试」。交底时如实说明：指导里第12天的 4 项任务，有 3 项已具备——

| 指导要求 | 现状 |
|---|---|
| 状态机单元测试 | 已有（`TicketStatusTest` 9 个用例，第5天写） |
| Service 层单元测试 | **缺失** ← 唯一缺口 |
| JUnit 5 + Mockito | JUnit 5 已用；Mockito 此前仅以 `@MockBean` 形式出现在切片测试中 |
| 不依赖真实数据库和 Redis | 原有 45 个集成测试全部依赖真实 MySQL/Redis |

用户选择覆盖范围为「**关键分支全覆盖**」。

---

## AI输出摘要

### 1. 产出文件

| 文件 | 动作 |
|---|---|
| `src/test/java/com/smartticket/service/impl/TicketServiceImplTest.java` | 新增（16 个用例） |

**未改动任何现有测试类**（60 个用例保持原样）。

### 2. 覆盖清单

| 方法 | 覆盖的分支 |
|---|---|
| `create` | 正常创建（编号格式 / 状态固定 / 写日志）；未传优先级显式写 MEDIUM；编号冲突重试后成功；**连续冲突 3 次返回 10004** |
| `getById` | 缓存命中（断言 `selectById` 从未调用）；未命中回源 + 回填；**缓存存了非法 JSON 时清 key 后回源**；工单不存在 → 10003 且不写缓存 |
| `page` | 非法 status → 10002，且不执行分页查询 |
| `update` | 用 `UpdateWrapper` 显式 set 全部字段（置空字段也出现在 SET 子句）；清除缓存；不存在 → 10003 |
| `assign` | 待分配 → 处理中 + 清缓存；处理中 → 转派（写回参数里状态仍为 PROCESSING）；已关闭 → 10005 且不修改数据 |
| `close` | 待分配可关闭 + 清缓存；处理中 → 10001 且提示含起止状态 |

### 3. 两个真实踩坑（对后续写单元测试有参考价值）

**坑 1：Mockito 对 MyBatis-Plus 的 `insert` 产生重载歧义**

`BaseMapper` 同时存在 `insert(T)` 与 `insert(Collection<T>)`，写 `insert(any(Ticket.class))` 编译报「对 insert 的引用不明确」。

即使 `any(Ticket.class)` 静态类型已是 `Ticket`，编译器在泛型推断阶段仍判定两个重载都适用。**解法：用显式类型见证** `insert(ArgumentMatchers.<Ticket>any())`。

同一问题也出现在 `ticketLogMapper.insert(any())` 上——`any()` 无参形式的返回类型完全依赖推断，必然歧义。

**坑 2：纯单元测试里 MyBatis-Plus 的 lambda 构造器找不到 `TableInfo`**

`update` / `assign` / `close` 三个方法与 4 个用例全部报：

```text
MybatisPlusException: can not find lambda cache for this entity [com.smartticket.entity.Ticket]
    at AbstractLambdaWrapper.tryInitCache
    at LambdaUpdateWrapper.set
```

原因：`Wrappers.lambdaUpdate(Ticket.class).set(Ticket::getStatus, ...)` 需要把方法引用解析成列名，依赖实体的 `TableInfo` 缓存；这个缓存在正常运行时由 MyBatis 启动流程建立，**纯单元测试没有该流程**。

解法（在 `@BeforeEach` 中显式初始化，并用 null 判断避免重复注册）：

```java
if (TableInfoHelper.getTableInfo(Ticket.class) == null) {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Ticket.class);
}
```

**这条说明"Service 层完全脱离 MyBatis 做单元测试"是有边界的**：只要被测代码用了 lambda 条件构造器，就必须给 MyBatis-Plus 一点上下文。

### 4. 其他技术判断

- `ObjectMapper` 用**真实实例**而非 mock：它无副作用，且缓存分支的断言依赖真实序列化结果。必须注册 `JavaTimeModule`，否则 `TicketVO` 中的 `LocalDateTime` 序列化会失败（与第9天线上路径遇到的是同一个坑）。
- `assertTrue(jsonCaptor.getValue().contains("ST-..."))` 断言的是回填内容的**具体值**，而不是"set 被调用过"——后者无法区分写入的是不是正确数据。
- 转派用例断言的是 `UpdateWrapper` 的 `paramNameValuePairs` **包含新处理人且状态仍为 PROCESSING**；最初我写成了 `assertEquals("PROCESSING", ticket(...).getStatus())`，这是自己和自己比较的无效断言，已修正。

---

## 采纳情况

16 个用例全部通过，全量回归 76 个用例通过。

---

## 验证证据

**单类运行**（纯单元，不需要 Docker）：

```text
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.904 s
[INFO] BUILD SUCCESS
```

运行日志印证各分支被真实触发：

```text
WARN  工单编号冲突，第 1 次尝试 | ticketNo=ST-20260923-...-1386
WARN  工单编号冲突，第 2 次尝试 | ticketNo=ST-20260923-...-D0D7
WARN  工单编号冲突，第 3 次尝试 | ticketNo=ST-20260923-...-43C7
WARN  工单详情缓存不可解析，清除后回源 | id=1
```

**全量回归**：

```text
[INFO] Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

用例分布（76 = 60 + 16）：

| 类型 | 测试类 | 用例数 | 依赖 Docker |
|---|---|---:|---|
| 单元 | `TicketServiceImplTest`（本次新增） | 16 | **否** |
| 单元 | `TicketStatusTest` / `TicketPriorityTest` / `ErrorCodeTest` | 9 / 2 / 4 | 否 |
| 切片 | `ApiContractTest` | 5 | 否（用 `@MockBean`） |
| 集成 | `TicketApiTest` / `TicketCacheTest` / `TicketMapperTest` / `RedisConnectivityTest` | 28 / 7 / 3 / 2 | 是 |

**单元测试的反馈速度对比**：本类 2.9 秒 vs 全量回归约 25 秒（其中大部分是集成测试的 Spring 上下文启动与真实库交互）。

---

## 发现的问题

1. **"完全脱离 MyBatis 的 Service 单元测试"有边界**：lambda 条件构造器强制要求 `TableInfo` 初始化。若将来想做到零 MyBatis 依赖，需要改造 Service 中条件构造器的用法（例如改用字符串列名的普通 `UpdateWrapper`），但那样会牺牲类型安全——**不建议为测试而改生产代码**，当前的手动初始化已足够。
2. **`mvn` 不在 Trae 新建终端的 PATH 中**（项目内 Maven 的 bin 加在用户级 Path，新终端未继承）。本日多次需要显式 `$env:PATH += "...\apache-maven-3.9.16\bin"`。建议把项目 Maven 路径固化到项目内的脚本或文档中。
3. **编译期出现 "使用了未经检查或不安全的操作" 警告**（`ArgumentCaptor.forClass(Wrapper.class)` 的泛型转换所致）。不影响功能，属已知的 Mockito + 泛型固有现象。

---

## 下一步行动

- **第13天：边界测试 + 缺陷记录**。指导给出的边界清单里有两条与之前登记的遗留问题直接对应：
  - 「**Redis 不可用时查询详情**」——正是第9天决定「阶段1 收尾处理」的缓存降级问题，第13天正好做它的验证与修复
  - 「**MySQL 不可用时创建工单**」——第10天 C2 期间真实遇到过（Docker 引擎挂掉时全部请求 50000），可复现并记录缺陷
  - 其余：创建缺参数、标题过长、查/更新不存在的工单、非法状态流转、重复关闭、指派不存在的处理人
- 产出物：`07_测试与质量/05_缺陷记录/缺陷登记表.md`、`07_测试与质量/06_阶段测试报告/阶段1测试报告.md`

---

## 是否需要更新 AI 约束

本日无新增建议（前几日提出的三条仍在待评估状态：清理指令需精确匹配、副本落盘后需 grep 复核、配置变更需实测验证生效）。

---

## 面试价值

1. **能说清"单元测试与集成测试的分工"**：不是"单元测试更高级所以全面替换集成测试"，而是各有边界——编号连续冲突 3 次、缓存中存脏 JSON 这类分支用集成测试几乎造不出来，而"SQL 与索引是否真能建对"只有集成测试能验。
2. **`TableInfo` 这个坑暴露了 mock 的代价**：把 Mapper mock 掉不等于"被测代码不再依赖 MyBatis"，只要用了 lambda 构造器，依赖依然存在。理解这一点，才能准确判断一个类是"可单测"还是"必须集成测"。
3. **无效断言的自我识别**：`assertEquals("PROCESSING", ticket(...).getStatus())` 这种"自己和自己比"的断言在 review 时很容易被放过，它与第7天的 JsonPath 假通过、第10天 healthcheck 恒真是同一类问题——**断言必须能失败**。
