# G3：真实图谱发布、撤回与补建

G3把G2已审核经验卡编译成PG中的节点、关系和逐条证据，复用现有INDEX/DELETE任务。本阶段提供管理员验收与补建接口；尚未增加Agent图谱工具、面向客户的图谱浏览或图增强效果评测。

## 启用与迁移

先执行MySQL `08_experience_graph_receipt.sql`和PG `03_experience_graph.sql`，前置为G1/G2全部迁移。`prepare-phase4.py`会按序为隔离环境执行。新运行包的动态知识发布/撤回事务会维护PG图目录，因此启用知识工作流前必须完成新迁移，即使暂不构图。

`smartticket.experience.graph-index-enabled=true`启用结构图谱编译，默认false。经验卡创建另由既有experience开关控制；关闭创建不会撤销旧经验。知识工作流须启用，INDEX仍要求直接连接本地Ollama BGE-M3 1024维。图编译为固定Java规则，不调用对话模型、不执行模型生成的SQL/Shell。

关闭图谱索引后保留普通文本RAG。撤回始终清理该来源的图证据，即使构图开关已关闭。回退旧包前应停止知识审核和异步工作流；旧代码不维护新图目录和结构回执，不能继续改写已构图候选。保留数据库表与卷。

## 图如何构建

| 经验内容 | 节点/关系 |
|---|---|
| 问题类型 | PROBLEM |
| 症状 | HAS_SYMPTOM → SYMPTOM |
| 适用条件 | REQUIRES → CONDITION |
| 正向步骤 | ADDRESSED_BY → STEP；相邻步骤另有NEXT_STEP |
| 禁止条件 | CONTRAINDICATED → CONDITION |
| 失败反例 | HAS_COUNTEREXAMPLE → COUNTEREXAMPLE |
| 待验证假设 | HAS_HYPOTHESIS → HYPOTHESIS，不转成已确认因果关系 |
| 业务效果 | HAS_OUTCOME → “业务效果待核实（非独立验证）”，不生成客户成功结论 |

节点按SHARED_DEMO＋类型＋完整规范化文本确定身份，关系按scope＋起点＋关系类型＋终点确定身份，均使用稳定SHA-256。保留否定、数值和版本；相同标签但不同节点类型不合并。相同步骤可以在序列里重复，逐次证据保留字段位置；存在重复或回环不代表工具可以无限遍历。

图以`experience-graph-v1`固定规则编译。每条证据包含经验字段、序号、事实范围、资料支持标签和待审核引用；发布目录同时记录candidateId/revision、cardId/submissionId、cardHash/textHash/graphHash及编译版本。不能只看到两个概念连线就认定存在普适因果关系。

同一节点或关系可被不同经验来源支持。每次查询仍按来源分组，条件和步骤须来自同一有效经验版本；不能把不同来源的条件拼接成一条操作流程。G4工具也必须沿用该边界。

## 事务与恢复

```text
读取并验证MySQL审核版本、经验结构及健康依据
→ 编译有界节点/边/证据，核对文本与结构哈希
→ 事务外调用本地嵌入（只有文本确需重建时）
→ 同一PG事务：版本围栏 + 文本向量 + 图发布目录 + 节点/边/证据
→ MySQL事务：核对当前版本与租约 + indexedRevision + 图回执 + 任务完成
```

PG失败时文本、目录和图一起回滚，不出现“文本已发布但图只写一半”。MySQL确认暂时失败时，已提交PG数据保留；原任务重试会比较真实图行与编译计划、模型和文本哈希，匹配后补回执，不再次嵌入。

若文本向量已存在，而图缺失或部分证据被删除，补建在锁定现有PG发布版本后修复图，复用原向量。校验比较实际节点、关系、逐条证据和目录，不仅检查一行“成功”标记。身份冲突、未知内容或哈希异常拒绝使用；并非任意数据库损坏都可以自动修复。

MySQL新版本、审核状态、删除状态和失败反馈仍是可见性权威。撤回提交后管理员接口不再返回旧图事实，无须等PG删除完成。DELETE在同一PG事务删除旧文本及该候选全部图证据，并保留新版本墓碑；迟到INDEX和旧版本补建不能覆盖它。

撤回只删该来源的证据，不删除其他来源对相同关系的支持。无证据的概念/关系可作为内部词表保留，但没有有效来源就不能返回为知识。普通流程不做全局词表删除；本地演示清理只删本次关联且已不被任何来源引用的词表项。

## 管理员接口

所有接口要求可信ADMIN凭据，HANDLER即使处理该工单也无权访问。来源工单已删除返回404；功能关闭返回404；未知字段、UUID和版本非法按400/409处理。没有新增高权限Agent工具。

### GET `/api/knowledge/candidates/{id}/graph`

只返回这个候选当前版本的图，不提供全局节点枚举。

| data.status | 含义 |
|---|---|
| READY | MySQL审核/索引/图回执、PG文本、真实图行和全部哈希匹配；返回真实PG nodes/edges/evidence及receiptHistory |
| NOT_VISIBLE | 未审核、未索引、已撤回或正向依据出现失败/纠正；事实数组为空 |
| NOT_READY | 文本可用但图目录、实际行或回执未就绪；事实数组为空 |
| NOT_EXPERIENCE | 普通知识没有经验卡结构，禁止凭空构图 |

READY含`candidateId,revision,sourceFile,publication,nodes,edges,evidence,receiptHistory`。关系ID不能冒充RAG sourceId；真实sourceFile来自受版本约束的发布目录。此接口没有调用模型生成答案。

### POST `/api/knowledge/candidates/{id}/graph/rebuild`

```json
{"expectedRevision": 1}
```

用于已审核且文本索引完成的经验：启用图功能后补建历史经验，或修复图证据缺失。返回ALREADY_CURRENT表示无需操作；QUEUED表示已为原INDEX任务写入新投递序号和Outbox；ALREADY_PENDING表示已有待处理/执行中任务，不重复追加重放。

该接口检查当前候选版本、审核状态、indexedRevision和健康依据，记录RETRY审计及knowledge_job_replay_audit。它不修改业务工单或候选revision，不重新创建解决记录。原失败任务retry接口仍只允许FAILED；补建另走受限入口，不放宽旧规则。若PG已提交但MySQL从未确认，应先等待原任务恢复，或对最终FAILED任务使用原重试入口。

## 表与责任

| 库 | 表 | 责任 |
|---|---|---|
| PG | experience_node | 类型化概念词表 |
| PG | experience_edge | 共享关系身份 |
| PG | experience_edge_evidence | 每个候选版本对关系的独立证据 |
| PG | experience_graph_publication | 与文本同事务的图版本目录/墓碑 |
| MySQL | knowledge_graph_receipt | 与indexedRevision同事务提交的图哈希、编译版本和计数回执 |

继续沿用两库显式事务与可靠任务，不宣称使用跨库分布式事务。MySQL回执与PG完成之间可能有短暂窗口；窗口内图不作为READY返回。

## 本地完整演示

在维护副本运行，Docker与本地Ollama bge-m3:latest需就绪。不会访问付费对话或嵌入厂商。不要与其他集成验收同时运行。

```powershell
Set-Location 'D:\SmartTicket-AI\.workspaces\review'
$env:JAVA_HOME = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1'
$ticketMaven = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16\bin\mvn.cmd'
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
if ($LASTEXITCODE -ne 0) { throw '隔离环境准备失败' }
python '.\08_部署与环境\03_scripts\build-quality-artifact.py' --maven $ticketMaven --offline --temp-dir 'D:\SmartTicket-AI\.tmp'
if ($LASTEXITCODE -ne 0) { throw '构建验收失败' }
python '.\08_部署与环境\03_scripts\verify-staff-experience.py' --with-cards --with-graph --java (Join-Path $env:JAVA_HOME 'bin\java.exe')
if ($LASTEXITCODE -ne 0) { throw 'G3验收失败，请保留报错' }
Get-Content '.\.tmp\g3-experience-graph.json' -Raw -Encoding UTF8 | ConvertFrom-Json | Select-Object status,stage,graph_built,graph_database_counts,revision_history,vendor_calls,own_records_cleaned
```

演示使用12条冻结合成案例；特意把重复卡的两个来源分别发布，用于测试共享证据独立撤回。正常业务可直接使用G2复用提示，不要求每个重复案例重新审核。撤回第一来源后，第二来源仍READY；第一来源修订后只有v4可见，v1不再返回。报告中graph_snapshots来自真实管理员HTTP接口与PG数据，不是预画的示意图。

演示结束只清理本次随机身份的数据、图发布及证据，保留其他来源的共用关系。冻结素材、作者复核账本和付费预算账本不改写。PASS是工程生命周期证据，不是客户处理成功、独立盲测或Agent图增强回答质量提升。
