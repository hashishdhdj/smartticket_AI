# 2026-10-20：相似问题中的低分标题筛选

相似问题原先直接使用全部融合命中的标题，因此蓝灯巡检会推荐白屏、验证码等约0.47分的内容。本次对每个推荐标题检查它自己的向量分，达到现有0.55门槛才展示；没有向量分的关键词结果不作为推荐标题。列表去重后保持融合顺序，最多4条，不为凑数量补低分标题。

生成放行门槛仍为0.55。全部来源和完整提示词继续提供给模型，主回答不会因推荐列表为空而被屏蔽。相似问题仍取真实资料标题，不采用模型临时编出的标题。单纯分数筛选不能保证每条语义都相关，本次修复覆盖的是已经观察到的低分尾部问题。

## 验证结果

260项单元、171项集成、62项脚本测试通过，无失败、错误或跳过。新增单元覆盖低分尾部、0.55边界与去重、关键词独有标题、推荐为空时保留主回答。

新JAR使用真实本地BGE-M3和HTTP，借助本地模型替身重放已保存的4条API回答文本；这是推荐展示验证，不是重新调用DeepSeek生成。

| 题号 | 修复前推荐条数 | 修复后推荐条数 | 实际观察 |
|---|---|---|---|
| NW02 | 3 | 3 | 现有标题均达到0.55，保持原列表 |
| NW13 | 3 | 3 | 现有标题均达到0.55，本次不作进一步语义判断 |
| QRP14 | 3 | 1 | 只保留“工单可以修改哪些内容” |
| QDP1 | 3 | 1 | 只保留蓝灯恢复问题，移除白屏、验证码标题 |

4条主回答文本与重放前一致，完整来源和模型上下文保留。临时数据与RabbitMQ命名空间已清理；历史回答、题集和人工复核账本未改变。新增厂商调用0次，累计仍407次。原8题付费复验仅对应其记录的旧JAR；当前新包的证据是完整工程测试与本地重放，不能把旧付费复验称为新包的真实生成效果。

完整执行证据：[本次记录](20261020-rag-recommendations.json)。当前本地包：`08_部署与环境/04_交付产物/SmartTicket-AI-recommendations-20261020-5c5f602bccd1.jar`。当前源码基线已保存上一版快照，构建仍要求精确源码指纹与260/171测试计数，不接受旧包混用。此处记录本地验收，远程核验以本分支对应提交的GitHub Actions为准，见[发布前核验](20261020-github-delivery.md)。

## 本人复跑命令

在同一个PowerShell窗口执行。Docker隔离依赖和本地Ollama应就绪；脚本自动启动临时服务，勿与其他测试并行。

```powershell
& 'D:\SmartTicket-AI\Enter-ReviewWorkspace.ps1'
$ticketJava = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1\bin\java.exe'
$recommendationReport = Join-Path '.\.tmp' ((Get-Date -Format 'yyyyMMdd_HHmmss_fff') + '_recommendations.json')
python '.\08_部署与环境\03_scripts\verify-rag-recommendations.py' --java $ticketJava --budget-ledger 'D:\SmartTicket-AI\.tmp\phase4-budget-ledger.json' --output $recommendationReport
if ($LASTEXITCODE -ne 0) { throw '相似问题检验失败，请保留报错和报告。' }
$recommendations = Get-Content -LiteralPath $recommendationReport -Encoding UTF8 -Raw | ConvertFrom-Json
$recommendations | Select-Object status, vendor_calls, threshold
$recommendations.rows | ForEach-Object {
    [pscustomobject]@{
        id = $_.id
        before = $_.before_similar_questions -join '；'
        after = $_.response.similarQuestions -join '；'
        answer_unchanged = $_.answer_text_unchanged
    }
} | Format-List
```

预期状态 `PASS_LOCAL_RETRIEVAL_HTTP_AND_ANSWER_REPLAY`，vendor_calls为0。蓝灯和PUT题的after均只有一条，answer_unchanged均True。输出文件每次另存，不覆盖旧记录。

## NW13资料缺口

已整理[附件超限处理资料补充草案](../../09_项目依赖与资源/03_知识库素材/NW13_附件超限处理资料补充草案.md)，包含图片、扫描PDF、办公文档的拟补充步骤、适用条件和发布验收要求。草案未进入运行包或向量库，具体方法仍需核实，原NW13“处理不够具体”的问题尚未据此宣布解决或改判通过。
