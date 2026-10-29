# SmartTicket-AI

用于 Java 后端与 Agent 实习面试的模块化单体：工单业务、可靠异步 AI 分析、本地 RAG 和受限工具调用。

**新工单 Agent 优先接待（2026-10-29）：** 新增自动排队、追问续跑、客户确认解决、人工待办与接管围栏；只读 Agent 不代客户确认、不自行执行退款等业务。先迁移 `09_agent_reception.sql`，启动时加 `-AgentReception`，详见[接待流程与接口](docs/api/agent-reception.md)。未启用接待 profile 时保留原创建与异步分类流程；下方历史评测不代表新接待流程已做真实模型质量复验。

[当前分支GitHub CI](https://github.com/hashishdhdj/smartticket_AI/actions/workflows/verify.yml?query=branch%3Acodex%2Fquality-closure) · [本轮发布前核验](docs/evaluation/20261020-github-delivery.md)

**快速导航：** [后端源码](06_项目源代码/ticket-service/src/main/java/com/smartticket) · [启动与配置](06_项目源代码/ticket-service/README.md) · [架构与设计](docs/architecture/README.md) · [接口与角色](07_测试与质量/02_接口测试/阶段四_角色矩阵与接口说明.md) · [测试与效果边界](docs/evaluation/README.md)

当前保留原工程路径以兼容启动、评测和部署脚本；日常阅读从本页与 [docs 导航](docs/README.md)开始。过程记录按日期保留，作为协作与失败追踪资料。

真实模型自主图谱Agent验证（2026-10-28）：两道经验正例由DeepSeek自主选择图谱工具，真实Java执行、结果回传、最终引用闭环均完成；撤回后新来源边界通过。普通规则题仍出现双检索后Token预算停止，另有能力范围表述和重复澄清问题，不能说五题全通过。新增8次真实调用，累计451次，保守费用上界约¥2.79；116项脚本保护测试通过，Java/JAR未变。见[结果与问题清单](01_项目说明/20261028_真实Agent图谱工具验证_结果与问题清单.md)及[完整证据汇总](docs/evaluation/20261028-agent-graph-real-summary.json)。

简单任务人工升级限制（2026-10-27）：新增共享handoff决策，区分直接回答、向用户澄清与真正人工事项；无原因升级最多纠正一次，纠正期间不开放新工具。331单元、270集成、97脚本检查通过，新增厂商调用0。见[当前契约](docs/api/handoff-policy.md)和[验证指南](01_项目说明/20261027_简单任务转人工限制_完成说明与验证指南.md)。这次是针对已知问题的工程修复，真实模型误转人工率尚未重测。

G5对照与真实生成更新（2026-10-27）：307单元、260集成、97脚本通过；12题A/B/C真实本地BGE对照及CI离线链路各52次协议请求通过。10个正例三组目标来源均命中，本批未显示图谱检索增益。经授权36次真实DeepSeek已执行，累计443次、保守费用上界约¥2.74；36/36格式与引用契约通过，人工语义复核仍待完成。见[结果与复跑指南](01_项目说明/20261027_G5对照验收与真实生成_结果及复跑指南.md)、[工程证据](docs/evaluation/20261027-g5-engineering.json)及[真实回答](docs/evaluation/20261027-g5-real-generation.json)。CI以当前分支Actions为准；B是评估适配器，不能称三个完整Agent实验。

Agent图谱工具更新（2026-10-23）：G4已完成searchExperienceGraph、同来源2跳路径、完整条件/反例上下文、引用与最终版本校验；307单元、260集成、68脚本通过。新JAR真实HTTP/MQ/本地BGE/PG演示5个Agent场景、10次本地协议请求通过，新增厂商调用0。对话使用离线确定性替身，不代表真实生成质量；G5对照与语义评估仍待完成。见[工具契约](docs/api/experience-graph-search.md)、[完成说明](01_项目说明/20261023_G4Agent图谱检索与来源校验_完成说明及操作指南.md)和[本轮证据](docs/evaluation/20261023-g4-agent-experience-graph.json)。本次为本地验收，尚未提交或推送远程CI。

真实知识图谱更新（2026-10-23）：G3已完成PG节点、关系、独立来源证据、图文原子发布、撤回墓碑、回执恢复与历史补建；283单元、240集成、62脚本及真实HTTP/MQ/本地BGE/PG验收通过，新增厂商调用0。见[接口与复跑](docs/api/experience-graph.md)、[完成说明](01_项目说明/20261023_G3知识图谱与发布生命周期_完成说明及操作指南.md)及[本轮证据](docs/evaluation/20261023-g3-experience-graph.json)。此段为G3完成时记录，Agent接入见上方G4更新。

经验结构与去重更新（2026-10-23）：G2结构化经验卡、并发精确去重、独立来源、失败反例、知识版本绑定与撤回修订已完成；271单元、218集成、62脚本及12条案例的真实本地文本检索验收通过，新增厂商调用0次。见[接口与复跑](docs/api/experience-cards.md)、[完成说明](01_项目说明/20261023_G2经验卡与去重_完成说明及操作指南.md)和[验收记录](docs/evaluation/20261023-g2-experience-cards.json)。图谱节点/关系和Agent图谱工具仍待G3/G4；本次为本地验收，尚未推送远程CI。

一线经验采集更新（2026-10-23）：G1员工记录、结果反馈和纠错关联已实现，默认关闭；260单元、196集成、62脚本及12条合成案例的真实HTTP/MySQL验收通过，新增模型调用0次。见[接口与启用方式](docs/api/staff-experience.md)和[完成说明](01_项目说明/20261023_G1一线记录与反馈_完成说明及操作指南.md)。知识图谱构建和Agent图谱工具仍待后续阶段实现；此处为本地验收，未更新远程CI。

相似问题筛选更新（2026-10-20）：已按逐条向量分筛掉低分推荐，蓝灯和PUT示例均从3条变为1条；260单元、171集成、62脚本及新包本地检索重放通过，新增付费调用0次。见[修复与复跑命令](docs/evaluation/20261020-rag-recommendations.md)。

真实回答复验更新（2026-10-20）：0.55策略的固定8题已执行，4题生成、3题模型拒答、1题门槛拦截；新增7次、累计407次，作者评分仍待完成。见[实际结果与查看命令](docs/evaluation/20261020-rag-generation-result.md)。

当前状态（2026-10-20）：第一轮工程整改已验收并合入 main，R05动态知识的本地与远程CI均通过；`codex/quality-closure` 继续推进R06。新增新措辞检索实测、版本绑定复核工具和四链演示指南；作者已复核1/148，整体质量签署仍待完成。见[R06进展](docs/evaluation/20261020-quality-closure.md)。

第一轮完成职责拆包、持久化分离、错误契约统一和预算死锁修复，见[工程验收](docs/evaluation/20261019-engineering-review.md)。本批动态知识增加结构化解决记录、人工审核、可靠增量索引和撤回；252 单元 + 171 集成 + 22 Python 通过，实际 JAR/HTTP/RabbitMQ/本地 BGE-M3 闭环通过，见[动态知识验收](docs/evaluation/20261019-dynamic-knowledge.md)与[操作指南](docs/api/dynamic-knowledge.md)。下方 234/137 数字是首次公开基线。

- 工单：状态机、可信身份与对象权限、事务审计、版本冲突、缓存失效与补偿。
- 异步：任务表与事务Outbox、RabbitMQ确认、幂等消费、租约恢复、规则兜底与人工重放。
- Agent：真实业务读工具、知识检索、受约束循环；指派仅生成提案，须原管理员独立确认。
- RAG：本地Ollama BGE-M3 1024维、向量与关键词融合、0.55生成门槛、模型按资料判断可答性、来源与模拟范围、失败原因。
- 知识回流：解决说明生成候选，管理员审核后发布共享演示知识；双库版本校验、失败重放审计及撤回。默认关闭，需迁移与显式启用。

当前最终源码Linux空Maven缓存234单元＋137集成通过，Python保护测试22项；最终JAR真实启动、接口15请求35断言和本地RAG检查通过。NW13最终版一次真实复验已恢复回答，不据此宣称总体准确率或模型绝不出错。

## 使用入口

首次克隆后需要 Java 17、Maven 和 Docker。下面的测试不调用厂商模型：

```bash
python "08_部署与环境/03_scripts/verify-test-profiles.py"
mvn -B -f "06_项目源代码/ticket-service/pom.xml" -Punit test
python "08_部署与环境/03_scripts/prepare-phase4.py"
```

最后一条准备隔离中间件、随机凭据及 `.tmp/phase4-integration.properties`。随后从 `06_项目源代码/ticket-service` 运行：

```bash
mvn -B -Pintegration -Dspring.config.additional-location=file:../../.tmp/phase4-integration.properties test
```

已有隔离卷时保留对应 `.tmp/phase4.env`，不要重新生成密码后复用旧卷。配置模板与本机演示启动见服务 README。

已有本机配置时，从项目根目录运行：

```powershell
& '.\06_项目源代码\ticket-service\start.ps1' -CheckOnly
& '.\06_项目源代码\ticket-service\start.ps1' -Mode offline-demo -Offline
```

首次环境准备、完整测试与配置要求见[服务README](06_项目源代码/ticket-service/README.md)。本地检索不等于真实模型生成；真实调用采用已验证交付JAR及计量代理，当前已按后续授权执行至累计407次；固定8题批次已结束，不自动消费调用差额。

## Agent 如何使用工具

| 工具 | 行为 | 执行边界 |
|---|---|---|
| getTicketDetail | 查真实工单详情和版本 | 服务端身份、对象权限 |
| getTicketLogs | 查处理进展 | 服务端身份、对象权限、条数限制 |
| searchKnowledge | 查知识来源和摘录 | 有限参数和输出，不嵌套一次额外生成 |
| searchExperienceGraph | 查已审核经验条件、步骤及同来源图路径 | 默认关闭；来源健康、版本与权限校验，只读 |
| prepareAssignTicket | 生成待确认指派提案 | 管理员可用，模型不能确认执行 |

```mermaid
sequenceDiagram
    participant U as 管理员
    participant A as Agent与工具校验
    participant M as 对话模型
    participant B as 业务服务
    U->>A: 查询工单并提出处理要求
    A->>M: 用户请求和工具定义
    M-->>A: 选择工具和参数
    A->>B: 校验权限后读取业务证据
    B-->>A: 工单/日志/知识来源
    A->>M: 按toolCallId回传结果
    M-->>A: 结构化答复或继续调用工具
    A-->>U: 有依据的建议和待确认提案
    U->>B: 独立确认接口
    B->>B: 校验原发起人、权限、版本、有效期与幂等
    B-->>U: 已执行结果
```

模型和工具执行有次数、Token、时限与线程池边界。跨请求会话记忆尚未实现；当前循环可在一次请求内连续调用多个工具。

## 异步分析如何恢复

```mermaid
sequenceDiagram
    participant API as 工单服务
    participant DB as MySQL
    participant P as Outbox投递器
    participant Q as RabbitMQ
    participant W as 分析消费者
    participant AI as 分类模型
    API->>DB: 同一事务保存工单、审计、任务、Outbox
    P->>DB: 短事务领取待发送事件
    P->>Q: 持久消息并等待发送确认
    Q-->>P: confirm及路由结果
    P->>DB: 标记发送状态，失败保留补发
    Q->>W: 可能重复投递
    W->>DB: 短事务领取任务与租约
    W->>AI: 事务外调用，失败可规则降级
    AI-->>W: 分类结果
    W->>DB: 新事务校验输入版本并保存独立建议
    W-->>Q: 提交后手动ACK或终态拒绝
```

任务幂等和租约约束重复业务效果，不保证外部模型恰好计费一次。业务重试有限；基础设施异常会暂停消费并尝试恢复。

## 当前事实与文档

- [当前项目状态](01_项目说明/20261019_项目状态与未结问题.md)
- [专家评价核查与修改执行计划](01_项目说明/20261019_专家评价核查与修改执行计划.md)
- [项目章程](01_项目说明/项目章程_v2_Agent与后端实习面试版.md)
- [阶段四验收清单](01_项目说明/07_阶段验收单/阶段4_验收清单_待执行.md)
- [最新收尾验收与原始证据](07_测试与质量/06_阶段测试报告/20261019_项目收尾验收记录.md)
- [角色、接口及版本契约](07_测试与质量/02_接口测试/阶段四_角色矩阵与接口说明.md)
- [架构与设计边界](10_面试与复盘/01_架构与设计/阶段四_架构与边界.md)

短信、支付、附件等知识是模拟客服场景，不代表项目实现了这些业务。固定演示API Key不是完整用户注册/SSO。已知题放行率不等于答案准确率；148条作者语义复核、长期稳定性与生产容量仍未签署或证明。远程CI证据见[首次执行结果](https://github.com/hashishdhdj/smartticket_AI/actions/runs/39204570840)。MCP、SSE、复杂记忆、复杂前端不是当前章程必交范围。

私有本机配置与运行凭据位于被忽略的本地文件；只使用08_部署与环境/04_交付产物下已验收公开JAR分发，不分发源码target中的中间包。评审后修改按执行计划分批推进，新增功能验收后再更新完成状态。
