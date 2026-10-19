# 发现记录

## 2026-09-14

### 项目章程抽取的环境与依赖要求
- JDK 17
- Maven 3.8+
- IntelliJ IDEA
- Git
- Docker Desktop
- Docker Compose
- Postman 或 Apifox
- Markdown 编辑器（Typora / VS Code）
- TRAE 或其他 AI Coding 工具
- MySQL 8
- Redis 7
- RabbitMQ 3.x
- PostgreSQL 15+
- pgvector
- Spring Boot 3.3.x
- Spring AI 1.1.x
- MyBatis-Plus

### 当前已知项目文件状态
- 项目根目录已创建：`D:\SmartTicket-AI`
- 已存在软件版本清单文件，但仍是目标版本/待确认状态，尚未完成环境实查。

### 技能使用补充
- 本次任务使用 `planning-with-files` 的工作方式，但技能目录中未提供模板文件，已转为手工创建 `task_plan.md`、`findings.md`、`progress.md`。

### 第一轮环境盘点结果
- `java -version` 可用，但当前默认版本为 **OpenJDK 25.0.2**，不符合章程要求的 **JDK 17**。
- `mvn -version` 无输出，`Get-Command mvn` 结果为 `MISSING`，可判定 **Maven 未就绪**。
- `git --version` 可用：**2.50.1.windows.1**。
- `docker --version` 可用：**29.4.1**。
- `docker compose version` 可用：**v5.1.3**。
- `winget` 可用：**v1.29.290**，可作为下载安装源。

### IDE 与常用工具状态
- IntelliJ IDEA 已安装，命令位置：`D:\JAVA_Study\IntelliJ IDEA 2025.3.3\bin\idea64.exe`
- VS Code 已安装，命令位置：`C:\Users\chars_bulus\AppData\Local\Programs\Microsoft VS Code\bin\code.cmd`
- Postman 命令未发现。
- Apifox 命令未发现。
- `winget list` 显示 **TraeCode (User)** 已安装。
- `winget list` 显示 **Obsidian** 已安装，可作为 Markdown 编辑器替代。

### 已安装软件与章程目标的偏差
- 章程目标要求 **JDK 17**，当前默认 Java 为 25，需要补 JDK 17。
- 章程目标要求 **Maven 3.8+**，当前 Maven 未就绪，需要补齐。
- 章程目标要求 **Postman 或 Apifox**，当前两者均未发现，需要补一个。

### 本机 JDK 排查补充
- `JAVA_HOME` = `C:\Users\chars_bulus\.jdks\openjdk-25.0.2`
- `java` 实际位置：`C:\Users\chars_bulus\.jdks\openjdk-25.0.2\bin\java.exe`
- `C:\Users\chars_bulus\.jdks` 下仅有 `openjdk-25.0.2`，无 17。
- `D:\JDK` 目录存在但为空。
- 结论：本机不存在任何 JDK 17，必须新引入。

### winget 可用包 ID（实测确认为官方 ID）
- JDK 17：`EclipseAdoptium.Temurin.17.JDK`（17.0.20.101）
- Postman：`Postman.Postman`（12.27.7）
- Apifox：`Ruihu.Apifox`（2.8.46）
- Maven：**winget 中不存在官方 Apache Maven 包**（仅搜到 HMCL、M2 Repo Cleaner 等无关条目）

### 下载与校验结果（第二轮）
- 安装包落盘目录：`09_项目依赖与资源/02_下载链接与校验/安装包/`
  - JDK：msi 160.6 MB + zip 182.0 MB
  - Maven：zip 9.0 MB + 官方 sha512 文件
  - Postman：exe 199.5 MB
- Maven SHA512 与 Apache 官方公布值**完全一致**，来源可信。
- 全部下载物 SHA256 已计算并写入对应下载说明文档。

### 运行验证结果
- 项目内免安装 JDK 17 解压成功，`java -version` = `17.0.20.1`（Temurin）。
- 项目内 Maven 3.9.16 在 JDK 17 下验证成功：
  `Apache Maven 3.9.16 / Java version: 17.0.20.1, vendor: Eclipse Adoptium`
- 新发现：`platform encoding: GBK`，非 UTF-8，需在阶段1 的 pom.xml 中显式声明。

### Docker 运行态结果
- Docker Desktop 4.71.0 已安装，但引擎初始未运行。
- 启动主程序后等待约 60 秒，`docker info` 输出 `ServerVersion 29.4.1`，引擎可用。
- 4 个中间件镜像已预拉取就绪：
  `mysql:8.0`、`redis:7`、`rabbitmq:3-management`、`pgvector/pgvector:pg16`
- 决策：镜像不打 tar 存项目目录（体积过大），符合章程「大文件只保留链接与说明」原则。

### 待决策事项
- 软件版本清单实际路径为 `09_项目依赖与资源/软件版本清单.md`，
  但文件头部「存放位置」与章程 4.3 规划均指向 `09_项目依赖与资源/01_依赖清单/软件版本清单.md`，存在不一致。
