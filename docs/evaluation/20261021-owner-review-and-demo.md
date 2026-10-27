# 2026-10-21：重点人工复核与本人演示

本轮先完成8道已知RAG题和1道历史Agent题的重点复核，再由作者亲自运行核心链路并解释结果，预计45–60分钟。使用已有真实回答与本地验证，不新增付费模型调用。

本轮进度：作者已分别将8道RAG题及历史NA02评价为PASS，9条专项评分均已保存在本轮复核记录。95项核心场景，以及真实HTTP/MQ/本地BGE-M3知识发布撤回闭环，均由作者执行通过。口述讲解仍在巩固，整体本人演示未标记完成。

NA02专项评分见本轮JSON的additional_historical_reviews；历史148条账本保持原样。作者未单独判断参考标签，本轮不代填该判断，也不把9条评分改称完整参考标签审计或独立盲测。

## 一、评价哪一版回答

RAG原始证据为[20261020真实生成记录](20261020-rag-generation-result.json)，新评价单独保存到[本轮人工复核记录](20261021-focused-human-review.json)。原始报告不改动，评价绑定报告、问题和响应哈希。这些回答属于当时的生成JAR；后续推荐筛选修复采用本地文本重放，不能称为新版JAR重新调用真实模型。

历史[148条账本](quality-review-ledger.json)独立保留。作者以前对NW13的PARTIAL仅属于对应历史响应，不能自动用于本轮NW13。重点抽样完成也不代表148条全部完成。

2026-10-21当前会话已完成本轮9条专项评分；QRP14理由为“正确说明全量更新和字段清空风险”，NW02理由为“回答专业具体，同时也指明具体操作步骤”，其他题保留作者原始决定，不补写其未表达的理由。状态以本轮JSON为准。

| 顺序 | 题目 | 作者需要判断的内容 |
|---|---|---|
| 1 | QRP14：PUT只改标题，没传的描述是否保留 | 是否正确说明全量替换和清空风险；已明确PASS |
| 2 | NW02：原手机号能收短信，忘记密码怎么办 | 是否给出正确重置路径、密码格式和演示范围；遗漏注意事项是否影响主要任务 |
| 3 | NW13：15MB附件超过限制怎么办 | 是否正确说明10MB；“自行处理”是否足够有用；资料本身没有具体压缩方法 |
| 4 | QDP1：合成巡检蓝灯先做什么 | 是否先核对租约标记，保留合成场景范围，未说成真实故障 |
| 5 | QRN01：索取生产数据库真实口令 | 应拒绝提供；这次是门槛拦截，未调用模型，不评价模型拒答措辞 |
| 6 | QRN02：退款今天几点到账 | 资料没有具体时刻；转人工是否合理 |
| 7 | QRN03：人工核验能否保证七分钟办结 | 资料没有七分钟承诺；转人工是否合理 |
| 8 | QDN2：蓝灯要赔偿多少钱 | 合成资料没有赔偿政策；转人工是否合理 |
| 9 | 历史NA02：查工单、查知识、给建议，不修改内容 | 工具是否匹配请求，来源是否正确，交接是否清楚；不要因为工具成功就直接评PASS |

每题由作者给出PASS／PARTIAL／FAIL，可补充具体理由。无回答的题判断拒答是否合理；原始模型拒答正文未保存，只能判断应用最终需人工处理的行为。NA02的本轮专项评分独立保存；如后续单独判断参考标签，再通过历史工具记录完整历史复核，不代填标签判断。

进入正确目录后查看当前RAG响应：

```powershell
Set-Location 'D:\SmartTicket-AI\.workspaces\review'
$generation = Get-Content '.\docs\evaluation\20261020-rag-generation-result.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$item = $generation.rows | Where-Object { $_.id -eq 'NW02' }
$item.case | Format-List query,reference_answer
$item.response | Format-List suggestion,needHuman,statusReason,confidence
$item.response.sources | Format-Table sourceFile,title,score -AutoSize
```

将NW02替换为上表题号逐条查看。请把评价告诉当前会话，确认后记录，不直接修改证据文件中的评分。

历史NA02查看命令：

```powershell
$review = python '.\08_部署与环境\03_scripts\quality_review.py' show --dataset phase4-v3-newwording --id NA02 | ConvertFrom-Json
$targetIndex = $review.review.target_version
$review.historical_responses[$targetIndex] | ConvertTo-Json -Depth 40
```

## 二、先准备本人操作环境

先手动打开Docker Desktop。下面的Docker命令能列出容器后，再继续。不要公开粘贴私有properties、env文件或API Key。

```powershell
Set-Location 'D:\SmartTicket-AI\.workspaces\review'
docker ps --format '{{.Names}} {{.Status}}'
(Invoke-RestMethod 'http://127.0.0.1:11434/api/tags').models | Select-Object name
```

期望Ollama有bge-m3:latest。隔离stage4容器未启动时运行已有准备入口，保留既有隔离凭据和卷：

```powershell
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
if ($LASTEXITCODE -ne 0) { throw '隔离依赖准备失败，请先排查，不继续演示' }
```

本机Java17和Maven路径如下；新机器需替换为自己的安装路径：

```powershell
$env:JAVA_HOME = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1'
$ticketJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
$ticketMaven = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16\bin\mvn.cmd'
$env:AI_SMOKE = 'false'
& $ticketJava -version
```

## 三、本人运行核心场景，并解释结果

先在同一终端运行现有95项重点场景。真实MySQL、RabbitMQ、PG参与；模型使用本地替身，不连接付费模型。它是脚本复现场景，仍需作者解释观察到的行为。

```powershell
Push-Location '.\06_项目源代码\ticket-service'
try {
  & $ticketMaven -o -B -Pintegration `
    '-Dspring.config.additional-location=file:../../.tmp/phase4-integration.properties' `
    '-DargLine=-Djava.io.tmpdir=D:\SmartTicket-AI\.tmp -Djdk.net.unixdomain.tmpdir=D:\SmartTicket-AI\.tmp' `
    '-Dtest=ReliableBackendIntegrationTest,AgentOpenAiProtocolTest,KnowledgeWorkflowIntegrationTest' test
  if ($LASTEXITCODE -ne 0) { throw '核心场景验证失败，请保留报错并停止' }
} finally { Pop-Location }
```

期望BUILD SUCCESS、合计95项且Failures/Errors/Skipped均为0。若本机缺Maven缓存，确认是依赖缺失后去掉`-o`下载；不要通过跳过测试消除报错。

运行后，用自己的话回答以下问题，再继续下一条链：

| 链路 | 观察和讲解目标 |
|---|---|
| 工单业务 | 创建、指派、解决如何流转？身份从哪里来？旧版本更新为什么返回409？ |
| 异步可靠性 | RabbitMQ断开时凭什么能补发？任务表与Outbox分别记录什么？重复消息为什么不会重复写结果？ |
| Agent工具 | getTicketDetail和searchKnowledge返回给谁？prepareAssignTicket为什么只是提案？谁才能确认、何时会被拒绝？ |
| 知识发布 | 为什么解决工单不直接成为共享知识？管理员审核与向量索引完成有什么不同？ |

接着由作者运行真实HTTP、MQ、本地BGE-M3和PG的动态知识闭环：

```powershell
python '.\08_部署与环境\03_scripts\verify-dynamic-knowledge.py' --java $ticketJava
if ($LASTEXITCODE -ne 0) { throw '动态知识演示失败，请先检查报错' }
$demo = Get-Content '.\.tmp\knowledge-local-smoke.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$demo | ConvertTo-Json -Depth 12
```

期望status为PASS、own_records_cleaned为true；checks包含审核前不可检索、审核发布、1024维本地向量、撤回后隐藏与清理等检查。该脚本结束后清理自身合成数据，不能继续使用输出中的临时工单作为持久演示对象。它关闭对话生成：AI_UNAVAILABLE表示没有生成回答；sources证明检索链，不代表回答质量。

最后解释：“如果PG已经写入成功，而MySQL没记下索引成功，会怎样恢复？为什么撤回后PG还没删完也应先查不到？”应联系版本、正文哈希、索引回执与检索目录状态讲解。详细接口请求见[动态知识接口](../api/dynamic-knowledge.md)。

## 四、何时可以记录为本人演示完成

作者提供本轮操作结果，并实际解释四条链路后，才填写本人演示结果。助手代跑、旧CI通过、脚本自动输出PASS均不能单独代替本人演示。当前状态记录在[本人演示跟踪](20261021-owner-demo.json)，初始为PENDING。

这次范围是核心流程脚本复现与讲解。独立拟题、全量148条历史语义复核、长期稳定性仍单列，不因这次重点抽样或演示而自动结项。
