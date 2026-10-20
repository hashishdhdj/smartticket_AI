# 2026-10-20：0.55策略真实回答复验

后续已对低分相似问题完成源码修复和本地重放，见[推荐筛选结果](20261020-rag-recommendations.md)。本页与JSON保留原付费执行版本，不改写为后续新包的真实生成结果。

经项目作者在当前会话明确确认，执行固定8道已知题回归。使用同一份已核验JAR，模型名deepseek-flash，本地BGE-M3嵌入，不修改提示词、题目标签或门槛，不自动重试。完整逐题响应见[执行记录](20261020-rag-generation-result.json)。

## 实际结果

| 题号 | 实际状态 | 可观察结果 |
|---|---|---|
| NW02 | ANSWERED | 给出忘记密码、注册手机号短信验证及新密码格式，保留演示范围 |
| NW13 | ANSWERED | 给出10MB限制并说明非本项目附件能力；具体处理方式仍不足 |
| QRP14 | ANSWERED | 约0.575的来源进入真实生成；明确PUT全量替换、可空描述可能被清空 |
| QRN01 | BELOW_THRESHOLD | 约0.484，未调用模型；不能计为模型成功拒答 |
| QRN02 | MODEL_REFUSED | 转人工，没有向用户返回未经证实的退款具体到账时刻 |
| QRN03 | MODEL_REFUSED | 转人工，没有向用户承诺七分钟完成核验 |
| QDP1 | ANSWERED | 回答先核对租约标记、再排队和查看回执，并保留合成场景范围 |
| QDN2 | MODEL_REFUSED | 转人工，没有向用户给出不存在于资料中的赔偿金额 |

4个正例均生成回答；4个负例中3个是模型语义拒答，1个是门槛拦截。检索降级0、上游失败0，7次供应商响应均正常结束。**这是一批已知题的行为观察，不等于独立题集准确率或整体质量验收通过。** 原始模型拒答正文没有单独采集，因此只能评价应用最终隐藏建议并转人工的行为，不能评价被隐藏的措辞。

## 费用、授权与清理

新增外部调用7次，累计从400增至407，低于本次授权的408。代理保守费用上界增加约¥0.0413，累计约¥2.5542，均在本批¥0.50与累计¥10的代理预算门禁内；代理估算不是供应商实际账单。

原累计账本继续保存在原项目 `.tmp/phase4-budget-ledger.json`，没有清零或另开新账本。模型返回的model字段为deepseek-flash。临时服务、测试身份配置、合成工单/候选/向量和RabbitMQ命名空间已清理，历史148条人工复核账本未改变。授权绑定了执行前账本哈希，执行后不再匹配，不能重放；不会自动消费未用的1次差额。

## 技术观察与下一步

1. QRP14实际证明了本次放宽策略的价值：来源正确但分数约0.575的题，现可交由模型解释全量更新规则。NW02当前分数约0.632，本来已高于0.63，不能把它的本次回答全归因于降门槛。
2. NW13仍只有“自行处理到10MB以内”的建议。原资料没有更具体的方法，应先由作者决定业务允许的方式并补充经过核实的资料；不靠让模型补写未确认规则来消除这个缺口。旧NW13的本人PARTIAL不直接迁移为新响应的评级。
3. QDP1主回答保留了范围和步骤，但相似问题仍包含“工单白屏”“验证码错误”等约0.47的低相关尾部召回。下一项可改进相似问题筛选，并用现有本地替身和检索证据回归，优先不增加付费调用。
4. 继续核对新响应的依据、完整性和有用性，作者评分保持PENDING。旧148条逐题复核、真正独立题集与本人演示仍是单独的未结项。

## 本人查看新响应

在维护副本根目录执行：

```powershell
$generation = Get-Content '.\docs\evaluation\20261020-rag-generation-result.json' -Encoding UTF8 -Raw | ConvertFrom-Json
$generation.machine_summary | Format-List
$generation.rows | ForEach-Object {
    [pscustomobject]@{
        id = $_.id
        question = $_.case.query
        status = $_.response.statusReason
        answer = $_.response.suggestion
    }
} | Format-List
```

逐题展开问题、原文参考、完整来源及实际回答：

```powershell
$generation.rows | Where-Object id -eq 'NW02' | ConvertTo-Json -Depth 40
$generation.rows | Where-Object id -eq 'QRP14' | ConvertTo-Json -Depth 40
```

这是当前新响应；`quality_review.py show`仍按其哈希绑定查看旧报告，二者不混用。执行记录中的answer_grade、refusal_correct_grade仍为空，技术观察不冒充作者或专家评分。本页保留真实执行时的本地验收快照；代码发布后的远程核验以对应提交的GitHub Actions为准。
