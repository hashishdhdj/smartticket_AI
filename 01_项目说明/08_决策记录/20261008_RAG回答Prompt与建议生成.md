# 决策记录：RAG 回答 Prompt 与建议生成

> 项目：AI驱动的智能工单服务系统
> 阶段：阶段2 AI集成与RAG（第5周第8天）
> 日期：2026-10-08
> 状态：已实施并通过真实验证（真实模型输出合规）
> 关联文件：`src/main/resources/prompts/rag-answer-system.md`、`rag-answer-user.md`、`src/main/java/com/smartticket/rag/RagAnswerPromptFactory.java`、`impl/RagAnswerServiceImpl.java`、`RagAnswer.java`、`src/test/java/com/smartticket/rag/RagAnswerPromptFactoryTest.java`、`RagAnswerRealVendorTest.java`、`impl/RagAnswerServiceImplTest.java`
> 关联约束：`01_项目说明/项目章程.md` 第5周任务 6「优化 Prompt」、阶段2 关键约束第 9 条（不允许让 AI 编造不存在的知识库内容）、第 10 条（每次 Prompt 修改都要记录版本）
> 上游记录：`20261008_RAG置信度阈值与转人工.md`（`RagAnswer.suggestion` 当时留 null，本记录是它的后续）
> 关联流程：`02_AI约束与规范/03_Prompt模板/README.md`（Prompt 变更的固定流程与版本记录）

---

## 一、本次决策事项

| 序号 | 决策事项 | 结论 |
|---|---|---|
| 1 | 什么时候调模型生成建议 | **仅在"有把握"时**（`needHuman=false`）；低置信度**根本不调** |
| 2 | 喂给模型的依据用什么 | 切片的**完整正文**（不是 `RagSource.summary` 里给人看的截断摘录） |
| 3 | 建议的输出形态 | 一句中文、≤80 字、**无前缀、无 Markdown** |
| 4 | 是否写"剥掉『建议：』前缀"的清洗代码 | **不写**，以真实模型实测为准（实测它一次就遵守） |
| 5 | Prompt 工厂放哪 | **单开 `RagAnswerPromptFactory`**，与分类 Prompt 的版本生命周期分离 |
| 6 | 模型调用失败时怎么办 | `suggestion=null`，但 **`needHuman` 保持 false**，`sources` 照常返回 |
| 7 | 本次是否新增 HTTP 接口 | **不加**（与任务③④⑤ 同一决定） |

---

## 二、为什么"只在有把握时才调模型"

低置信度（`needHuman=true`）时**不调用模型**，而不是"先生成再丢弃"。两个理由：

1. **省一次调用**——低置信度的建议是注定不返回的；
2. **更重要的是从根上杜绝幻觉**。让模型去解释"它没把握的内容"，正是幻觉最容易发生的场合：
   资料本身回答不了这个问题，模型要么照抄不相关内容、要么开始自由发挥。
   **不调它，就没有这个机会。**

这与 `RagAnswer.needHuman` 的语义是一致的：`needHuman=true` 意味着"不得给出 AI 自拟的建议"。
把它做成"不调用"而不是"调用后不返回"，是让约束在结构上成立，而不是靠调用方自觉。

---

## 三、依据必须用切片的完整正文

`RagSource.summary` 是**给人看的**——折叠空白后截断到 150 字。**不能拿它喂模型**：

- 截断会切掉资料的尾部，而答案往往就在尾部（如"注意事项""到账时间"）；
- 模型据残缺资料作答，会补全缺失部分——这正是要避免的编造。

因此渲染 Prompt 时用的是 `FusedHit.chunk().content()`（原文），而 `RagSource.summary` 只用于返回给前端。
单元测试 `promptCarriesFullChunkContentAsGrounding` 用 `ArgumentCaptor` 抓住了送往模型的 user 提示词，
断言其中包含**完整正文**——防止将来有人图省事改成用 `summary`。

---

## 四、输出形态为什么限制得这么死

建议要直接展示给客服/用户，因此模板规定了四条硬约束：

| 约束 | 原因 |
|---|---|
| 一句、≤80 字 | 它是"下一步动作"，不是一篇分析；长了没人读 |
| 无前缀（如"建议："） | 前端会自己加标签，模型再加就变成"建议：建议：…" |
| 无 Markdown | 它进的是纯文本字段，Markdown 标记会原样显示出来 |
| 不提"资料/知识库" | 用户不关心系统的检索过程，只想要做法 |

### 一个由实测支撑的决定：**不写清洗代码**

实现里**没有**写"剥掉『建议：』前缀""去掉首尾引号"这类事后清洗。理由：

- 模板已经明确禁止了前缀，**应当先看模型是否遵守**；
- 实测（见第六节）它**一次就遵守了**；
- 于是不加一段"永不生效、只增加维护面"的代码。若将来换模型后出现前缀，
  再按**那时的实测证据**补规则。

`suggestion` 只做了三件有确定性收益的整理：**折叠空白成一行**（返回字段要求单行）、去空判空、超长兜底截断（200 字）。
这三件事与"模型是否遵守格式"无关，是无论模型怎么表现都必须做的。

---

## 五、为什么单开一个 Prompt 工厂

`RagAnswerPromptFactory` 与 `com.smartticket.ai.AiPromptFactory` 是**两个独立家族**：

| | 分类 Prompt | RAG 回答 Prompt |
|---|---|---|
| 稳定程度 | 长期稳定（输出是接口契约） | 会随检索效果反复调 |
| 版本号 | `ticket-classify-v1` | `rag-answer-v1` |

**共用一个版本常量的坏处**：调 RAG 措辞会被迫给分类 Prompt"陪跑"一次版本升级，
版本号就失去了"这一版改了什么"的意义。阶段2 约束第 10 条要求"每次 Prompt 修改都要记录版本"，
前提是版本号**只对应一个可解释的变更**。

**但有两处必须与 `AiPromptFactory` 保持一致**（都是踩过的坑，已写进两个类的注释）：

1. **模板分隔符用 `<>` 而不是默认的 `{}`**——否则正文里的花括号会被当成占位符；
2. **渲染时剥离首行 `<!-- prompt-version -->` 注释**——那是给人看的元信息，发给模型只白花 token。

另有一处**刻意不做**：本模板里**不写**"资料不足时请说不知道"这类兜底指令。
因为资料不足的情况在到达模型之前就已经被置信度阈值拦掉了（第二节），
写进去只会让模型在正常情形下也无谓地犹豫。

---

## 六、验证证据

**全量回归**

```text
[INFO] Tests run: 238, Failures: 0, Errors: 0, Skipped: 9
[INFO] BUILD SUCCESS
```

Skipped 9 = 真实厂商调用的 3 个测试类（`AiRealVendorSmokeTest` 2 + `KnowledgeRetrievalRealVendorTest` 4
+ `RagRetrievalEvaluationTest` 1 + `RagAnswerRealVendorTest` 2）。

**新增测试**

| 测试类 | 数量 | 验证的层次 |
|---|---|---|
| `RagAnswerPromptFactoryTest` | 7 | 反幻觉指令存在、资料块编号与顺序、占位符替换、**版本号与常量一致**、尖括号不被二次解析、空问题占位 |
| `RagAnswerServiceImplTest` | 10（含新增 4） | 高置信度生成建议、**提示词携带切片完整正文**、低置信度**不调模型**、模型失败仍返回来源、空输出不给空建议、多行折叠成一行 |
| `RagAnswerRealVendorTest` | 2 | **真实模型**：建议是否合规；低置信度是否确实不调模型 |

**真实模型验证（`AI_SMOKE=true`，DeepSeek `deepseek-chat`，1157ms）**

```text
[问题] 重复扣款如何申请退款
[置信度] 0.8130 | needHuman=false | matched=true
[来源]
  - faq/02-支付问题.md > 重复扣款如何申请退款 | score=0.8130 | via=[VECTOR, KEYWORD]
  - faq/02-支付问题.md > 支付成功但订单未生成怎么办 | score=0.6607 | via=[VECTOR]
  - tickets/02-支付问题.md > 同一笔订单被扣款两次   | score=0.6560 | via=[VECTOR]
[建议] 请先收集两笔扣款的渠道流水号与金额，提交「支付问题-重复扣款」工单，由人工核对后原路退款。
```

四条约束逐项核对：单行 45 字无前缀 ✓ / 无 Markdown ✓ / 未提"资料/知识库" ✓ /
内容全部出自第 1 条资料（无编造）✓

**低置信度侧的反证**：同一批运行里 `如何修改发票抬头`（置信度 0.5414）返回
`needHuman=true`、`suggestion=null`，且日志中**只有高置信度那次**出现
`AiChatServiceImpl : AI 调用成功`——证明链路**确实没有调用模型**，而不是"调了但不返回"。

复现方式：

```powershell
$env:AI_SMOKE = "true"
mvn test -Dtest=RagAnswerRealVendorTest
```

---

## 七、已知限制与遗留

| 序号 | 问题 | 影响 | 建议处理时机 |
|---|---|---|---|
| 1 | 只验证了 2 个问题（1 正 1 负） | 覆盖有限，不能说明"建议质量整体达标" | 第8项任务（评估报告）扩充 |
| 2 | 未评估建议的**内容质量**（只验证了格式与合规） | `reference_answer` 列为空，无法判定"建议是否真的有用" | 第8项任务，需先填标准答案 |
| 3 | 建议生成耗时未纳入整体预算 | 单次回答耗时 = 检索(0.2~1.8s) + 模型(约 1.2s) | 阶段3（并发与超时隔离） |
| 4 | 每次回答都真实调用模型 | 有成本，且无缓存 | 若同一问题重复率高，可加缓存（阶段3） |
| 5 | 未做 Prompt 注入防护 | 用户问题原样进 Prompt，理论上可诱导 | 内部系统、输入受工单表单约束，暂列观察 |
