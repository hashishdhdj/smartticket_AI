# 一线处理记录与反馈接口（G1）

新增时间：2026-10-23。员工处理记录和结果反馈真实保存到MySQL，供后续经验卡与知识图谱使用。当前尚未提炼经验、构图或新增Agent图谱工具。

## 启用和权限

先执行增量迁移`06_项目源代码/sql/06_staff_experience.sql`，再配置`smartticket.experience.enabled=true`。默认关闭；关闭后接口返回404并保留原记录。启动旧业务不依赖本功能的新表，禁止回退时删除数据库卷。

使用`X-API-Key`认证，作者ID、角色、工单版本和创建时间取自服务端。即使旧配置关闭全局身份检查，这组接口仍要求可信身份。ADMIN可访问所有未删除工单；HANDLER仅可访问当前分配给自己的工单。SUBMITTER不能读写内部记录，包括自己提交的工单。重新分配或逻辑删除后，旧处理人不能继续读取或重放旧请求。

记录是内部工作笔记，不是向客户发送消息，也不是公开留言/聊天接口。提交人对工单详情和操作日志的原有权限保持原语义。员工提交记录不需要管理员逐条审核；只有后续将经验发布为共享知识时才进入既有审核流程。

## 接口

统一前缀`/api/tickets/{id}`，返回原有`{code,message,data}`包装；成功HTTP 200。

| 方法与后缀 | 内容 |
|---|---|
| POST `/interactions` | 追加一条员工回复、排查步骤或纠正 |
| GET `/interactions?afterId=0&limit=20` | 按递增ID分页读取内部记录 |
| POST `/experience-feedback` | 追加一条对原记录的效果反馈 |
| GET `/experience-feedback?afterId=0&limit=20` | 按递增ID分页读取反馈 |

GET返回`data.records`和可选的`data.nextAfterId`；后者有值时用于下一页，无值说明已到尾页。`afterId>=0`，`1<=limit<=100`。各页重新检查当前工单权限。

### 添加记录

```json
{
  "requestId": "b13c5d70-2f69-4b6c-86d3-d54f8bacb323",
  "expectedVersion": 1,
  "kind": "STAFF_REPLY",
  "content": "演示处理：先检查连接配置，再重试并记录结果。",
  "origin": "AI_SYNTHETIC_DEMO",
  "correctsInteractionId": null,
  "evidenceRefs": [{"path": "docs/architecture/example.md", "title": "待核实的引用说明"}]
}
```

`requestId`为客户端生成的规范小写UUID。`expectedVersion`必须与当前工单版本相同。`kind`可为`STAFF_REPLY`、`TROUBLESHOOTING`或`CORRECTION`；只有最后一种必须填写`correctsInteractionId`，该ID必须是同工单已有记录。纠正的来源类型必须与原记录一致，不能把合成材料改标为实际员工经验。原记录不会被覆盖。

`origin`为`STAFF_ENTERED`或`AI_SYNTHETIC_DEMO`，由提交者标记资料来源；身份可信不代表内容已核实。content去除首尾空白后长度5–4000；最多8条引用，path不超过240、title不超过200。引用目前只作为待核实文本保存，不读取路径、不打开网络链接，也不能直接作为Agent可引用的知识来源。后续发布需要独立核查。

返回`id,ticketId,requestId,ticketVersion,authorId,authorRole,kind,content,origin,correctsInteractionId,evidenceRefs,createdAt`，可空字段按现有JSON配置省略。版本是追加时的工单快照，追加操作不会修改工单版本或业务状态。

### 添加反馈

```json
{
  "requestId": "12d25247-61e5-4e6c-a209-d9a151bcf507",
  "expectedVersion": 1,
  "interactionId": 123,
  "outcome": "UNVERIFIED",
  "origin": "SIMULATED_DEMO",
  "comment": "合成反馈：目前只是场景设定，尚未验证实际恢复。",
  "correctionInteractionId": null
}
```

`interactionId`必须指向本工单已有记录。`outcome`为`ADOPTED`（报告已采用）、`EFFECTIVE`（报告有效）、`FAILED`、`CORRECTED`或`UNVERIFIED`。所有结果均为提交者报告，不是系统自动认证的事实。

`origin`为`STAFF_OBSERVATION`或`SIMULATED_DEMO`；合成记录只接受`SIMULATED_DEMO`反馈。comment去除首尾空白后长度5–2000。只有`CORRECTED`必须填写`correctionInteractionId`，且它必须是明确指向本次interactionId的CORRECTION记录。不能用普通回复或另一条记录的纠正冒充。

返回记录ID、工单与版本、可信作者、原记录ID、结果、来源、说明、可选纠正ID和数据库创建时间。失败、待核实和纠正都追加保存，不会自动把工单标为已解决。

## 幂等、并发与错误

每类POST以`工单ID＋当前作者ID＋requestId`去重。相同规范化请求重试返回同一记录；同键但内容或expectedVersion变化返回409，不覆盖旧数据。首次追加必须匹配当前工单版本；已经成功的原请求在工单版本变化后仍可返回原记录，但必须先通过当前权限校验。客户端应保存原requestId和请求体用于网络失败重试。

事务内锁定MySQL工单行，再检查当前处理人、版本及关联记录，完成插入。该锁与重新分配/删除串行，禁止仅凭缓存或请求体作者身份决定权限。事务不调用消息队列或模型。复合外键阻止跨工单关联；唯一索引防止重复追加。

| HTTP | 典型含义 |
|---|---|
| 400 | 字段、UUID、长度、枚举、纠正关系、来源组合或分页非法；未知字段也拒绝 |
| 401 | 无有效身份 |
| 403 | 非员工身份或不是当前处理人 |
| 404 | 功能关闭、工单删除/不存在，或本工单不存在关联记录 |
| 409 | 工单版本过期或幂等键内容冲突 |

不提供修改/删除接口。数据库物理清理工单时级联清理其记录，逻辑删除则保留审计材料并禁止读取。请勿将内部记录自动暴露给工单提交人。

## 本地演示

从维护目录`D:\SmartTicket-AI\.workspaces\review`运行，Docker需要就绪。演示使用隔离smartticket-stage4库、三个随机员工身份和12个合成案例；不会调用DeepSeek或嵌入模型，也不要求Ollama启动。自动启动临时HTTP服务，完成后只清理自己的测试工单。不要与其他集成测试同时运行。

```powershell
Set-Location 'D:\SmartTicket-AI\.workspaces\review'
$env:JAVA_HOME = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1'
$ticketMaven = 'D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16\bin\mvn.cmd'
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
if ($LASTEXITCODE -ne 0) { throw '隔离环境准备失败' }
python '.\08_部署与环境\03_scripts\build-quality-artifact.py' --maven $ticketMaven --offline --temp-dir 'D:\SmartTicket-AI\.tmp'
if ($LASTEXITCODE -ne 0) { throw '构建测试失败' }
python '.\08_部署与环境\03_scripts\verify-staff-experience.py' --java (Join-Path $env:JAVA_HOME 'bin\java.exe')
if ($LASTEXITCODE -ne 0) { throw '员工记录验收失败，请保留日志' }
Get-Content '.\.tmp\g1-staff-experience.json' -Raw -Encoding UTF8 | ConvertFrom-Json | Select-Object status,stage,vendor_calls,real_database_counts_before_cleanup,own_records_cleaned
```

冻结输入和预设反馈保留原样。实际运行ID、断言和数据库计数写入独立`.tmp/g1-staff-experience.json`；报告PASS只说明采集链路通过，不代表合成业务处理实际成功、知识已发布或图谱增强已完成。
