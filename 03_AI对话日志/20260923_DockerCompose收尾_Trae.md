# AI对话日志

## 基本信息

| 项目 | 内容 |
|---|---|
| 日期 | 2026-09-23 |
| 主题 | 阶段1 第10天：Docker Compose 收尾（复核 + 补验证 + 凭据加固） |
| 使用模型 | Trae |
| 当前阶段 | 阶段1：环境搭建与最小骨架（第2周） |
| 对话目的 | 完成第10天清单：任务项已由第2天提前完成，本次做复核、补上从未验证的验收项、并加固 Redis 凭据 |

---

## 输入内容

用户要求「把第十天要做的任务整理成一个清单，这次不需要你一下把全部都做了，我和你配合一条一条的去做」；交底后选定范围为 **C1~C6，不做应用容器化**（应用容器化超出 `阶段一指导.md` 的 14 天清单范围）。

清单（用户确认）：

| 编号 | 任务 |
|---|---|
| C1 | 复核第10天 4 项任务 + 4 条验收，写清「已由第2天完成」的事实 |
| C2 | 验证「初始化 SQL 自动执行」 |
| C3 | 决策 Redis 是否加 `requirepass` |
| C4 | 给 Redis 加 healthcheck |
| C5 | 补 Compose 使用说明（启动/停止命令、挂载点与数据卷） |
| C6 | 写对话日志与相关记录更新 |

---

## AI输出摘要

### 1. 最重要的事实：第10天的任务在第2天就完成了

指导第10天要求「创建 compose（MySQL + Redis + 数据卷 + 初始化 SQL 挂载）」，而这份 compose 在第2天已建成，且超出要求：MySQL healthcheck、密码走 `.env` 注入、`utf8mb4` 参数、Redis AOF 持久化、SQL 挂载指向源码目录（避免副本漂移）。

因此本次**没有新建 compose 文件**——「假装有活干」会让第10天的产出与实际不符，这个差异必须显式记录。

### 2. 4 条验收中有 1 条从未被执行过（C1 + C2）

**C1 复核的发现**：前 3 条（compose 启动 / MySQL 可连 / Redis 可连）在第3、5、6、7、8、9 天被反复验证；但第 4 条「初始化 SQL 自动执行」**从未真正跑通**。

证据链：

```text
数据卷 01_docker_mysql-data 创建于   2026-09-14 15:39（北京时间）
SQL 挂载加入 compose 是 第2天（9-15）之后
MySQL 容器重建于                      2026-09-16 13:42
```

MySQL 官方镜像的规则是「`/docker-entrypoint-initdb.d` 中的脚本只在数据目录为空时执行一次」。数据卷 9-14 就存在，SQL 挂载是 9-15 才加的，因此 9-16 之后的每一次容器启动都不会执行脚本。容器日志中也只有 `Entrypoint script ... started`，无任何执行初始化脚本的痕迹。

**结论：`ticket` / `ticket_log` 是当时手动执行 SQL 建出来的，不是自动初始化的产物。**

**C2 的验证方法（不动现有数据卷）**：起一个一次性临时容器挂载同一份 SQL 目录，验完销毁。

验证通过：

```text
[Entrypoint]: Initializing database files
[Entrypoint]: Creating database smartticket
[Entrypoint]: /usr/local/bin/docker-entrypoint.sh: running /docker-entrypoint-initdb.d/01_init_schema.sql
[Entrypoint]: MySQL init process done. Ready for start up.

表：ticket、ticket_log          行数：0（全新库）
字符集：utf8mb4_unicode_ci（两张表）
索引（7 个）：PRIMARY(id)、uk_ticket_no(ticket_no) 唯一、idx_status、idx_creator_id、
              idx_assignee_id、idx_created_at、idx_status_priority(status,priority)
```

**为什么核对索引而不是只核对表名**：如果建表语句被截断或中途报错，表可能已存在但索引缺失。索引全部齐备，才证明脚本**完整执行到底**。这也顺带证明了 compose 的挂载路径 `../../06_项目源代码/sql` 不仅"配置可解析"，而是"真能建出表"。

### 3. 意外发现：第1周验收数据消失，并完成了排查

清理临时容器时核对现有库，发现 `ticket_rows = 0`、`log_rows = 0`——第7天留的 id=70/71 及 5 条日志不在了（第9天时它们还在）。用户明确表示不是自己删的。

**排查用了三条证据区分「重建」与「删除」**：

| 证据 | 读数 | 含义 |
|---|---|---|
| 表 `CREATE_TIME` | 2026-09-14 15:39:13 | 表从未重建（若 `down -v` 过，时间会变） |
| `AUTO_INCREMENT` | `ticket`=164、`ticket_log`=192 | 历史上插入过 163/191 行 → 「老表被删空」，而非「新表」 |
| 容器 `RestartCount` | 0，启动时间线 9-16 → 9-23 连续 | 无异常重建 |

**结论：是 SQL `DELETE` 删的，不是卷或表被重建。**

**用哨兵法排除测试代码的嫌疑**：直插一条 `creator_id = 1`（与 id=70/71 同属主）的哨兵数据，跑完 60 个用例后它**依然存在**——证明两个测试类的清理条件（`creator_id IN (999_998, 999_999)`）覆盖不到非测试数据。

**本次要认领的错误**：第7天我给的那条清理指令写作 `DELETE FROM ticket WHERE id >= 70`，**条件过宽**。它的语义是"删掉 id 不小于 70 的所有工单"，而不是"删掉本次验收的两条"。只要有新数据 id ≥ 70，执行一次就会被连带清空。我当时的标注是「可选、不确定就先别跑」，但破坏性操作给了一个过宽的匹配范围，属指令设计缺陷。正确写法应是 `WHERE id IN (70, 71)` 或 `WHERE creator_id = 1`。

### 4. Redis 凭据加固（C3）

原状态是 MySQL 有密码、Redis 裸奔（`0.0.0.0:6379`），凭据策略不对称。用户选择「加 `requirepass`」，共改 5 处：

| 文件 | 改动 |
|---|---|
| `01_docker/docker-compose.yml` | redis `command` 加 `--requirepass ${REDIS_PASSWORD}` |
| `01_docker/.env` | 新增 `REDIS_PASSWORD`（不入库） |
| `02_env/.env.example` | 新增 `REDIS_PASSWORD=change_me` 示例 |
| `application.yml` | 新增 `spring.data.redis.password: ${REDIS_PASSWORD:}` |
| `application-local.yml` | 填真实值（不入库，与 MySQL 密码同样处理） |

验证：

```text
容器侧：无密码 PING → NOAUTH Authentication required.
        带密码 PING → PONG
应用侧：RedisConnectivityTest + TicketCacheTest → Tests run: 9, Failures: 0, Errors: 0
```

应用侧 9 个用例通过，才证明 `application-local.yml` 里的密码被真正读取（若漏配会直接 NOAUTH 全红）。

### 5. Redis healthcheck（C4）

与 MySQL 对齐，新增 `healthcheck`；密码经 **`REDISCLI_AUTH` 环境变量**传入，而不是 `redis-cli -a`——后者每次探活（每 10 秒）都会往容器日志写一条密码明文警告。

**验证 healthcheck 是否"有失败的能力"**（避免又一个"恒真断言"）：

```text
错误密码 → AUTH failed: WRONGPASS        exit=1   ← 会失败
正确密码 →                                exit=0   ← 通过
```

容器状态：`smartticket-redis | Up 25 seconds (healthy)`。

### 6. 产出文件

| 文件 | 动作 |
|---|---|
| `08_部署与环境/01_docker/docker-compose.yml` | 修改（redis 加 `requirepass`、`REDISCLI_AUTH`、healthcheck） |
| `08_部署与环境/01_docker/.env` | 修改（+ `REDIS_PASSWORD`，不入库） |
| `08_部署与环境/02_env/.env.example` | 修改（+ `REDIS_PASSWORD` 示例） |
| `08_部署与环境/01_docker/README.md` | 新增（服务清单、启停命令、数据卷与初始化 SQL 机制、连接方式、已知限制） |
| `application.yml` / `application-local.yml` | 修改（Redis 密码占位 / 真实值） |
| `01_项目说明/07_阶段验收单/阶段1_第1周验收单.md` | 修改（遗留问题第 8 项：验收数据已清空） |

---

## 采纳情况

C1~C6 全部完成。全量回归 `Tests run: 60, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

**记录一处未完全定位的事实**：id=70/71 的删除「是谁、在什么时间执行的」无法确证（MySQL 未开 general log，无审计痕迹）。可以确证的只有：**不是表/卷重建、不是测试代码**，且只可能是一条范围条件的 `DELETE` 语句。如实登记，不做无根据的推断。

---

## 发现的问题

1. **我给出的清理指令条件过宽**（`WHERE id >= 70`），已在上文认领。后续凡涉及删除的指令，一律按精确 id 或按属主限定。
2. **Docker 引擎第 4 次未启动**（前三次在第3、5、6天）。本次表现是 `mvn test` 大批失败 + `docker compose` 报 `cannot find the file specified`。已建议开启 Docker Desktop 开机自启。
3. **同批文件编辑中 import 行未落盘（第 3 次遇到）**：本次 `TicketCacheTest` 与 `TicketApiTest` 的 Redis import 各失败一次（第9天记录过同类问题）。应对方式已固化为"批量编辑后用 grep 复核所有新增符号"。
4. **测试数据隔离可以更彻底**：当前靠 `creator_id` 约定隔离（实测有效），但若让测试连独立的库（如 `smartticket_test`），可从物理层杜绝交叉。建议在第12天「编写基础单元测试」时一并考虑。
5. **Redis / MySQL 端口绑定 `0.0.0.0`**：已写入 README 的已知限制。本机开发可接受，将来在有公网 IP 的机器上使用前必须收紧。
6. **应用未容器化**：`ticket-service` 仍需 Maven / IDEA 启动。第10天未要求，留待阶段3；届时需把 `spring.data.redis.host` 由 `127.0.0.1` 改为服务名 `redis`。

---

## 下一步行动

- **第11天：编写接口文档 + Postman 集合**。Postman 集合在第6/7天已建（6 组 22 个请求），第11天主要补的是**接口文档**（每个接口的路径/方法/参数/示例/错误码/备注）。
- 阶段1 收尾时需处理「缓存故障无降级」（第9天已决定纳入收尾）。

---

## 是否需要更新 AI 约束

**建议新增一条**（供 `02_AI约束与规范` 评估）：

> 任何涉及数据删除的操作指令，匹配条件必须精确到具体行（精确 id 或明确的属主字段），禁止使用 `id >= N`、`id > N` 这类随数据增长会改变语义的范围条件；且指令中必须写明"执行前请确认影响行数"。

理由：本次 id=70/71 消失事件中，唯一能造成该结果的已知途径，正是我在第7天给出的一条范围过宽的清理指令。破坏性指令的匹配范围必须与操作意图严格对齐。

---

## 面试价值

1. **"配置解析通过" ≠ "功能真的可用"**：`docker compose config` 通过只说明语法正确；C2 用一次性容器真正跑了一遍初始化，才把这条验收从"看着没问题"变成"有证据"。这和第8天的"断言必须能失败"是同一种思维。
2. **用证据链回答"数据去哪了"**：`CREATE_TIME`（是否重建）+ `AUTO_INCREMENT`（删空 vs 新建）+ `RestartCount`（容器是否异常重建），三条元数据把可能性收敛到一个。再叠加哨兵数据法排除测试代码嫌疑——**排查不是猜，而是逐步排除**。
3. **healthcheck 也要验证其"可失败性"**：一个只返回 0 的探活命令会让编排系统永远认为服务健康。用错误密码跑一次，确认它 exit=1。
4. **凭据一致性**：Redis 起初无密码而 MySQL 有，这种"不对称"在真实项目里很常见，也常被忽略。第10天把它统一到"真实值只在 `.env` 与 `application-local.yml`，仓库内只留占位"这一条规则上。
