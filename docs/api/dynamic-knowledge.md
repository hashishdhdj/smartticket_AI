# 动态知识操作指南

本功能将已解决的合成演示工单变成**待审核知识**，管理员审核发布后，才进入现有 RAG/Agent `searchKnowledge` 检索。不自动学习所有工单，不调用收费生成模型提炼。当前只允许 `SHARED_DEMO`，不支持真实客户资料或私有知识权限隔离。

## 先准备环境

在当前维护的仓库根目录执行，Java 使用 17。与其他集成测试串行运行，保留既有 `.tmp/phase4.env`：

```powershell
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
mvn -B -f '.\06_项目源代码\ticket-service\pom.xml' -Punit test
```

准备脚本只操作 `smartticket-stage4` 隔离项目，重复应用幂等迁移，包括 MySQL `05_reviewed_knowledge.sql` 与 PG `02_knowledge_publication.sql`。旧数据库不会因重启容器自动执行新 SQL，须显式应用迁移；不要删除卷解决迁移问题。

```powershell
Set-Location '.\06_项目源代码\ticket-service'
mvn -B -Pintegration '-Dspring.config.additional-location=file:../../.tmp/phase4-integration.properties' package
Set-Location '..\..'
python '.\08_部署与环境\03_scripts\verify-dynamic-knowledge.py'
```

最后一条要求 `JAVA_HOME` 指向 Java 17、Ollama `http://127.0.0.1:11434` 已运行且已有 `bge-m3:latest`；也可以传 `--java 'Java17的完整可执行路径'`。默认占用空闲端口 18087，脚本启动当前 JAR、使用随机演示身份、本地嵌入和真实 MQ，完成审核前隐藏、权限拒绝、发布、换问法检索、撤回、审计及清理。报告在忽略目录 `.tmp/knowledge-local-smoke.json`，启动日志也只保存在该目录。

Windows 若测试出现 `Unable to establish loopback connection`，在上述 Maven 测试命令添加参数（先创建这个短临时目录）：

```powershell
'-DargLine=-Djava.io.tmpdir=D:\SmartTicket-AI\.tmp -Djdk.net.unixdomain.tmpdir=D:\SmartTicket-AI\.tmp'
```

手动运行服务时，在隔离配置上启动 `ollama` profile，并显式覆盖：

```text
--smartticket.ai.enabled=false
--smartticket.phase3.record-tasks=false
--smartticket.phase3.async-enabled=false
--smartticket.knowledge.enabled=true
--smartticket.knowledge.async-enabled=true
--smartticket.vector.embedding.base-url=http://127.0.0.1:11434
--smartticket.vector.embedding.api-key=ollama-local
--smartticket.vector.embedding.model=bge-m3:latest
--smartticket.vector.embedding.send-dimensions=false
--smartticket.vector.index-on-startup=false
```

应用默认关闭知识工作流。只打开 enabled 而不打开 async-enabled 可以保存待处理任务，但不会自动消费。`ollama-local` 是客户端占位符，不是密钥。工作器只允许本地 Ollama/BGE-M3 地址和 1024 维向量，故障不会自动切换到云端。

## 接口按顺序使用

以下 JSON 均通过 `Content-Type: application/json` 提交，每次携带对应角色的私有 `X-API-Key`；不把真实凭据写进文档。现有创建和指派接口不变，先把工单分配给处理人，状态为 PROCESSING。

1. 处理人调用 `POST /api/tickets/{ticketId}/resolve`，增加可选 solution：

```json
{"operatorId":200,"remark":"演示故障已解决","solution":"先核对演示服务连接配置，再重建缓存连接并验证读写恢复。"}
```

solution 去除两端空白后须 10–2000 字符，与工单状态更新、审计、解决快照、DRAFT 任务和 Outbox 同一事务提交。未启用功能时传 solution 返回 503 并回滚；旧请求不传 solution 仍可结单。取消关闭不会产生成功知识。

2. 创建人、该工单处理人或管理员调用 `GET /api/tickets/{ticketId}/knowledge`，获取 resolutionId 和候选状态。异步生成前 state 可以为空，稍后为 DRAFT。管理员可调用 `GET /api/knowledge/candidates?limit=20`，以及 `GET /api/knowledge/candidates/{resolutionId}` 查看问答和任务。候选 ID 与解决记录 UUID 相同；原工单描述不自动进入共享向量库。

3. 管理员人工核对事实、删除敏感信息，调用 `POST /api/knowledge/candidates/{id}/approve`：

```json
{
  "expectedRevision":0,
  "question":"演示服务缓存连接中断应如何恢复？",
  "answer":"先核对演示服务连接配置，再重建缓存连接并验证读写恢复。仅适用于合成演示工单。",
  "scope":"SHARED_DEMO",
  "redactionConfirmed":true
}
```

question 为 5–500 字符，answer 为 10–1500 字符。明显邮箱、手机和凭据会被拒绝，但正则不能替代人工脱敏。审批返回 APPROVED、revision=1；这仅表示审核成功。待 `indexedRevision == revision` 且 INDEX 任务 SUCCEEDED，才表示本版本已可检索。审核前检索不到。

4. 调用现有 `POST /api/ai/knowledge/answer`：

```json
{"query":"演示服务与缓存的连接断开了，恢复时先检查什么？"}
```

来源形如 `resolved-ticket/{UUID}/v1.md`，摘要明确“已审核的合成工单经验，仅限演示”。Agent 的现有 searchKnowledge 工具也通过同一检索链获取来源，无新增写知识工具。关闭生成模型时仍可看到 sources，`needHuman=true`、`statusReason=AI_UNAVAILABLE` 表示未生成回答，不能把它算成回答质量验收。

5. 未通过审核时，管理员对 DRAFT 调用 `POST .../{id}/reject`；下线已发布版本时调用 `POST .../{id}/retract`。请求都为：

```json
{"expectedRevision":1}
```

reject 首次应使用 revision=0；retract 首次发布后使用 revision=1。状态变更使 revision 增加，撤回会创建 DELETE 任务。撤回提交后的检索目录检查即屏蔽该候选，即使 PG 暂时无法删除；PG 恢复后清理切片，保留新版本墓碑抵挡迟到的旧发布。历史已经返回的答复不会追溯撤销。修改后重新发布可用最新 revision 再次 approve。

## 失败时检查什么

管理员 detail 返回 jobs：jobId、kind、revision、status、attempts、totalAttempts、replays、errorCode。DRAFT/INDEX/DELETE 是任务种类，SUCCEEDED 是任务完成状态；APPROVED 是候选审核状态，两者不能混为一谈。

- Outbox 发送失败仍为待发送；恢复后调度器补发，收到路由和发布确认后标记 SENT。消息只包含标识、版本与追踪 ID，不带问答正文。
- 默认每轮最多尝试 3 次，失败后约 5 秒、30 秒退避；租约默认 90 秒。过期任务会恢复，旧租约不能提交回执。消费者数据库故障时重新入队并暂停约 30 秒，避免无间隔重试。
- 正常处理失败耗尽时进入 FAILED 并拒绝到独立死信队列 `smartticket.knowledge.workflow.dead`；进程崩溃导致租约耗尽可由恢复器直接标记 FAILED，未必有对应死信。查任务表才是判断失败的依据。
- 管理员修复故障后，调用 `POST /api/knowledge/jobs/{jobId}/retry`，请求 `{"expectedRevision":1}`（DRAFT 用 0）。只有 FAILED、版本和当前发布/撤回状态匹配才能重放。每轮 attempts 重置，总尝试次数保留，并记录操作者和重放前计数；重复重放或过期请求返回 409。
- PG 成功、MySQL 回执失败时，按来源/版本/正文哈希/模型核对已提交索引，补回执而不重复计算嵌入。较新版本已到达时，旧任务标记 STALE，不能覆盖新版本。

如更换嵌入模型，需对所有静态和动态来源重建同一模型空间。当前工作器会拒绝混合模型，不能只改模型名继续检索。首版每条有长度上限的问答对应一条切片，更新在 PG 事务内替换旧版本全部切片。
