# 回答、澄清与人工处理契约

2026-10-27。适用于 Agent 最终输出与 RAG 回答。版本：`agent-v3.3-governed-handoff`、`rag-answer-v4.0`。这是对 G5 已知失败的定向修改，不是新的盲测结果。

已有资料能完整解释的问题直接回答。缺少用户能补充的信息先追问。模型要求人工处理时，必须提交受限原因与具体阻塞事项，不能只返回一个 `needHuman=true`。

| 当前请求 | 处理方式 | 示例 |
|---|---|---|
| 已有依据的规则或步骤解释 | ANSWER，needHuman=false | PUT 全量更新、版本冲突、SENT 与分析成功的区别 |
| 解释涉及人工的流程 | ANSWER，needHuman=false | 解释管理员审核与索引；解释人工申诉路径 |
| 缺少用户可提供的必要信息 | ASK_USER，needHuman=false | 询问具体错误、适用条件；Agent 状态 WAITING_INPUT |
| 缺少用户真正需要的关键业务事实 | HUMAN_SUPPORT，needHuman=true | 无法查到某笔真实退款的处理记录 |
| 明确要求实际办理且须人工操作 | HUMAN_SUPPORT，needHuman=true | 立即办理需要人工核验的账号申诉 |

仅“演示资料”“业务效果尚未独立验证”“本题没有要求的源码细节未列出”，均不构成人工升级理由。资料支持排查步骤时可说明条件和步骤；回答步骤不等于已经查明根因或处理成功。权限不足不得索取管理员密钥、绕过权限或冒充办理。

## 模型契约与校验

Agent 输出保留 `answer, needHuman, sourceIds`，RAG 保留 `suggestion, similarQuestions, needHuman`；二者都新增 `handoff`：

```json
{"state":"ANSWERED","reason":"NONE","detail":""}
```

| state | reason | needHuman | detail |
|---|---|---|---|
| ANSWERED | NONE | false | 空字符串 |
| NEEDS_CLARIFICATION | USER_CONTEXT_REQUIRED | false | 5–200 字，具体缺什么用户信息 |
| INSUFFICIENT_EVIDENCE | CRITICAL_FACTS_MISSING | true | 5–200 字，具体缺什么业务事实 |
| HUMAN_ACTION_REQUIRED | HUMAN_OPERATION_REQUIRED | true | 5–200 字，具体需要办理什么人工操作 |

Java 的 `HandoffDecision` 统一校验字段、原因白名单、状态与布尔值一致性。旧版三个字段的成功回答继续兼容；旧版只有 true 而没有原因的回答被拒绝。此校验约束结构和取值，资料是否真正充分、原因是否切题仍需模型语义判断，不能据此保证所有简单问题都零误判。

人工升级判定不合规时最多纠正一次。Agent 纠正仍受原整次调用次数、Token、时限限制，且不给工具定义；尝试额外工具调用会停止。RAG 最多第二次调用，每次仍经过原有调用预算和超时控制。不会循环重试。

纠正后仍无合规原因，则保留来源并返回待澄清；这不是成功答案，也不会自动创建人工工单。Agent 原因是 `HANDOFF_REASON_REQUIRED`，RAG 原因是 `CLARIFICATION_REQUIRED`。现有 API 没有跨请求保存 Agent 对话历史；后续请求须携带原问题及补充信息。

真正的模型/工具故障、权限或预算问题、低于检索门槛、伪造引用、已撤回/变更来源等，继续沿用各自的停止或降级流程。`needHuman=true` 是辅助处理信号，不直接将工单分配给管理员。指派仍须提案与独立确认，知识发布仍须原审核流程。

## API 增量字段

Agent `POST /api/agent/runs` 及知识回答响应新增：

- `nextAction`：ANSWER、ASK_USER、HUMAN_SUPPORT；Agent 提案完成时为 CONFIRM_ACTION。
- `handoffReason`：有人工事项时给出具体原因；无人工升级时为 NONE。故障降级沿用稳定原因码。
- `handoffDetail`：真正人工事项的具体阻塞说明；不返回模型原始异常或认证信息。

调用方先看 nextAction。ASK_USER 展示追问，不能仅因状态不是 SUCCEEDED 就分配人工；CONFIRM_ACTION 展示待确认操作。`needHuman=false` 也不代表业务操作已执行，仍需检查状态、步骤和提案。

## 验证范围

新增单元测试覆盖状态与原因冲突、无理由升级、一次纠正、澄清、具体人工阻塞、纠正时传输失败和重复 JSON。集成测试覆盖真实 Agent 循环、图谱来源、工具禁用、调用次数不足及 Spring AI 本地 HTTP 协议。付费模型未在本次修改后重跑，历史 G5 的36条回答及评分不改写，不能将测试替身结果当作 DeepSeek 质量提升或真实转人工率下降。
