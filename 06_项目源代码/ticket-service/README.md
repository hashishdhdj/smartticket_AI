# ticket-service

2026-10-19评审整改：生产源码已按职责拆包，统一错误契约，并修复预算并发死锁。当前本地246单元＋143集成＋22 Python通过，见[整改验收](../../docs/evaluation/20261019-engineering-review.md)与[错误契约](../../docs/api/error-contract.md)。以下涉及旧最终JAR、234/137和阶段四收尾的数字为原公开基线；当前构建产物以整改验收为准。

更新：2026-10-19。SmartTicket-AI的Spring Boot服务，阶段一/二基础、阶段三可靠后端与受限Agent均已有实现；阶段四本地工程验收完成，整体语义验收与本人/专家签署待完成。

## 实现范围

| 模块 | 行为 |
|---|---|
| 工单业务 | 创建、查询、编辑、指派、解决、关闭、日志；可信身份、对象权限、状态机、事务审计 |
| 并发与缓存 | 数据库版本/CAS；可选expectedVersion拒绝旧页面；提交后缓存失效和补偿 |
| 异步分析 | 工单/任务/Outbox同事务；发布确认、幂等消费、租约与重试；AI结果独立保存 |
| Agent | getTicketDetail、getTicketLogs、searchKnowledge、管理员prepareAssignTicket；模型提工具调用，应用校验权限与预算 |
| 人工确认 | 指派提案不写工单；独立确认绑定原管理员、参数、版本、有效期和幂等 |
| RAG | 本地BGE-M3/pgvector、关键词与RRF融合；0.63门槛、来源、模拟范围、needHuman和statusReason |

API Key为演示身份方案；短信、订单支付、附件上传等知识是模拟场景，不是项目真实业务。合法JSON与合法引用不保证每句事实正确；有限补救操作词检查也不是通用语义校验器。

## 技术基线

Java 17、Maven 3.9.16、Spring Boot 3.5.14、Spring AI 1.1.6、MyBatis-Plus 3.5.17；MySQL 8、Redis 7、PostgreSQL 16/pgvector、RabbitMQ。本地嵌入为Ollama BGE-M3 1024维，对话为OpenAI兼容接口。这里记录项目基线，不声称最新版。

Windows脚本优先使用有效JAVA_HOME和PATH中的Maven；缺失时回退项目本地安装目录。只修改当前进程环境，不改系统设置。Docker需要四个后端依赖；本地RAG另需Ollama及已下载的BGE-M3。

## 启动与配置

从项目根目录运行：

```powershell
Set-Location 'D:\SmartTicket-AI'
& '.\06_项目源代码\ticket-service\start.ps1' -CheckOnly
& '.\06_项目源代码\ticket-service\start.ps1' -Mode offline-demo -Offline
```

CheckOnly核对现有配置及四个依赖健康，不启动应用或模型。offline-demo禁用真实模型调用；local-rag模式要求-EmbeddingProvider ollama，只验证本地检索，云端回答关闭，过门槛后返回AI_UNAVAILABLE是预期行为。首次下载Maven依赖去掉-Offline。

首次本机环境按[部署运行指南](../../08_部署与环境/03_scripts/阶段四运行指南.md)配置。源码application-local.yml、phase3-local.properties及Compose .env为私有本机文件，不覆盖已有凭据。隔离集成环境由prepare-phase4.py生成独立随机配置到.tmp；不得删掉配置后继续复用初始化过的卷。

real-model模式可能启用后台分析并产生费用，不作为默认启动。需要真实效果评估时使用stage4-evaluate.py，它现在强制使用已验收公开JAR并核对源码及提示词；不要直接运行开发target中间包冒充最终版。

## 零厂商验收

```powershell
. '.\08_部署与环境\03_scripts\Build-Environment.ps1'
& '.\08_部署与环境\03_scripts\Run-Phase3Tests.ps1' -Offline -Profile unit
& '.\08_部署与环境\03_scripts\Run-Phase3Tests.ps1' -Offline -Profile integration
python '.\08_部署与环境\03_scripts\verify-rag-local-http.py'
python '.\08_部署与环境\03_scripts\run-api-collection.py'
```

集成测试需要已准备的smartticket-stage4隔离环境。本地RAG验收要求当前Ollama配置和最新公开交付记录；没有模型密钥也不自动改用云端嵌入。评估/本地RAG脚本共用18083/19014端口和计量账本，应串行运行。

最新结果：234单元＋137集成全通过；最终源码在Linux新容器空Maven缓存也全通过。Python预算14、DNS 3、评估预检5项通过；同一最终JAR在Windows/Linux启动，接口集合15请求35断言通过。Node集合执行器不是Postman客户端，Linux容器不是远程CI。

## 接口约定与状态

请求携带X-API-Key，由服务端决定身份，请求体creatorId/operatorId不能提升权限。HTTP状态与业务code都要检查：认证、权限、版本和频控可能返回401/403/409/429；不能照旧阶段文档假设所有HTTP均为200。时间按接口真实ISO格式解析。

GET工单保存version，PUT时提供expectedVersion可防止旧页面覆盖；省略仍兼容旧客户端，不能声称所有客户端都具备旧页面保护。Agent和RAG都应检查needHuman/statusReason；HTTP200本身不代表业务目标完成。

最新API与角色契约见[角色矩阵](../../07_测试与质量/02_接口测试/阶段四_角色矩阵与接口说明.md)。历史请求响应及旧失败报告保留，不作为当前调用模板。

## 当前验收边界

截至2026-10-19，当前实习项目功能与本地工程验证完成，可直接由专家查看项目。NW13最终交付版一次真实复验恢复；已知题86/86只是门禁放行，不是准确率。148条作者语义评审、本人演示复述、远程CI及长期稳定性仍未签署；不称生产系统或全面安全证明。

累计外部调用400次，现有额外调用授权已用完；累计费用上限仍为¥10，保守上界约¥2.5129非厂商账单。不擅自提高门禁或自动继续调用。最新事实见[20261019收尾验收](../../07_测试与质量/06_阶段测试报告/20261019_项目收尾验收记录.md)。
