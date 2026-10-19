# AI对话日志

## 基本信息

| 项目 | 内容 |
|---|---|
| 日期 | 2026-09-14 |
| 主题 | 阶段1 第1天：创建 Spring Boot 项目骨架 |
| 使用模型 | Trae |
| 当前阶段 | 阶段1：环境搭建与最小骨架 |
| 对话目的 | 在 `06_项目源代码/ticket-service` 下创建可编译、可启动的 Spring Boot 骨架（Maven / JDK 17 / Spring Boot 3.3.x），不实现任何业务功能 |

---

## 输入内容

用户原始任务：

```
1. 在 06_项目源代码/ticket-service 下创建 Spring Boot 项目。
2. 使用 Maven。
3. JDK 17。
4. Spring Boot 3.3.x。
5. 初始依赖只选：Spring Web、Validation、Lombok、MySQL Driver、
   MyBatis-Plus（后续手动加）、Spring Boot DevTools（可选）。
```

并给出了建议目录结构（`com.smartticket` 下 11 个包 + `resources/mapper` + `src/test/java`）。

本次生效的约束（来自 `02_AI约束与规范/AI协作总则.md`、`01_项目说明/阶段一指导.md`、`09_项目依赖与资源/01_依赖清单/Maven依赖清单.md`）：

| 约束 | 来源 |
|---|---|
| 只生成骨架和必要配置，不实现任何业务功能 | 阶段一指导 第1天 |
| 不引入 MyBatis-Plus、Redis、RabbitMQ、Spring AI | 阶段一指导 第1天 + Maven依赖清单 〇 |
| `pom.xml` 必须显式声明 `project.build.sourceEncoding=UTF-8` 与 `maven.compiler.release=17` | Maven依赖清单 七.3 |
| 禁止硬编码密码、密钥、Token | AI协作总则 四 |
| 必须给出验证方式，且必须包含异常与边界场景 | AI协作总则 8 |
| 版本号优先由 Spring Boot 依赖管理统一提供，不逐条手写 | Maven依赖清单 七.2 |

---

## AI输出摘要

### 1. 版本决策

| 项 | 决策 | 依据 |
|---|---|---|
| Spring Boot | **3.3.13** | 在 Maven Central 查询 `spring-boot-starter-parent` 的 3.3 线，3.3.13 为该线最后一个补丁版；不擅自升到 3.4/3.5 大版本 |
| JDK | 17 | 项目章程 6.1；`maven.compiler.release=17` |
| mysql-connector-j | 8.3.0（不手写版本） | 读取本地 `spring-boot-dependencies-3.3.13.pom` 中 `<mysql.version>8.3.0</mysql.version>` 确认 |
| Lombok | 1.18.38（不手写版本） | 同上，`<lombok.version>1.18.38</lombok.version>` |

### 2. 产出文件

| 文件 | 说明 |
|---|---|
| `06_项目源代码/ticket-service/pom.xml` | parent 为 `spring-boot-starter-parent:3.3.13`；依赖仅 Web、Validation、Lombok、mysql-connector-j(runtime)、DevTools(runtime/optional) |
| `.../src/main/java/com/smartticket/TicketServiceApplication.java` | 启动类，无任何接口 |
| `.../src/main/resources/application.yml` | 端口 8080、应用名、`profiles.active: local`、Jackson 时区与日期格式 |
| `.../src/main/resources/application-local.yml` | 本地 profile，仅日志级别，无任何敏感信息 |

包目录：`controller / service / service/impl / mapper / entity / dto / vo / enums / config / common / exception`，以及 `resources/mapper`、`src/test/java` 均已建立，空目录内放 `.gitkeep`。

### 3. 未引入项（有意为之）

| 依赖 | 状态 | 原因 |
|---|---|---|
| MyBatis-Plus | 未引入，pom 内仅留注释 | 计划第3天引入，且必须用 `mybatis-plus-spring-boot3-starter` |
| spring-boot-starter-test | 未引入 | Maven依赖清单 五 中状态为「待引入」，计划第12天随单元测试引入 |
| Redis / AMQP / Spring AI / Actuator | 未引入 | 阶段范围约束禁止提前引入 |
| 健康检查接口 | 未实现 | 第1天要求「不实现任何业务功能」，端口连通性用 404 验证 |

### 4. 验证证据（实测输出）

构建（使用项目内 JDK 17 + Maven 3.9.16，未使用本机全局 JDK 25）：

```text
[INFO] --- compiler:3.13.0:compile (default-compile) @ ticket-service ---
[INFO] Compiling 1 source file with javac [debug parameters release 17] to target\classes
[INFO] BUILD SUCCESS
```

启动：

```text
Tomcat started on port 8080 (http) with context path '/'
Started TicketServiceApplication in 1.483 seconds
```

端口连通性：

```text
GET http://localhost:8080/  →  HTTP 404
```

404 属预期：骨架未实现任何接口，能返回 404 即证明容器已正常监听。验证完成后已停止进程。

---

## 采纳情况

| 内容 | 是否采纳 | 原因 |
|---|---|---|
| Spring Boot 冻结为 3.3.13 | 是 | 满足「3.3.x」约束，且取该线最后补丁版，避免后续反复升级 |
| starter 版本交给依赖管理，不手写 | 是 | 符合 Maven依赖清单 七.2，避免版本漂移 |
| 引入 DevTools（optional） | 是 | 属用户列出的初始依赖范围；`optional=true`，随时可移除 |
| 不引入 test starter | 是 | 用户明确「初始依赖只选」清单中未含测试依赖，留到第12天 |
| 空目录使用 `.gitkeep` | 是 | Git 不追踪空目录，否则目录结构在仓库中不可见（阶段0 已踩过同类坑） |
| `application-local.yml` 只放日志级别 | 是 | 该文件已被 `.gitignore` 忽略，不应承载需要共享的配置 |
| pom 中保留 MyBatis-Plus 引入位置注释 | 是 | 避免第3天漏引或误用旧 starter |

---

## 发现的问题

1. **首次构建报 `zip END header not found`（spring-boot-maven-plugin 3.3.13）。**
   实测该 jar 的 SHA1 与官方值完全一致、ZIP 结构可正常打开（74 个条目），判定为首次下载时的瞬时读写竞争，直接重跑即 `BUILD SUCCESS`。
   后续若复现，处理顺序应为：先校验 SHA1 → 再删除本地仓库对应版本目录重下 → 最后才怀疑 pom。

2. **`application-local.yml` 会被 `.gitignore` 忽略。**
   当前该文件只含日志级别，无影响；但后续若在其中放本机差异配置（如数据源），新克隆的仓库将缺少该文件，需按既定约定补 `application-local.example.yml`。

3. **Maven 未加入全局 PATH。**
   每次构建都必须显式把 `JAVA_HOME` 指向项目内 JDK 17，否则会落到本机全局 JDK 25，与 `release 17` 的意图不符。建议在 IDEA 中把 Project SDK 固定为 JDK 17。

4. **Maven Central 下载速度慢。**
   首次拉取依赖出现 2~30 KB/s 的速率，构建耗时被网络拉长；后续应避免无意义的 `clean`。

5. **本次存在流程偏差（已记录）。**
   骨架代码按用户明确指定的路径直接落入 `06_项目源代码/ticket-service`，未先经 `05_AI输出代码/raw` 暂存，与 AI协作总则 3、4 的标准流程不一致。此处据实记录以保持可追溯，建议从第2天起恢复 raw → reviewed → accepted 流程。

---

## 下一步行动

- [ ] 在 IDEA 中把 Project SDK / Language Level 设为 JDK 17，避免误用本机 JDK 25
- [ ] 第2天：设计 `ticket`、`ticket_log` 两张表并产出初始化 SQL
- [ ] 第3天：引入 `mybatis-plus-spring-boot3-starter` 并回填本清单版本
- [x] 回填 `Maven依赖清单.md`：Web / Validation / Lombok / MySQL 驱动 / DevTools 置为「已引入」，并记录冻结版本
- [ ] 需要共享本地配置时，补 `application-local.example.yml`
- [ ] 后续业务代码恢复 `05_AI输出代码/raw` 暂存流程

---

## 是否需要更新AI约束

- **否**，本次未产生新的禁止事项。
- 可选建议（非阻塞）：在 `02_AI约束与规范/02_技术栈约束.md` 中固化「本项目所有构建与运行命令统一使用项目内 JDK 17 + Maven 3.9.16」，把第3条问题变成硬约束。

---

## 面试价值

- 是否可作为踩坑案例：**是**
  可讲「Maven 报 `zip END header not found`，但 jar 的 SHA1 与官方一致」这一矛盾现象——先验证文件完整性再重试，而不是盲目删仓库或怀疑依赖坐标。
- 是否可作为技术决策案例：**是**
  可讲三点取舍：
  1. 为什么在「3.3.x」范围内选 3.3.13 而不是直接升到 3.5.x（阶段约束优先，大版本升级需单独决策）；
  2. 为什么 starter 依赖不写版本号（交给 BOM，避免版本漂移）；
  3. 为什么用 `.gitkeep` 保住空目录结构（Git 不追踪空目录，结构即约定）。
