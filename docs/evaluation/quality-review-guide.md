# R06：质量复核与演示操作

本轮自动部分已覆盖当前产物核验、新措辞检索评估、真实 HTTP 集合和可填写的历史回答复核工具。**R06 的整体质量签署仍未完成**：148 条本人判断、真正独立拟题及生成回答效果不能由自动测试代签。

2026-10-20 后续策略更新：当前源码默认生成放行门槛从0.63降至0.55，让中等相似度的资料进入模型语义判断；见[放宽说明](20261020-rag-recall-first.md)。下面原始R06检索指标仍按冻结的0.63解释，旧回答和人工评价保持原版本。相似度不是答案准确率，生成效果仍须单独验证。

要按命令亲自检查新版0.55门槛，请使用[逐步检验指南](rag-recall-verification-guide.md)：包含正确目录、Java17、依赖准备、NW02/QRP14真实接口检查与整批回归。

最新相似问题筛选已通过新包本地检索与回答文本重放，查看[筛选说明和复跑命令](20261020-rag-recommendations.md)。当前构建基线为260单元、171集成；历史256或252的记录属于各自版本。

2026-10-20 收尾时，作者已在会话内将 NW13 的最终历史响应评为 PARTIAL：大小与范围正确，但处理建议不够具体。账本当前已复核1条、待复核147条；后续以 status 命令的实时计数为准。

以下命令在维护副本根目录执行。本机为 `D:\SmartTicket-AI\.workspaces\review`，原根目录可运行 `Enter-ReviewWorkspace.ps1` 进入；新克隆仓库直接进入仓库根目录即可。不要从保留的旧源码目录启动当前版本。

## 先用十分钟判断一条回答

无需模型调用。脚本输出使用 JSON Unicode 转义，兼容 Windows PowerShell 的默认编码；`ConvertFrom-Json` 会还原中文，无需设置 Python 输出编码。显示 NW13 的完整问题、各次响应、引用与检查：

```powershell
python '.\08_部署与环境\03_scripts\quality_review.py' status
$review = python '.\08_部署与环境\03_scripts\quality_review.py' show --dataset phase4-v3-newwording --id NW13 | ConvertFrom-Json
$targetIndex = $review.review.target_version
$review.historical_responses[$targetIndex] | ConvertTo-Json -Depth 40
$responseHash = $review.review.versions[$targetIndex].response_sha256
```

如果旧版本曾出现中文乱码、`ConvertFrom-Json` 提示“应为冒号或右花括号”，请使用修正后的脚本重新执行上述赋值命令；之前失败的 `$review` 不可继续使用。此问题发生在终端输出转换环节，不需要重建账本或重跑模型。NW13 已有本人的 PARTIAL 评价，无需重复填写；下一条可将命令中的 `--id NW13` 改为 `--id NW02`。

先看 case 的问题、参考答案和 expected_sources，再看 response 的实际回答和 sources。不要只看 checks、SUCCEEDED、PARTIAL 或相似度。来源正文可直接打开 `06_项目源代码/ticket-service/src/main/resources/knowledge`，但历史原文优先以该报告内参考及证据为准；当前知识可能在旧评测之后修改过。

逐项问自己：

1. 参考标签本身对吗？问题要求的事实能否从原文得到？
2. 回答是否保留限制条件？有没有把“模拟功能”说成本项目真实能力？
3. 每个关键结论有没有依据？没有回答时，是合理拒答、门槛漏答还是基础设施失败？
4. Agent 是否完成用户目标、交接是否有用、是否真正写入了业务？工具成功不代表整体任务完成。

完成判断后才能运行下面的**填写示例**，把署名、等级和理由改成你本人的决定：

```powershell
python '.\08_部署与环境\03_scripts\quality_review.py' record `
  --dataset phase4-v3-newwording --id NW13 --response-sha256 $responseHash `
  --reviewer '你自选的评审标识' --grade PARTIAL --label-correct yes `
  --refusal-correct na --notes '请替换为你的判断，指出原文依据、回答保留的限制和仍未满足的问题。'
python '.\08_部署与环境\03_scripts\quality_review.py' status
```

grade 可选 PASS / PARTIAL / FAIL / NOT_APPLICABLE；label-correct 为 yes/no；refusal-correct 为 yes/no/na。备注至少十个字符，应具体指出依据和问题。“已复核”表示完成判断，**失败评级也算已复核，不代表通过验收**。输入者署名是自行声明，不是身份认证或第三方专家签字。

新账本为 `docs/evaluation/quality-review-ledger.json`。它记录每个历史版本的报告/问题/响应哈希，目标为队列明确列出的最后一次复验；早期失败保留。更正评语会追加决策，不覆盖上一条。旧回答哈希、报告被改动或直接伪造 REVIEWED 都会被拒绝。init 遇到已有账本只校验，不清空决定。请一次只运行一个填写进程，避免两个评审同时写同一本地文件。

优先顺序：NW13 → NW02 → NW17 → NW18 → NW19 → NW08 → NA02 → RH09。八条技术对照在[单独记录](20261019-quality-desk-review.json)，只作为定位线索；148 条本人状态仍以账本为准。旧 `.66` 响应不能因当前阈值 `.63` 就改称“现在已生成正确答案”。

## 本地检索评估如何复现

新题在首次执行前冻结于提交 `643efb9`：静态16正例/8负例；动态4正例/2负例，另测审核前、撤回后不可见。这是 AI 依据语料拟定的新措辞留出题，**不是独立第三方盲测**。首次结果已看到，后续不得用它调参后仍称独立测试。

```powershell
python '.\08_部署与环境\03_scripts\verify-quality-closure.py' --contracts-only
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
python '.\08_部署与环境\03_scripts\build-quality-artifact.py'
python '.\08_部署与环境\03_scripts\verify-quality-closure.py' --java 'Java17的完整可执行路径'
```

要求 JAVA_HOME 为Java17、Maven在PATH、18083端口空闲、Ollama 11434的bge-m3:latest就绪。build-quality-artifact会核对180份服务文件；当前基线为 `docs/evaluation/current-runtime-baseline.json`，显式记录本次推荐筛选涉及的2份服务文件变更与预期260单元、171集成；上一版0.55基线已另存快照。历史R05/R06的252单元记录保持原样。完整测试通过并检查包内私有配置后，才记录本次JAR哈希至忽略的 `.tmp/quality-artifact-build.json`。可传 `--maven 'mvn.cmd完整路径' --offline` 使用本机缓存；首次下载依赖不要加offline。新克隆也通过此入口构建，避免被打包时间导致的不同JAR哈希阻断。

评估接受原已核验JAR，或上述当前源码完整构建/测试通过且哈希匹配的本地记录；缺失证据、源文件改变、漏测/跳过或混用旧JAR都会被拒绝。运行时Java/配置发生变动须另建验收基线，不在本轮脚本中自行放宽。该本地记录用于防止误用产物，不是签名证明；有本机写权限的人仍能改文件。

脚本固定本地嵌入、关闭对话与旧异步模型分析、创建本次专用 RabbitMQ vhost。静态种子按当前 JAR 内容哈希做本地增量检查；真实数据库当前为30个小节、32条切片。评估后清理本次随机身份创建的工单、任务、回放、知识和 vhost，不删除其他数据或卷。不要与既有集成/评估脚本并行运行。原始输出保存在 `.tmp`，不会重写旧运行。

指标分别解释：

| 指标 | 含义 |
|---|---|
| Hit@3 | 正例前三条中是否出现任一目标来源 |
| 宏平均 Recall@3 | 各正例召回目标来源比例后取平均；本批每题一个目标，因此与 Hit@3 相等 |
| MRR@3 | 第一个目标来源排名的倒数取平均，衡量排序 |
| 正例门槛覆盖率 | 有来源的正例中，置信度是否达到0.63 |
| 负例门槛放行率 | 缺少所需具体事实的负例中，有多少仍达到0.63；是潜在生成放行，不是已经错误回答 |
| 答案支持度/Agent完成率/工具越权率 | 本轮不启用生成，保持空值，不能从检索结果推导 |

关闭对话时 needHuman 全为 true，因此不能用它计算拒答质量。达到门槛但不生成的返回是 AI_UNAVAILABLE，仍提供真实来源。

## 四条链如何演示与讲解

先准备隔离配置，再从服务目录运行下列零厂商调用验收。Java 使用17；Windows 如有套接字路径问题，添加动态知识指南中的短临时目录 argLine 参数。

```powershell
Set-Location '.\06_项目源代码\ticket-service'
mvn -B -Pintegration '-Dspring.config.additional-location=file:../../.tmp/phase4-integration.properties' `
  '-Dtest=ReliableBackendIntegrationTest,AgentOpenAiProtocolTest,KnowledgeWorkflowIntegrationTest' test
Set-Location '..\..'
python '.\08_部署与环境\03_scripts\verify-dynamic-knowledge.py' --java 'Java17的完整可执行路径'
```

这三类现有集成共95项。模型响应由替身/本地协议服务器提供；MySQL、RabbitMQ、PG是真实隔离依赖。最后一条才是当前 JAR 的 HTTP 与真实本地 BGE-M3 闭环。它们不证明当前 DeepSeek 生成效果。

五分钟复述可按以下顺序练习：

1. **业务链，约一分钟**：创建、管理员指派、处理人解决；身份取自凭据，版本不符返回409，审计与更新一起提交。实际 HTTP 集合覆盖15请求35断言。
2. **异步链，约一分钟**：工单、任务、Outbox一起入库。RabbitMQ负责传递，任务表负责进度；断连保留待发送，重复消息通过任务领取和结果唯一性防重。事务外调用模型，规则兜底明确DEGRADED。
3. **Agent链，约一分钟**：模型提出 getTicketDetail、searchKnowledge 等调用，应用检查对象权限和限额。prepareAssignTicket仅生成提案；原管理员独立确认后才能写入，版本变化/过期/跨账号确认被拒。协议测试可观察真实工具结果回给模型替身。
4. **知识链，约一分钟**：solution先生成候选，审核发布才计算本地向量。MySQL与PG各自短事务，通过版本、哈希与回执补偿；撤回先屏蔽新检索，再异步清理PG。
5. **失败与取舍，约一分钟**：说明实际预算死锁修复（INSERT IGNORE锁升级→幂等更新先取排他锁），以及相似度不能保证事实充分。RRF融合排名，当前关键词是pg_trgm而非BM25；双库存业务可靠性与向量能力，但增加一致性补偿成本。

本人演示完成与否仍需你实际运行并复述，本轮脚本执行不能替代这一项。

## 剩余执行顺序

先完成八条重点中的其余7条，再分批处理其余140条；如只验收抽样范围，明确写出覆盖数量和未审项目，不把总状态改为完成。

新一批真正独立题由未看过旧失败题的人拟定，先锁定问题、参考依据和评分准则，再执行。当前 QR/QD 题保留为回归/对照，不改标签去迎合结果。

后续已获明确授权并完成NW02、NW13、QRP14、QRN01、QRN02、QRN03、QDP1、QDN2的真实回归，新增7次、累计407次，见[实际结果](20261020-rag-generation-result.md)。全量语义质量和作者逐题评分仍另行评价。原始R06不调整冻结的0.63；当前策略已更新为0.55，使用 `verify-quality-closure.py --threshold 0.55` 单独执行已知题回归，报告标注为事后回归而非独立评估，不改原数据集或历史报告。该真实批次已结束，不自动消费授权差额；后续调用需另行确认。长期资源稳定性和底层HTTP取消仍未认证；R07会话记忆是可选项，不是本轮质量签署前提。
