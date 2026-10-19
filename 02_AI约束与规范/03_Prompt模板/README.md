# Prompt 模板版本记录

> 项目：AI驱动的智能工单服务系统
> 建立日期：2026-09-26（阶段2 第2天）
> 依据：`01_项目说明/项目章程.md` 阶段2 交付物（Prompt 模板存放位置）+ 阶段2 关键约束第 10 条「每次 Prompt 修改都要记录版本」
> 位置说明：本目录由 `项目章程.md` 指定为 Prompt 模板的归档位置

---

## 一、这个目录里为什么没有 Prompt 正文

**Prompt 正文只有一份，在代码里**：

```text
06_项目源代码/ticket-service/src/main/resources/prompts/
├─ ticket-classify-system.md          分类的 system 提示词
├─ ticket-classify-user.md            分类的 user 提示词
├─ ai-classification-result.schema.json   分类输出的 JSON Schema
├─ rag-answer-system.md               RAG 回答的 system 提示词（阶段2 第5周任务 6）
├─ rag-answer-user.md                 RAG 回答的 user 提示词（阶段2 第5周任务 6）
└─ rag-answer-result.schema.json      RAG 回答输出的 JSON Schema（阶段2 第11天补）
```

理由：Prompt 必须随应用一起打包才能被运行时读取（`ClassPathResource`），放在本目录会导致应用读不到；而如果两处各放一份正文，迟早会不一致——**本项目已经明确不接受"两份真值"**（同类判断见依赖清单里 Redis 引入阶段的口径矛盾、以及「状态取值只由应用层单点保证，避免 DB 与应用保留两份真相」的决策）。

因此本目录只承担两件事：**索引**与**版本变更记录**。

---

## 二、版本记录

| 版本 | 日期 | 涉及文件 | 变更内容 | 变更原因 | 实测效果 |
|---|---|---|---|---|---|
| `ticket-classify-v1` | 2026-09-26 | `ticket-classify-system.md`<br>`ticket-classify-user.md` | 首个版本。system 侧规定 5 字段输出结构、分类白名单、优先级白名单、confidence 语义、keywords 与 reason 的长度约束；user 侧给出标题与描述两个插槽 | 章程第3周任务 3「设计 Prompt 模板」 | DeepSeek `deepseek-chat` 实测：553ms，输出裸 JSON、字段无多余、分类与定级均合理，被解析器一次接受。见下节证据 |
| `rag-answer-v1` | 2026-10-08 | `rag-answer-system.md`<br>`rag-answer-user.md` | 首个版本。system 侧要求"只依据知识库资料作答、不得编造"、输出一句 80 字以内中文（无前缀、无 Markdown、不提"资料/知识库"）；user 侧给出问题与带编号的资料块两个插槽 | 章程第5周任务 6「优化 Prompt」——RAG 检索已有来源，需要据此生成建议 | DeepSeek `deepseek-chat` 实测：1157ms，45 字单行、无前缀、无 Markdown、未提"资料"字样，内容全部落在给出的资料内。见第六节证据 |
| `rag-answer-v2` | 2026-10-09 | `rag-answer-system.md`<br>`rag-answer-user.md` | 输出由"一句自由文本"改为**结构化 JSON**：`{suggestion, similarQuestions[]}`。新增 similarQuestions 的语义约束（1~5 条、每条 ≤40 字、须紧扣某条资料、不得照抄原问题）；suggestion 的既有规则（只依据资料、≤80 字、无前缀、无 Markdown）**保持不变** | 章程模块三要求「返回相似问题」；且 RAG 要接成"提交前检索"接口，调用方需要的是结构化数据而不是一段文本。**并入同一次调用**而非另开一次：用户在提单页等着，多一次往返的延迟与计费不划算 | DeepSeek `deepseek-chat` 实测：**一次通过**。suggestion 43 字、单行、无前缀、未提"资料"字样、内容全部出自第 1 条资料；similarQuestions 返回 4 条、每条 ≤40 字、前 2 条紧扣命中小节，**后 2 条超出资料范围**（已登记为局限，见第七节） |

> 新增一行时请追加，不要修改历史行——历史行的价值就在于能回答"当时 Prompt 长什么样、效果如何"。

---

## 三、v1 的真实模型验证证据

2026-09-26，用真实 DeepSeek 密钥执行 `AiRealVendorSmokeTest#realModelOutputIsAcceptedByParser`：

```text
[真实分类] promptVersion = ticket-classify-v1
[真实分类] success = true | cost = 553ms
[真实分类] 原始返回 = {"category":"功能异常","priority":"HIGH","confidence":0.95,
                      "keywords":["登录页","验证码异常","无法登录","验证码错误","换了浏览器"],
                      "reason":"用户输入正确验证码仍提示错误，且多设备多浏览器均无法登录，
                                属于核心登录功能异常，影响面广，故判为高优先级。"}
[真实分类] 解析结果 = category=功能异常 | priority=HIGH | confidence=0.95 | ...
```

四件事同时成立才算 v1 合格：

| 检查项 | 结果 |
|---|---|
| 不使用 Markdown 代码块包裹 | ✓ 裸 JSON |
| 字段数量与名称与 Schema 完全一致（不多不少） | ✓ |
| category 逐字取自白名单 | ✓ 「功能异常」 |
| 输出的长度与数值约束都在界内 | ✓ reason 约 85 字、keywords 5 个 |

---

## 四、改 Prompt 的固定流程

改 Prompt 是**高风险改动**（一句话措辞变化就可能让输出格式崩掉），因此按下列顺序执行，一步不省：

```text
1. 改 resources/prompts/ 下的模板正文
2. 改模板文件首行的 <!-- prompt-version: xxx --> 版本号
3. 改 AiPromptFactory.PROMPT_VERSION 常量，与第 2 步保持一致
        ↑ 第 2、3 步漏做任何一步，AiPromptFactoryTest 会直接失败（这是刻意设计的机械检查）
4. 在本文件第二节追加一行版本记录
5. 跑单元测试：mvn test -Dtest=AiPromptFactoryTest,AiClassificationParserTest
   （RAG 回答 Prompt 对应的是 mvn test -Dtest=RagAnswerPromptFactoryTest,RagAnswerServiceImplTest）
6. 跑真实模型验证：$env:AI_SMOKE="true"; mvn test -Dtest=AiRealVendorSmokeTest
   （RAG 回答 Prompt 对应的是 $env:AI_SMOKE="true"; mvn test -Dtest=RagAnswerRealVendorTest）
7. 若第 6 步输出格式不符，回到第 1 步；重复修改可复用同一版本号，但改完要重跑第 6 步
```

第 6 步不可省：单元测试用的是我们自己构造的输入，只能证明"解析器能处理合规输出"，**证明不了"模型会产出合规输出"**——后者只有真实调用能回答。第5周做评估集时，本步骤会扩展成批量评估。

---

## 五、版本与外键的对应关系

| 位置 | 内容 |
|---|---|
| 模板文件首行 | `<!-- prompt-version: ticket-classify-v1 -->`（渲染时会剥离，不会发给模型） |
| `AiPromptFactory.PROMPT_VERSION` | `ticket-classify-v1` |
| 本文件第二节表格 | 版本、日期、变更内容、原因、实测效果 |
| `AiClassificationResult` 与 `ai-classification-result.schema.json` | **不属于 Prompt 版本**。它们约束的是"输出结构"，与 Prompt 措辞是两个独立可变项：结构变更是接口契约变更，走 `01_项目说明/08_决策记录/` |

---

## 六、`rag-answer-v1` 的真实模型验证证据

2026-10-08，用真实 DeepSeek 密钥执行 `RagAnswerRealVendorTest`（阶段2 第5周任务 6）：

```text
[问题] 重复扣款如何申请退款
[置信度] 0.8130 | needHuman=false | matched=true
[来源]
  - faq/02-支付问题.md > 重复扣款如何申请退款 | score=0.8130 | via=[VECTOR, KEYWORD]
  - faq/02-支付问题.md > 支付成功但订单未生成怎么办 | score=0.6607 | via=[VECTOR]
  - tickets/02-支付问题.md > 同一笔订单被扣款两次   | score=0.6560 | via=[VECTOR]
[建议] 请先收集两笔扣款的渠道流水号与金额，提交「支付问题-重复扣款」工单，由人工核对后原路退款。
```

四条约束逐项核对：

| 检查项 | 结果 |
|---|---|
| 只输出一句、无前缀（如"建议："） | ✓ 单行 45 字，无前缀 |
| 不使用 Markdown / 代码块 | ✓ |
| 不提及"资料""知识库"字样 | ✓ |
| 内容不超出给出的资料（无编造） | ✓ 「收集渠道流水号与金额」「提交工单选『支付问题-重复扣款』」「人工核对后原路退款」均出自第 1 条资料 |

**一个由实测支撑的决定**：实现里**没有**写"剥掉『建议：』前缀"这类修饰代码。
理由是模板已明确禁止前缀，应当先看模型是否遵守；实测它一次就遵守了，
于是不加猜测性的清洗逻辑（否则就是一段永不生效、只增加维护面的代码）。
若将来换模型后出现前缀，再按那时的实测证据补规则。

**低置信度侧的反证**：同一批运行里，`如何修改发票抬头`（负样本，置信度 0.5414）返回
`needHuman=true`、`suggestion=null`，且日志中**只有高置信度那次**出现
`AiChatServiceImpl : AI 调用成功` —— 证明低置信度时链路**确实没有调用模型**，
而不是"调了但不返回"。

---

## 七、`rag-answer-v2` 的真实模型验证证据

2026-10-09，用真实 DeepSeek 密钥执行 `RagAnswerRealVendorTest`（RAG 接入"提交前检索"接口）：

```text
[置信度] 0.8130 | needHuman=false | matched=true
[来源]
  - faq/02-支付问题.md > 重复扣款如何申请退款 | score=0.8130 | via=[VECTOR, KEYWORD]
  - faq/02-支付问题.md > 支付成功但订单未生成怎么办 | score=0.6607 | via=[VECTOR]
  - tickets/02-支付问题.md > 同一笔订单被扣款两次   | score=0.6560 | via=[VECTOR]
[建议] 收集两次扣款的渠道流水号与金额，提交工单选择「支付问题-重复扣款」，人工核对后原路退款。
[相似问题] [支付成功但订单未生成怎么办？, 同一笔订单被扣款两次该如何排查？,
            退款到账需要多长时间？, 支付渠道账号已注销还能退款吗？]
```

约束逐项核对：

| 检查项 | 结果 |
|---|---|
| 只输出 JSON、不加代码块围栏 | ✓ 解析器**一次接受**，未触发剥围栏逻辑 |
| suggestion ≤80 字、单行、无前缀 | ✓ 43 字、单行、无"建议："前缀 |
| suggestion 不提及"资料""知识库" | ✓ |
| suggestion 不超出资料（无编造） | ✓ 「收集渠道流水号与金额」「提交工单选『支付问题-重复扣款』」「人工核对后原路退款」均出自第 1 条资料 |
| similarQuestions 条数 1~5、每条 ≤40 字 | ✓ 返回 4 条，最长 20 字 |

### 一处**未通过**的约束（如实记录）

规则 5 要求每条相似问题"紧扣某一条【知识库资料】…不得引入资料之外的话题"。实测：

| 相似问题 | 是否落在资料内 |
|---|---|
| 支付成功但订单未生成怎么办？ | ✓ 对应第 2 条资料 |
| 同一笔订单被扣款两次该如何排查？ | ✓ 对应第 3 条资料 |
| 退款到账需要多长时间？ | **✗ 语料里没有这一节** |
| 支付渠道账号已注销还能退款吗？ | **✗ 语料里没有这一节** |

**4 条里有 2 条是模型"顺着主题发散"出来的**，主题仍在支付/退款范围内，但知识库里查不到对应内容。

**为什么解析器没挡住它**：解析器只能校验**结构与长度**（是不是 JSON、字段齐不齐、条数与字数是否越界），
**无法校验"这句话是否真的出自资料"**——那需要语义判断，正是模型自己在做的那件事。
`RagAnswerParser` 的注释里已写明这一尺度：只校验语义与结构，不校验措辞。

**影响与处置**：不算阻断性问题（用户点进去会看到"知识库里没有"，不会误导），但确实不符合 Prompt 的约定。
**已登记为局限**，候选改进见下一条。

### 候选改进（未实施，留给后续）

把 similarQuestions 的生成方式从"自由发散"改为**与资料一一对应**：

1. Prompt 改成"为**每一条**【知识库资料】各生成一条问句，条数与资料条数相同"；
2. 在**服务层**（不是解析器）校验 `similarQuestions.size() == sources.size()`，
   不等就说明模型没照做，按"内容不合规"整份丢弃。

这样至少能把"条数"变成可机械校验的约束，迫使模型逐条对着资料写，而不是自由联想。
代价是资料条数为 1 时只能得到 1 条相似问题，展示效果会单薄一些——
**取舍留到有真实用户反馈后再定**，现在不提前优化。


## 2026-10-15：rag-answer-v3 与 agent-v2

保留v2真实基线，修复两类实测问题：高相似度但资料不覆盖问题时答非所问；演示业务被表述为项目已实现功能。RAG模型输出新增必填布尔needHuman；缺字段或非布尔按结构错误处理。模型只能追加拒答，不能解除原0.66检索门禁；拒答时服务层隐藏生成建议、保留真实来源。资料含模拟范围时回答必须明确演示性质和未实现边界，不能提供凭据或编造未知规则。similarQuestions的公开返回仍由真实检索标题确定。

Agent系统指令升级为agent-v2：最少工具调用、建议场景不额外查日志、一次检索足够就生成最终答案、保留模拟性质。仅提供给模型的知识视图按每来源320 UTF-8字节截取并标注truncated；重复sourceId改为前文引用。原始工具证据保留，不截断审计数据。4次模型/6次工具/12000保守Token/60秒限额保持不变。

v1评估题已经用于发现问题，此后重跑只作回归，不再称独立盲测。新增30题phase4-v2-regression在首次v3厂商执行前冻结，同样明确是补充回归；标签和语义评分仍需作者复核。

补充：rag-answer-v3.1为每条资料显式携带资料性质，Agent同用KnowledgeProvenance。已登记模拟文件的续片不因缺少前言而变成项目事实；未知文件标UNSPECIFIED。新增资料需审查来源并同步类型映射。该末次修复由200项单元、127项集成和3题RAG/2题Agent针对性真实复验覆盖，不能把末次5题当完整效果重评。

### 2026-10-15晚间工具契约补充

Agent主提示词仍为agent-v2；searchKnowledge新增权限后编号查询标题补全与requestedQuery/effectiveQuery/queryOrigin输出，具体实现版本由JAR哈希记录。没有将服务端补全冒充模型选词能力。新增10单元和2集成，最新210+129=339项通过；两题真实复验为定向回归，作者语义待审，详见阶段四效果续作报告。

## 2026-10-16 rag-answer-v3.3

限制资料外的压缩、拆分、分卷、提高上限等补救建议；新增有限的服务端操作词检查，未获来源支持时转人工并返回UNSUPPORTED_WORKAROUND。该检查只覆盖有限措辞，不等于通用事实验证。保留needHuman、模拟范围、权限与预算约束。
