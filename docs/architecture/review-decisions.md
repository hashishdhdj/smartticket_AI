# 评审整改决策记录

日期：2026-10-19。开发分支：codex/review-engineering。

## 职责包与持久化

生产源码已从 com.smartticket.phase3 迁到职责包：security、analysis、analysis.worker、analysis.api、analysis.persistence、mq.outbox、cache、governance、config、exception。

原共享 Repository 进一步分成分析任务、Outbox、缓存补偿、Agent 执行审计、待确认指派五类持久化组件。Agent 不再借用分析任务 Repository 的原始 JdbcTemplate；Controller 通过查询方法读取任务/Outbox 状态。共享 JsonCodec 只管理序列化，不管理业务表。

保留 MyBatis-Plus 与 JdbcTemplate：前者处理工单实体，后者表达任务领取、租约和 PG 原生检索。两者不是必须强行统一的不同架构；数据归属、事务和封装边界必须清楚。

## 配置兼容

RuntimeProperties 暂时保留 smartticket.phase3.* 属性前缀，资源 phase3.properties 和私有 phase3-local.properties 继续有效。阶段数字不再出现在生产 Java 包名中；旧配置名作为兼容契约保留，后续按安全/Agent/分析/预算域渐进迁移，不能一次替换破坏本机环境与评测脚本。

## 错误契约

移除独立 ApiFailureHandler，由 GlobalExceptionHandler 同时处理业务异常、ApiFailure、参数错误和框架错误。安全 Filter 使用同一 Result.fail 工厂。业务错误码不变，HTTP 状态按场景表达，见[错误契约](../api/error-contract.md)。这是需要客户端适配的变更。

## 事务与模型

任务领取和结果提交分别使用短事务，模型调用放在两者之间。已有 version 条件更新继续作为数据库防线，不叠加 MyBatis-Plus 自动版本插件。线程池超时不等于底层调用一定停止，需要分别验证连接释放、线程回收和客户端超时。

## 发布边界

原本地历史中曾包含凭据，不能合入公开主线。改进从公开安全快照派生，工作副本与原历史分离。历史数据、旧验收和旧 JAR 保持版本含义；源代码有改动后，旧最终 JAR 不代表当前新分支，必须重新构建并核对。
