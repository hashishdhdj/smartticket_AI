# G4：Agent只读图谱检索契约

2026-10-23。本页描述G4实现；真实生成质量和A/B/C对照留到G5，工程链路验收不代替语义评分。

## 工具与配置

新增模型工具 `searchExperienceGraph`，参数严格为 `{"query":"具体问题"}`，1–1000字符，不接受scope、actorId、SQL、节点或候选ID。未启用时不加入工具definitions，直接调用也返回404。它复用现有Agent调用次数、Token、时限、重复调用及身份检查，未新增高权限写操作。

需要同时启用：`smartticket.experience.enabled`、`cards-enabled`、`graph-index-enabled`、`graph-search-enabled`和`smartticket.knowledge.enabled`。四个experience开关默认false。G4没有新增DDL；需先应用G1–G3的MySQL 06–08和PG 03迁移。关闭graph-search不影响原text工具和工单主流程。

没有新增公开的任意图遍历HTTP接口。Agent经`POST /api/agent/runs`使用此工具；G3的`GET /api/knowledge/candidates/{id}/graph`仍只允许ADMIN。普通用户可读取已审核的SHARED_DEMO经验，不会因此获得内部员工记录、原工单或审核回执的读取权限。

仅含工单编号的通用检索请求，先经现有TicketService对象权限校验，再用有权访问的标题检索，同时记录requestedQuery、effectiveQuery和queryOrigin。无法通过改工单编号读取他人标题。不含具体工单的经验查询只读取已发布共享知识。

## 检索和验证

`AgentTools → ExperienceGraphSearchService → KnowledgeSearchService`沿用向量、关键词与RRF融合定位来源，再由`ExperienceGraphReader`验证真实MySQL/PG数据：

1. MySQL当前候选APPROVED、revision等于indexedRevision，工单未删除，经验来源没有新的失败反馈。
2. 当前不可变结构版本、cardHash、textHash、图编译版本和MySQL图回执匹配。
3. PG文本目录/切片使用当前嵌入模型，图目录为ACTIVE，实际节点、关系和逐条证据完整匹配编译结果。
4. 再核对即将返回的PG快照和MySQL可见性，防止前一次验证后恰好切换版本。

每次最多检查6个不同动态来源，返回最多2份经验；每份最多48节点、64关系、128条证据，路径从该来源问题节点出发最多2跳。只读取该候选版本的实际PG证据，不通过共享节点跨来源拼接处理步骤。不会将普通FAQ或没有结构版本的文本凭空变成图。

检索没有另外引入高相似度放行门槛。命中来源说明有候选材料，不保证适用；模型必须根据完整条件判断。图不可用、版本不符或无图时返回空来源及原因/降级标记，提示可用searchKnowledge查询文本，不冒充已解决异常。

## 工具结果与模型上下文

每份工具证据包含真实`sourceId=resolved-ticket/{candidateId}/v{revision}.md#0`、candidateId/revision/cardId、图文哈希、编译版本、scope/factScope、facts、实际路径及逐条证据。facts包含问题、症状、适用条件、顺序步骤、禁止条件、失败反例、待验证假设和“业务效果待核实”。不返回原始员工回复、员工身份、submissionId或管理员回执历史。

审计证据保留实际节点/关系ID；模型获得较短的contextPaths，其端点使用`problem`或`conditions/0`、`steps/1`等字段位置。例如：

```json
["problem", "REQUIRES", "conditions/0", "conditions/0"]
["steps/0", "NEXT_STEP", "steps/1", "stepOrder/0"]
```

每条都属于外层同一个sourceId。节点/关系ID和原始references不是可引用sourceId。

AgentToolContext为整次图工具结果设置6500 UTF-8字节的来源内容额度。每份经验连同全部条件、反例、步骤、路径和范围整份纳入或整份省略；不会截掉后半句的否定词来凑额度。省略来源不进入可引用集合。重复图来源引用已有上下文；若之前仅返回过同来源的文本，首次图调用仍发送完整结构。原API证据不被压缩视图覆盖。

工具序列化后超过现有最终结果长度上限时，来源集合不会被提前登记，避免模型引用实际上没收到的内容。

## 最终输出

图工具和text工具都设置知识searched标记；检索后没有可引用来源时不能返回完整成功。sourceIds仍必须来自实际送达模型的知识来源，伪造节点、关系或reference引用会返回PARTIAL/UNSUPPORTED_SOURCE。

模型回答前要求核对适用/禁止条件、不混用不同来源、不把失败反例当成功步骤、不把假设当确诊或待核实结果当实际成功。引用图来源时，后端在最终输出前再次核对当前版本、实际图和来源健康；已撤回、已变更、开关关闭或校验失败则返回PARTIAL/STALE_GRAPH_SOURCE，不展示那条无法验证的生成答案。该检查保护新回答，不追溯删除已经返回的历史回答，也不声称跨数据库的分布式读事务。

有效引用图经验的回答会由后端附加“已审核合成演示、模拟规则不代表已实现功能、适用前提仍需核实、效果未独立验证”的范围说明和来源。

以上是来源、版本和契约检查，不是自动证明每句自然语言都正确。图谱不赋予新权限；指派仍只能生成提案并经独立确认，解决/关单沿用人工业务接口。

## 验证入口

在当前维护副本根目录，Java17、Docker隔离依赖和本地bge-m3就绪后执行：

```powershell
python '.\08_部署与环境\03_scripts\prepare-phase4.py'
python '.\08_部署与环境\03_scripts\build-quality-artifact.py'
python '.\08_部署与环境\03_scripts\verify-agent-experience-graph.py' --java $ticketJava
```

build入口需要JAVA_HOME=17、Maven在PATH或传`--maven`；Windows短临时路径可传`--temp-dir D:\SmartTicket-AI\.tmp`。具体本机命令见[G4操作指南](../../01_项目说明/20261023_G4Agent图谱检索与来源校验_完成说明及操作指南.md)。

该演示启动仅监听127.0.0.1的离线对话协议替身；替身从真实tool消息复制条件和步骤，Java/Spring AI工具调用、HTTP、RabbitMQ、BGE、MySQL和PG都真实执行。检查发布前不可见、发布后可引用、伪造引用拒绝、回答途中撤回拒绝、新检索不见撤回来源和工单未被修改。替身请求不是DeepSeek调用，不用于计算生成准确率。脚本结束停止临时服务并清理本次随机身份的记录及专用vhost，不删原有数据库卷。
