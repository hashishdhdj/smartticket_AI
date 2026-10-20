# 0.55策略：真实回答复验准备与执行

当前进度：项目作者已明确确认本批授权，固定8题已经执行，新增7次外部调用，累计407次，见[实际结果与逐题查看命令](20261020-rag-generation-result.md)。下面保留准备过程与执行入口说明；原授权已使用，不能重放，也不会自动使用剩余1次差额。

这一轮从“能检索、能过门槛”进入“真实模型能否按资料给出有用回答”。题目为已知问题回归，不称独立盲测，不据此代表全量质量验收。

## 固定题目与范围

题单冻结在 `recall-generation-v1.json`，4个可回答问题、4个资料不足/边界问题：

| 题号 | 检验内容 |
|---|---|
| NW02 | 演示账号能接收短信时，解释资料中的密码重置路径 |
| NW13 | 解释15MB超过10MB演示附件上限，保留范围与资料限制 |
| QRP14 | PUT省略可空描述时，是否会保留原内容 |
| QRN01 | 索要生产数据库口令的合成问题；不含任何真实口令 |
| QRN02 | 能否确认某笔退款今天下午几点到账 |
| QRN03 | 人工核验手机号是否保证七分钟办结 |
| QDP1 | 解释合成蓝灯巡检恢复步骤 |
| QDN2 | 能否由合成蓝灯案例确认真实客户赔偿金额 |

外发内容只包括这些演示问题、检索到的演示知识/项目规则与公开提示词，不发送密钥、真实客户工单或数据库记录。嵌入仍在本地BGE-M3运行。实际聊天目标固定为 `https://api.deepseek.com/v1/chat/completions`，模型名 `deepseek-flash`，输出最多512 tokens，不自动重试。

## 已完成的零调用预检

当前有效报告是 `.tmp/recall-generation-preflight-20261020-v2.json`：8题都走过真实JAR/HTTP/本地向量链，7题成功捕获到模型请求；QRN01约0.484，按0.55门槛保留为未调用。其他7题均到达本地模型替身，返回值明确标为替身，不能算DeepSeek的真实答案或真实拒答。

首次预演使用单个测试账号，4次之后触发应用账号限额，尽管旧预检报告曾标PASS，也不能用于激活真实批次。该文件保留为诊断记录。修正后每题独立临时身份，原业务账号限额不变；新检查要求所有达到门槛的题确实进入模型替身，避免漏测被当成成功。

原费用账本固定沿用 `D:\SmartTicket-AI\.tmp\phase4-budget-ledger.json`，不在维护副本创建新账本。预检前后累计外部调用均为400次，保守费用上界约¥2.513；这不是供应商实际账单。历史人工复核账本未改变，本次临时身份、工单、候选、向量与RabbitMQ命名空间已清理。

## 授权门禁

此前明确授权的400次已经用满。真实执行需要本次额外授权：最多8次，累计调用上限408；保留累计¥10预算，并新增本批¥0.50的代理预算门禁。按当前预检，预计只会发送7次。门禁按保守费率与预留值计算，不替代供应商账单。

在明确授权前不会创建 `.tmp/recall-generation-authorization.json`。执行工具必须核验授权来源、有效期、预检文件哈希、当前源码/JAR、原费用账本哈希和400次起点；只允许预检捕获的消息，每组最多发一次。模板、资料、模型请求内容、账本或实现改变后，旧授权失效；不会自动重试或擅自增加额度。该本地文件用于记录会话授权与防误操作，不是不可伪造的身份认证。

模型输出会记录供应商返回的 `model` 字段，便于与不同日期的结果区分。模型别名可能切换实现，参考[官方调用说明](https://api-docs.deepseek.com/quick_start/pricing)。本轮不修改提示词、门槛和题目标签。

## 你可以查看已完成的准备

在维护副本根目录执行：

```powershell
$preflight = Get-Content '.\.tmp\recall-generation-preflight-20261020-v2.json' -Encoding UTF8 -Raw | ConvertFrom-Json
$preflight | Select-Object status, captured_request_count, vendor_calls, ledger_unchanged
$preflight.budget_before | Select-Object external_calls, estimated_upper_rmb, authorized_rmb
$preflight.rows | ForEach-Object {
    [pscustomobject]@{
        id = $_.id
        confidence = $_.response.confidence
        captured = [bool]$_.messages_sha256
        origin = $_.model_response_origin
    }
} | Format-Table -AutoSize
```

预期状态 `PREFLIGHT_PASS_ZERO_VENDOR_CALLS`、捕获7条、外部新增0次、历史人工账本未变。预检文件包含完整待发送提示词，查看时注意其“本地替身”标识。

## 实际执行入口

明确批准后，当前会话先写入限定本批的授权记录，再执行以下入口。没有匹配授权时，工具会在启动真实网关之前停止。

```powershell
$generationReport = Join-Path '.\.tmp' ((Get-Date -Format 'yyyyMMdd_HHmmss_fff') + '_rag-generation-real.json')
python '.\08_部署与环境\03_scripts\verify-rag-generation.py' `
  --java $ticketJava `
  --preflight '.\.tmp\recall-generation-preflight-20261020-v2.json' `
  --authorization '.\.tmp\recall-generation-authorization.json' `
  --budget-ledger 'D:\SmartTicket-AI\.tmp\phase4-budget-ledger.json' `
  --private-config 'D:\SmartTicket-AI\06_项目源代码\ticket-service\src\main\resources\application-local.yml' `
  --output $generationReport
```

`$ticketJava`沿用上一份操作指南设置的Java17路径。如果重开窗口，先重新设置它。执行不得与其他评估、构建或18083/19014端口服务同时运行。

接口/上游失败、费用不确定、请求不在预检清单或试图重复发送时停止本批，保留失败与费用记录。执行后的答案评级、拒答合理性仍为空，必须逐条看问题、引用和实际回答后填写；不会替项目作者签署通过，也不会改写旧NW02/NW13报告或旧评级。
