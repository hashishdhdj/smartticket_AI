# G2：结构化经验卡、去重与知识版本

经验卡先独立保存，不要求把工单标为已解决。首版接收AI离线提供的结构字段，后端负责schema校验、保守规范化、来源关联与去重；不是对任意聊天的自动事实抽取，也不训练模型。图节点、关系和Agent图谱工具属于G3/G4。

## 启用与权限

先应用`06_项目源代码/sql/07_experience_cards.sql`，设置`smartticket.experience.enabled=true`和`smartticket.experience.cards-enabled=true`；两个开关默认关闭。绑定与审核另外要求既有`smartticket.knowledge.enabled=true`。

员工仅可提交/读取当前分配给自己的工单经验；ADMIN可访问全部未删除工单。SUBMITTER不能访问内部经验。绑定候选、修订和审核仍由ADMIN执行。首版只接受来源标为AI_SYNTHETIC_DEMO的记录，范围固定SHARED_DEMO；不会自动把真实员工观察或私有客户内容共享。

G2运行包中的知识审核与动态来源过滤已接入结构版本校验，因此启用知识工作流前须执行07迁移，即使暂不创建新卡。关闭经验卡创建开关不取消已有版本的审核校验和失败依据屏蔽。回退保留新增表与记录，不删除数据库卷。

若回退到G1或更早的旧包，应先停用知识审核与异步知识工作流；旧包不具备G2结构版本保护，不能继续审批已经绑定结构卡的候选。新增表不应删除。

## 创建与查询

| 方法与路径 | 内容 |
|---|---|
| POST `/api/tickets/{id}/experience-cards` | 规范化经验卡并追加本工单的来源提交 |
| GET `/api/tickets/{id}/experience-cards?limit=20` | 最近提交摘要，limit为1–100 |
| GET `/api/tickets/{id}/experience-cards/{submissionId}` | 卡片、提交来源快照与当前可复用提示 |
| POST `/api/knowledge/candidates/{id}/experience` | ADMIN绑定或修订已有候选的结构版本 |
| GET `/api/knowledge/candidates/{id}/experience` | ADMIN查看当前绑定、来源和依据健康状态 |

统一返回原有`{code,message,data}`。示例中的记录ID须替换为同工单已保存的员工记录ID，不能直接复用示例ID。

```json
{
  "requestId": "94a6f6ce-5b32-49bd-897f-15dd2be58684",
  "expectedVersion": 1,
  "positiveInteractionIds": [123, 124],
  "counterexampleInteractionIds": [],
  "card": {
    "problemType": "演示巡检连接异常处理",
    "symptoms": ["连接中断"],
    "conditions": ["仅限演示环境", "已经核实连接配置"],
    "steps": ["核对连接参数", "重试并记录是否恢复"],
    "contraindications": ["核实前不得承诺已经恢复"],
    "counterexamples": [],
    "hypotheses": [],
    "evidenceRefs": [{"path": "docs/api/staff-experience.md", "title": "内部处理记录"}],
    "factScope": "PROJECT_RULE",
    "supportStatus": "REFERENCED_DEMO"
  }
}
```

`positiveInteractionIds`为1–12条正向候选依据；任何已被标为FAILED或CORRECTED的原记录都不能选入，后来单独追加EFFECTIVE也不会抹除既有失败。应使用新的CORRECTION记录，并把原错误记录加入最多8条`counterexampleInteractionIds`，同时填写经验卡反例说明。正反依据不能重叠，也不能跨工单；所有引用必须出现在选定员工记录中。引用仍是待管理员核实的资料指针，存在引用不等于结论真实。

card包括问题类型、症状、条件、有序步骤、禁止条件、失败反例、待验证假设和引用。数组及每项长度受schema限制，完整渲染文本最多1500字；超限拒绝，不截断前提或反例。未知字段、来源组合和非法枚举均拒绝。

`factScope`为PROJECT_RULE、SIMULATED_PLATFORM_RULE_NOT_IMPLEMENTED_FEATURE或SIMULATED_CUSTOMER_QUESTION_NO_PAYMENT_INTEGRATION。模拟业务保留模拟范围；未集成真实支付的合成问题明确不能确认实际到账，强制保持UNVERIFIED。`supportStatus`由合成资料编写者提供：REFERENCED_DEMO表示待核查的演示依据，不是机器验证通过；UNVERIFIED表示缺少充分依据，允许保存草稿，禁止审核发布。业务处理效果单独来自原始反馈，渲染中明确“实际业务效果未独立核实”。

返回的`data.submission`记录本工单、版本、可信作者与cardId；`data.card`包含规范化结构、哈希及模板渲染的question/answer；`exactDuplicate`表示是否复用已有相同内容；`extractionMethod=provided-fields-v1`明确本轮处理方法。原文、正反依据和准备时每条记录最近20条反馈保存在独立来源快照中，完整追加历史仍在G1表。

同工单、作者和requestId下，相同规范化请求重试返回同一submission；内容或expectedVersion冲突返回409。重放先检查当前权限，重新分配后原处理人不能继续访问。首次提交必须匹配工单版本，生成经验卡本身不修改版本或业务状态。

## 去重和复用

规范化只去除首尾空白；症状、条件、禁止条件、反例、假设和引用作为集合排序、精确去重。步骤保持顺序及重复项，内部空格、否定词、数值、标点和版本不删除。固定schema前缀和规范化JSON计算SHA-256，MySQL唯一索引处理并发去重。

完全相同的卡共用cardId，但每个工单仍保存独立submission和来源快照。不把某位员工的原文或工单信息透露给其他处理人。条件、适用范围或支持状态不同都会生成另一张卡。

`relatedDrafts`仅ADMIN可见，是最近50张卡中问题名称的字符二元组重叠提示，最多5条；不代表语义重复，不执行合并。它不是全库模糊查重，也不是模型评分。

若同卡已有APPROVED且indexedRevision与当前版本一致、哈希匹配、原工单未删除且正向依据仍健康，则返回`reusableKnowledge`目录提示。它不创建新审批或业务写操作，也不绕过实际检索时的PG发布版本检查。没有可用索引时仍复用卡结构，不把草稿称为已发布知识。

## 绑定、审核与修订

经验卡独立于工单状态；需要发布到当前文本RAG时，先沿用原有`resolve(solution)`生成解决记录和DRAFT候选，再由管理员把本工单submission绑定到该候选。不能为了保存经验而强制解决工单，未知业务结果可以一直停留在经验卡草稿。

```json
{"expectedRevision": 0, "submissionId": "请替换为真实submissionId"}
```

绑定请求体不接受question/answer；服务端从卡片完整生成。初次绑定DRAFT候选保留revision 0；同submission重试不追加版本。后续改卡必须新建submission，已APPROVED候选须先撤回，再绑定新卡进入新的DRAFT revision。旧结构、旧反例和来源版本保留。

原有approve接口继续使用expectedRevision、question、answer、scope和redactionConfirmed。对于绑定经验的候选，question/answer必须与模板完全一致，管理员不能绕过结构只改审核文本；要改条件或步骤，应先修订卡。审批会重新检查正向依据是否新增失败/纠正反馈，并将结构绑定、候选新版本、审核审计和INDEX任务/Outbox放在同一MySQL事务中。

典型版本：`0绑定草稿 → 1审核发布 → 2撤回 → 3新卡草稿 → 4重新审核发布`。knowledge_experience_version以candidateId＋revision为主键，保存cardHash、publicationHash、来源submission和状态，防止结构与文本串版。并发绑定/审核先锁工单再锁候选，并用READ_COMMITTED读取等待期间已提交的结构版本。

审核后沿用原有RabbitMQ、Ollama BGE-M3和PG文本索引流程；G2不创建图关系。新增失败或纠正反馈时，依赖该正向记录的新检索及复用提示立即屏蔽，无须等待PG删除；历史回答不会追溯撤销，候选历史状态也不自动改为RETRACTED。管理员可据此复核并撤回/修订，PG清理和完整图生命周期由既有流程及后续阶段处理。

## 数据与错误

新增experience_card（不可变共享结构）、experience_submission（本工单来源）、knowledge_experience_version（候选版本绑定）。不修改历史01–06迁移和ticket_resolution的唯一约束。来源票据与候选版本在物理清理工单时级联清理；演示脚本只清理本次工单，并只删除已无任何来源/绑定的本次关联卡，不删除其他工单共用卡。

400表示schema、来源/正反关系、未核实发布、审核文本不一致等错误；401无身份，403无角色/对象权限，404功能关闭或资源不属于本工单，409版本或幂等冲突、新失败反馈导致审批条件变化。当前结构绑定缺失或哈希损坏时拒绝继续审核，不回退成普通文本审批。

## 本地验收

Docker、Java17和本地Ollama的bge-m3:latest需就绪。全部业务资料为冻结合成素材；测试管理员审核属于工程演示，不是作者/专家的真实质量评分。脚本不调用对话模型或任何付费厂商接口，本地嵌入次数不作为0宣称。

```powershell
Set-Location 'D:\SmartTicket-AI\.workspaces\review'
$env:JAVA_HOME = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1'
$ticketMaven = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16\bin\mvn.cmd'
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
if ($LASTEXITCODE -ne 0) { throw '隔离环境准备失败' }
python '.\08_部署与环境\03_scripts\build-quality-artifact.py' --maven $ticketMaven --offline --temp-dir 'D:\SmartTicket-AI\.tmp'
if ($LASTEXITCODE -ne 0) { throw '构建验收失败' }
python '.\08_部署与环境\03_scripts\verify-staff-experience.py' --with-cards --java (Join-Path $env:JAVA_HOME 'bin\java.exe')
if ($LASTEXITCODE -ne 0) { throw 'G2演示失败，请保留日志' }
Get-Content '.\.tmp\g2-experience-cards.json' -Raw -Encoding UTF8 | ConvertFrom-Json | Select-Object status,stage,unique_base_cards,revised_cards,revision_history,vendor_calls,own_records_cleaned
```

12条基础案例预期得到11张卡：EXP01/09精确重复，EXP01/10条件不同保持分离，EXP11保留错误与纠正，EXP12待核实禁止发布。另加一张修订卡演示0–4版本历史。已发布结构卡应能经原有文本检索返回，撤回/替换后新请求不能引用旧来源。

冻结输入及历史G1报告保持原样，G2实际运行结果写入独立`.tmp/g2-experience-cards.json`。以上是开发/回归验证，不能当作独立盲测、实际客户处理成功或Agent生成准确率提升的证据。
