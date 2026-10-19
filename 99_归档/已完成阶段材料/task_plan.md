# 任务计划

## 任务目标
- 根据项目章程检查 SmartTicket-AI 所需基础工具、运行环境与关键依赖是否准备到位。
- 对缺失项进行下载补齐，并统一放入项目目录中便于后续复用。

## 阶段计划
| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 建立盘点计划与记录文件 | 已完成 |
| 2 | 核对项目章程与现有依赖清单 | 已完成 |
| 3 | 检查本机工具、命令与运行时环境 | 已完成 |
| 4 | 下载缺失安装包/便携资源到项目目录 | 已完成 |
| 5 | 预拉取中间件镜像（Docker） | 已完成 |
| 6 | 回写结果、补充日志与操作记录 | 已完成 |

## 交付结果
| 交付物 | 位置 |
|---|---|
| 安装包（JDK 17 / Maven / Postman） | `09_项目依赖与资源/02_下载链接与校验/安装包/` |
| 项目内可运行 JDK 17 运行时 | `.../安装包/JDK/jdk-17-runtime/jdk-17.0.20.1+1/` |
| 项目内可运行 Maven 3.9.16 | `.../安装包/Maven/apache-maven-3.9.16/` |
| 下载说明（JDK/Maven/Docker/Postman） | `09_项目依赖与资源/02_下载链接与校验/` |
| 软件版本清单（状态已回写） | `09_项目依赖与资源/软件版本清单.md` |
| AI指导操作记录 | `04_AI指导操作记录/20260914_基础工具与依赖盘点补齐指导.md` |
| AI对话日志 | `03_AI对话日志/20260914_工具与依赖盘点_Trae.md` |

## 目标检查范围
- 基础开发工具：JDK、Maven、Git、Docker Desktop、Docker Compose、IDE、Postman/Apifox、Markdown 编辑器、TRAE
- 运行依赖：MySQL、Redis、RabbitMQ、PostgreSQL、pgvector
- 构建与框架目标版本：Spring Boot、Spring AI、MyBatis-Plus、测试依赖

## 决策与原则
- 优先检查“是否已可用”，再决定是否需要下载。
- 下载安装资源统一放入 `09_项目依赖与资源` 下的可追踪目录。
- 不记录真实密钥、密码、Token。
- 如某些软件无法通过命令行可靠判断，则至少检查常见安装路径和可执行文件。

## 错误记录
| 问题 | 尝试 | 处理 |
|---|---|---|
| `planning-with-files` 模板路径不存在 | 读取模板文件失败 | 改为根据技能说明手工创建计划文件 |
| `mvn` 命令不存在 | 检查命令与 winget 源 | winget 无官方 Apache Maven 包，改用 Apache dlcdn 官方源下载 |
| `mvn -v` 报 JAVA_HOME 未正确定义 | 直接执行 Maven | 显式设置 `JAVA_HOME` 指向项目内 JDK 17 |
| Docker CLI 无法连接引擎 | `docker info` 失败 | 启动 Docker Desktop 主程序，等待约 60 秒 |
| `Start-Service com.docker.service` 失败 | 尝试以服务方式启动 | 放弃该方式，统一通过 Docker Desktop 启动引擎 |
