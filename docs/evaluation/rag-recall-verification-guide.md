# 0.55门槛：逐步检验命令

全部命令在同一个PowerShell窗口按顺序执行。本指南验证当前配置、运行包、真实本地检索和接口；不调用DeepSeek或其他付费模型。历史复核仍使用 `quality_review.py`，查看旧报告不会重新生成答案。

## 1. 进入正确目录，选择Java17

```powershell
& 'D:\SmartTicket-AI\Enter-ReviewWorkspace.ps1'
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1'
$ticketJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
$ticketMaven = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16\bin\mvn.cmd'
Get-Location
& $ticketJava -version
Test-Path '.\08_部署与环境\03_scripts\verify-rag-admission.py'
```

目录应为 `D:\SmartTicket-AI\.workspaces\review`，Java版本为17，最后一行是True。

## 2. 检查依赖与当前策略

先在桌面确认Docker Desktop已运行。下列准备脚本启动/检查本项目隔离依赖，保留已有隔离配置和凭据；请等它执行结束，不与其他测试同时运行。

```powershell
docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw '请先启动Docker Desktop，等待引擎就绪。' }
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
if ($LASTEXITCODE -ne 0) { throw '隔离依赖准备失败，请保留报错。' }
docker ps --filter 'name=smartticket-stage4-' --format 'table {{.Names}}\t{{.Status}}'
(Invoke-RestMethod 'http://127.0.0.1:11434/api/tags').models | Select-Object name

$policy = Get-Content '.\07_测试与质量\06_阶段测试报告\RAG当前门槛策略.json' -Encoding UTF8 -Raw | ConvertFrom-Json
$policy | Select-Object selected_threshold, previous_threshold
```

MySQL、Redis、PG、RabbitMQ四个隔离容器应healthy；模型列表应有 `bge-m3:latest`；当前门槛0.55、上一版0.63。如Docker、模型或配置有错误，停在这一处处理，不跳过继续。

## 3. 可选：亲自重新构建并跑全套Java检查

本机已有核验的新包，可以直接进入第4步。若想亲自复跑全部260单元、171集成，执行：

```powershell
python '.\08_部署与环境\03_scripts\build-quality-artifact.py' --maven $ticketMaven --offline --temp-dir 'D:\SmartTicket-AI\.tmp'
if ($LASTEXITCODE -ne 0) { throw '构建失败，请保留报错；不要继续使用旧包。' }
```

通过时输出status=PASS、unit.tests=260、integration.tests=171，失败/错误/跳过都为0。重建可能产生新的JAR哈希，以本次构建记录为准。构建日志在忽略的 `.tmp/quality-build-unit.log` 与 `.tmp/quality-build-integration.log`。如果重开窗口，需先重新执行第1步恢复变量。

## 4. 实测NW02与QRP14是否越过门槛

这是新的检验入口，会启动18083端口上的临时服务、调用真实本地BGE-M3和HTTP接口，保存结果后退出并清理本次临时资源。不要另外启动同端口服务，也不要与第3、5步并行。

```powershell
$admissionReport = Join-Path '.\.tmp' ((Get-Date -Format 'yyyyMMdd_HHmmss_fff') + '_rag-admission.json')
python '.\08_部署与环境\03_scripts\verify-rag-admission.py' --java $ticketJava --output $admissionReport
if ($LASTEXITCODE -ne 0) { throw '门槛检验失败，请保留报错和报告。' }
$admission = Get-Content -LiteralPath $admissionReport -Encoding UTF8 -Raw | ConvertFrom-Json
$admission | Select-Object status, threshold, vendor_calls, ledger_unchanged
$admission.rows | Format-Table id, confidence, expected_source_found, admitted_to_generation_path, status_reason -AutoSize
```

预期：

| 字段 | 预期 | 含义 |
|---|---|---|
| status | PASS_ADMISSION_ONLY | 本轮配置、资料与放行检查通过 |
| threshold | 0.55 | 当前门槛 |
| vendor_calls | 0 | 没有外部模型调用 |
| ledger_unchanged | True | 旧回答复核账本未改变 |
| expected_source_found | True | 正确资料确实召回 |
| admitted_to_generation_path | True | 已进入生成流程，未被门槛挡住 |
| status_reason | AI_UNAVAILABLE | 本轮主动关闭对话模型，因此不生成真实答案 |

NW02当前观察分数约0.632，历史分数约0.599；不要要求两者完全相同。QRP14约0.575，更直接体现0.63挡住、0.55放行。分数会随模型/语料状态变化，以你本次实际报告为准；不修改报告凑预期值。

`AI_UNAVAILABLE`在这套关闭对话模型的检验里符合预期。`BELOW_THRESHOLD`才表示被门槛挡住，`VECTOR_UNAVAILABLE`表示检索基础设施出问题。`has_generated_answer=false`表示本次没有生成答案，不能把这一步评价为“DeepSeek回答正确”。

如果想单独展开一道题：

```powershell
$admission.rows | Where-Object id -eq 'NW02' | ConvertTo-Json -Depth 10
$admission.rows | Where-Object id -eq 'QRP14' | ConvertTo-Json -Depth 10
```

## 5. 可选：整批检索与接口回归

第4步通过后再执行。显式传入0.55，默认不传仍会沿用冻结R06的0.63；每次使用新文件名保存，保留旧报告。

```powershell
$batchReport = Join-Path '.\.tmp' ((Get-Date -Format 'yyyyMMdd_HHmmss_fff') + '_quality-055.json')
python '.\08_部署与环境\03_scripts\verify-quality-closure.py' --java $ticketJava --threshold 0.55 --output $batchReport
if ($LASTEXITCODE -ne 0) { throw '整批回归失败，请保留报错和报告。' }
$batch = Get-Content -LiteralPath $batchReport -Encoding UTF8 -Raw | ConvertFrom-Json
$batch | Select-Object status, threshold, vendor_calls
$batch.static_metrics | Format-List count, positive_count, negative_count, hit_at_3, positive_gate_coverage, negative_gate_pass_rate, degraded_count, generated_answer_count
$batch.dynamic_metrics | Format-List count, positive_count, negative_count, hit_at_3, positive_gate_coverage, negative_gate_pass_rate, degraded_count, generated_answer_count
$batch.dynamic_state_checks | Format-Table -AutoSize
$batch.api_collection | Select-Object status, requests, assertions
```

既有观察：静态24题（16正、8负）、动态6题（4正、2负），正例放行率均为1，降级0，生成答案0；审核前隐藏和撤回后隐藏都PASS，接口15请求/35断言PASS。总状态 `EXECUTED_SEMANTIC_REVIEW_PENDING` 表示已执行工程/检索检查，人工语义验收仍待完成。

静态负例放行率曾为0.75，动态为1：这是交给模型判断的比例，不是模型答错的比例，也不能算成功拒答率。当前题集已经看过，只能称回归。

## 6. 下一步如何继续

真实回答复验的工具、8题清单和零调用预检已准备，见[真实回答复验指南](rag-generation-review-guide.md)。

第4、5步仍是零厂商调用检查，不要用它们给旧NW02回答改判“已成功生成”。后续另经明确授权已完成8题真实回归，新增7次、累计407次，见[实际结果](20261020-rag-generation-result.md)。实际生成、作者评分与业务/Agent演示分别记录；本指南中的本地检验命令不会提高额度或发起付费调用。
