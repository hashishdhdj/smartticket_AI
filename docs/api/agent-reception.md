# Agent 优先接待（2026-10-29）

新工单保存后自动启动受限 Agent；简单问题直接答复，缺用户信息先追问，需实际人工操作或执行未完成时进入人工待办。答复成功不等于问题解决，只有原提交人确认后才记为 RESOLVED。

## 启用与兼容

1. 已完成既有 01..08 MySQL 迁移后，运行 `py -3.11 "08_部署与环境/03_scripts/prepare-reception.py"`，只新增两张表，不回填历史工单。
2. 使用 `start.ps1 -AgentReception` 或启用 `reception` Spring profile。默认基础配置保留旧流程；接待 profile 才切换创建行为，便于既有数据库和评测兼容。启动时检查表结构及可信鉴权，缺迁移直接启动失败。
3. 真模型示例：`& '.\06_项目源代码\ticket-service\start.ps1' -Mode real-model -EmbeddingProvider ollama -AgentReception -Offline`。`-Offline` 只表示 Maven 离线，不会禁止模型 HTTP 调用；真实模型仍须配置密钥和运行预算。知识库、图谱沿用各自配置与启用条件，不能只开接待就认为已经入库。
4. `-Mode offline-demo -AgentReception` 可验证模型不可用时的人工降级，不会得到真实模型回答。
5. 暂停调度用 `smartticket.reception.async-enabled=false`，保留状态查看和人工请求；关闭整个功能应先接管未完成的接待。旧 `/api/agent/runs` 和人工工单接口仍可使用。

接待 profile 不重复登记创建时的后台分类任务，避免同一新工单同时触发两套模型处理。旧分类分析、Outbox、RabbitMQ、分析重放能力继续保留；未启用接待时沿用原行为。

维护副本没有本机 `.env` 时，可显式传 `--env-file "D:\SmartTicket-AI\08_部署与环境\01_docker\.env"`，凭据只在本机读取，不打印。`start.ps1` 仍要求按原服务说明准备该维护副本自己的本地配置。

## 状态与数据

| 接待状态 | 工单业务状态 | 下一步 |
|---|---|---|
| QUEUED / RUNNING | PROCESSING，处理人为空 | 后台 Agent 接待，不表示已由人工领取 |
| WAITING_INPUT | PROCESSING | 客户补充信息 |
| WAITING_CUSTOMER | PENDING_FEEDBACK | 客户确认解决、继续描述或请求人工 |
| HUMAN_REQUIRED | PENDING_ASSIGN | 进入现有待分配列表，由管理员指派合适处理人 |
| HUMAN_ACTIVE | 取决于人工业务流 | 人工已指派或已有人工操作，禁止自动续跑 |
| RESOLVED / CLOSED | 对应业务终态 | 正常人工闭环或客户确认 |

工单创建、操作日志、接待任务在同一 MySQL 事务保存。`ticket_reception` 本身是持久任务队列，调度器领取后直接调用同进程 Agent，不跨 MQ 发送，因此此链路无需另建 Outbox。调度线程只负责启动一个有界后台执行器，不会等慢模型而阻塞已有 Outbox、缓存补偿等定时任务。

领取采用 ticket→reception 的固定锁顺序，租约至少覆盖 Agent 时限+30秒，调用在事务外，完成后核对工单版本、接待轮次、领取令牌及处理人。重复领取跳过；进程崩溃可租约恢复，默认最多3次，耗尽后人工接管。它不保证外部调用严格一次，崩溃重试仍可能再次计费。

默认最多6轮接待。客户回复带幂等键和期望轮次，同键同内容不重复排队，不同内容返回409。客户消息与内部生成消息使用独立键命名空间。保存有限历史给下一轮 Agent 使用，这是工单范围内的持久上下文，不是任意跨请求全局会话记忆。

Agent 自动接待只获得提交人身份与三类只读工具：当前工单详情、共享文本知识、已启用的共享经验图谱。即使管理员创建工单，也不把管理员权限授予后台 Agent；禁止员工日志、指派提案和跨工单详情访问。直接回答至少应经过一次工具取得依据；来源校验、模型循环预算和人工升级契约继续适用。格式与来源校验仍不等于语义绝对正确。

人工指派、解决、关闭和修改输入都会在同一写事务使旧接待令牌失效。输入被改动时，当前自动接待转入人工待办；不会在新问题上继续发布旧回答。客户等待答复时也能主动请求人工。待反馈→人工待分配遵循原状态机，经处理中再转待分配，两次状态变化均留日志。

## 接口

所有请求都携带本项目 `X-API-Key`，不使用 DeepSeek Key。

`GET /api/tickets/{id}/reception`

提交人仅查看自己的接待；处理人仅查看已指派给自己的工单；管理员可查看。响应包含 `ticketStatus`、`ticketVersion`、`receptionState`、`turn`、`reason` 和按时间排序的 `messages`。工作人员另可查看 `lastRunId`、工具步骤及人工交接摘要；客户不返回这些内部字段。消息保存在项目接口中供客户端轮询，没有接入外部短信或客服聊天发送服务。

`POST /api/tickets/{id}/reception/responses`

仅原提交人可确认或补充，管理员不能代客户确认。请求示例：

```json
{"requestId":"reply-001","expectedTurn":1,"action":"MESSAGE","message":"原手机号还能收到短信"}
```

- `MESSAGE`：非空，最多800字；在等待追问或等待确认时重新排队。未解决也使用此动作说明现象。
- `RESOLVED`：仅 WAITING_CUSTOMER 可以确认；`message` 传空串。
- `HUMAN`：排队、执行中和等待状态均可请求人工，取消自动写回；`message` 传空串。
- `requestId`：1..64位字母、数字、下划线或短横线；同一次提交重试保持不变。
- `expectedTurn`：使用最新 GET 返回的 `turn`，过时返回409。

人工待办使用已有 `GET /api/tickets?status=PENDING_ASSIGN`。查看接待记录后通过原指派接口分配；交接保留工单描述、客户回复、Agent答复、最后执行编号、工具成功/失败和未完成原因。当前没有自动选择具体员工的排班/技能路由，也没有真实退款、账户变更等执行工具。

## 验证边界

`ReceptionIntegrationTest` 使用真实 MySQL、HTTP控制器、真实 AgentService/AgentTools 循环，模型和知识召回使用确定性替身，覆盖自动触发、追问续跑、确认、人工接管、租约、并发、预算与权限。证明工程闭环，不代表真实 DeepSeek 回答质量；本次不消费旧评估批次的剩余额度。

交付 JAR 的零厂商调用验收：先运行 `prepare-phase4.py` 和 `build-quality-artifact.py`，再执行：

```powershell
py -3.11 '.\08_部署与环境\03_scripts\verify-agent-reception.py' --java "$env:JAVA_HOME\bin\java.exe"
```

脚本启动本地模型协议替身及真实 JAR，经真实 HTTP 创建三类工单，验收后台调度、工具回传、答复待确认、追问续跑和人工指派；报告为 `.tmp/reception-http-smoke.json`，结束后停止自己的服务并清理自己的数据。替身使用工单状态查询演示直接答复，不证明知识回答质量。
