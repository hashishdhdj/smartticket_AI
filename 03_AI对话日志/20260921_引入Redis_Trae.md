# AI对话日志

## 基本信息

| 项目 | 内容 |
|---|---|
| 日期 | 2026-09-21 |
| 主题 | 阶段1 第8天：引入 Redis |
| 使用模型 | Trae |
| 当前阶段 | 阶段1：环境搭建与最小骨架（第2周开篇） |
| 对话目的 | 把 Redis 接入项目（依赖 / 连接配置 / 读写可用性），**不实现缓存业务**（留第9天） |

---

## 输入内容

用户要求「介绍一下第八天的工作」。交底后给出 3 项决策：

| 决策项 | 用户选择 |
|---|---|
| 操作模板 | **StringRedisTemplate**（第9天缓存由 Service 手动 JSON 序列化） |
| 「能写入并读回 key」的验收方式 | **JUnit 测试类**（进 `mvn test` 流水线） |
| 测试顺序 | 第7天全量测试已完成，直接进入第8天 |

执行依据为 `01_项目说明/阶段一指导.md` 第8天：加依赖 / 配连接 / Docker 启动 Redis / 编写配置类；验收三条：容器启动成功、项目可连、可写入并读取测试 key。

---

## AI输出摘要

### 1. 产出文件

| 文件 | 动作 |
|---|---|
| `pom.xml` | 修改（+ `spring-boot-starter-data-redis`） |
| `src/main/resources/application.yml` | 修改（+ `spring.data.redis` 连接段） |
| `src/test/java/com/smartticket/support/RedisConnectivityTest.java` | 新增（2 个用例） |
| `09_项目依赖与资源/01_依赖清单/Maven依赖清单.md` | 修改（Redis 回填为已引入；commons-pool2 标注不引入） |

**未新增** `config/RedisConfig.java`，理由见下节。

### 2. 与指导清单的偏离（需留痕）

指导第8天任务第 4 项写的是「编写 Redis 配置类」。本次**刻意不写**：

- 选择 `StringRedisTemplate` 后，Spring Boot 的 `RedisAutoConfiguration` 已经提供了该 Bean，且默认 `StringRedisSerializer` 让 key/value 在 `redis-cli` 中直接可读；
- 此时再写一个 `RedisConfig` 只能是空壳（或把自动配置重抄一遍），属于过度工程；
- 配置类真正有价值的场景是「用 JDK 序列化的 `RedisTemplate<String,Object>`」或「需要自定义连接池」——本阶段两者都不需要。

**替代方案**：配置类真正的风险点不是"没写"，而是"前缀写错却没发现"，因此用一个断言用例守住它（见关键技术判断 4）。

### 3. 关键技术判断

1. **前缀是 `spring.data.redis`，不是 `spring.redis`**。Spring Boot 3.x 已废弃 Boot 2 的前缀，且**写错不会报错**——只会静默使用默认值（`localhost:6379`），造成"配置看着没问题、实际没生效"。这是本任务最容易踩且最难自查的坑。
2. **`StringRedisTemplate` 而非 `RedisTemplate<String,Object>`**（用户决策）。后者若不配 Jackson 序列化器，会落到 JDK 序列化，`redis-cli` 里是乱码二进制；若配了 Jackson，还必须额外注册 `JavaTimeModule` 才能处理 `TicketVO` 里的 `LocalDateTime`（第9天会立即撞上）。前者没有这些隐藏依赖。
3. **不引入 `commons-pool2`**。默认 Lettuce 用共享连接、无需连接池；阶段1 无并发压力，引池属过度工程。已在依赖清单中把该项从「待引入」改为「**不引入**」并注明理由（避免后续误以为是遗漏）。
4. **`timeout: 3s` 是显式声明的非默认值，它的作用是让"配置是否生效"可断言**。若沿用默认（`RedisProperties.timeout` 初始为 `null`），断言 `getHost()`/`getPort()` 毫无意义——因为默认值恰好也是 `localhost`/`6379`，前缀写错时断言照样通过（与第7天发现的"JsonPath 过滤断言假通过"是同一类问题）。用一个非默认值，这个用例才真正有守护能力。
5. **测试用独立 key 前缀 `smartticket:test:` 且用完即删**。第9天会写业务缓存 key，测试若不做隔离与清理，`DBSIZE` 会残留垃圾键、干扰缓存命中判断。
6. **用 `@SpringBootTest` 全上下文而非 Web 切片**。本测试的目的就是验证"应用进程能不能真的连上 Redis"，切片环境（`@WebMvcTest`）不会建立真实连接，达不到验证目的。

### 4. 验证证据

**自动化回归**：

```text
$ mvn clean test
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.smartticket.support.ApiContractTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.smartticket.support.RedisConnectivityTest
[INFO] Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time: 31.837 s
```

（第7天为 51 个用例，本次 +2）

**容器侧核对**：

```text
$ docker exec smartticket-redis redis-cli PING
PONG
$ docker exec smartticket-redis redis-cli DBSIZE
0
$ docker exec smartticket-redis redis-cli INFO keyspace
# Keyspace
```

`DBSIZE = 0` 是**预期结果**（测试用例用完即删），不能作为"没写进去"的证据——"写入成功"由用例中的 `get()` 读回断言证明。

**依赖版本解析**：

```text
$ mvn dependency:tree -Dincludes="org.springframework.data:spring-data-redis,io.lettuce:lettuce-core,org.apache.commons:commons-pool2"
[INFO] \- org.springframework.boot:spring-boot-starter-data-redis:jar:3.3.13:compile
[INFO]    +- io.lettuce:lettuce-core:jar:6.3.2.RELEASE:compile
[INFO]    \- org.springframework.data:spring-data-redis:jar:3.3.13:compile
```

`commons-pool2` 未出现在依赖树中，证明"不引入"的决策已落地。

---

## 采纳情况

代码与配置已落地并通过全量测试；依赖清单已回填。

**待用户确认**：`RedisConnectivityTest` 放 `support` 包、以及"不写 RedisConfig"的偏离说明是否需要调整措辞。

---

## 发现的问题

1. **依赖清单存在口径不一致**：`Maven依赖清单.md` 「〇、引入阶段与范围约束」表中把 Redis 归到**阶段3（工程化）**，但第二节表格与 `阶段一指导.md` 第8天都写明是**阶段1 第2周**引入。两处矛盾，已在本日志登记，待确认是否统一为"阶段1 第2周"。
2. **`redis-cli` 无法直接证明"是应用写的"**：测试用完即删，容器里看不到痕迹。若需眼见为实，可在另一终端 `redis-cli MONITOR` 后重跑 `mvn test -Dtest=RedisConnectivityTest`，即可看到应用进程发来的真实 `SET`/`GET`/`DEL` 命令。
3. **Redis 当前无密码**：`docker-compose.yml` 中 redis 服务未设 `requirepass`。本机开发可接受，但第10天补 Docker Compose 时需决定是否加密码（会连带影响 `application.yml` 与连接配置）。
4. **第7天验收单尚未签署**：第五节「复核（评审与决定）」仍为「待确认」，属第7天遗留，未在第8天处理。

---

## 下一步行动

- **第9天：实现工单详情缓存**。届时必须覆盖四类失效点：查询回填、更新删缓存、**关闭删缓存**、**重开（指派）后删缓存**——最后一项是第5天"允许重开"决策的连带影响，第9天指导清单里只写了「关闭删缓存」，执行时需补齐。
- 第9天需产出决策记录：`01_项目说明/08_决策记录/20260922_工单缓存策略.md`。

---

## 是否需要更新 AI 约束

**建议新增一条**（供 `02_AI约束与规范` 评估）：

> 当指导清单中的任务项经技术判断确认为"不必要的产出"时（如本日的空配置类），不得默默省略——必须显式说明省略理由，并提供等价的替代验证手段。

理由：省略本身是正确的工程判断，但"静默省略"会让指导清单与代码产出的差异在后续阶段变得难以追溯。

---

## 面试价值

1. **"不写代码"也是一种技术判断**：能说清"什么时候配置类该写、什么时候是过度工程"，比会写配置类更能体现分寸感。
2. **配置生效的可验证性**：识别出"默认值与目标值相同 → 断言失去鉴别力"这个陷阱，与第7天修掉的 JsonPath 假通过是同一类思维模式——**断言必须能失败**。
3. **阶段隔离的排错价值**：第8天只做接入、不做业务，使第9天缓存出问题时能立即排除连接层因素。
