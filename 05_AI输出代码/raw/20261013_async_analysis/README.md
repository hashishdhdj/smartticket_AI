# 异步分析SQL初稿：如何审查与验收

本目录仍为raw初稿；已在独立验证库测试，尚未采纳到正式SQL目录，未修改开发库或Java实体。

## 文件

- 02_async_analysis.sql：增量迁移，在客户端选定目标schema后执行；无USE、DROP、TRUNCATE。为ticket新增version并创建三张表。
- verify_async_schema.sql：只读查看字段、表定义、索引和原记录数量。
- verify_isolated_schema.py：创建唯一命名隔离库，在其中建立现有基础表和合成数据，执行迁移两次并验证约束。读取本地.env密码但不打印，不迁移smartticket。每次运行会保留一个新验证库，已有一次成功证据，无需反复执行。
- 隔离验证结果.md：本次实际通过的检查、验证schema与版本。

## 现在由开发者进行的审查

1. 打开02_async_analysis.sql，对照决策记录，确认task/结果/Outbox各自字段与职责。
2. 找出五个唯一约束：task_id、(ticket_id,input_version,generation)、结果task_id、event_id、(task_id,dispatch_sequence)。说明每个防止什么重复。
3. 阅读顶部version迁移：先查列是否存在，再执行ALTER；如果列已存在，仅跳过加列，不自动修复错误类型。
4. 明白CREATE TABLE IF NOT EXISTS只避免重复建表，不修复已有表结构漂移。每次仍要运行verify_async_schema.sql检查。
5. 明白CHECK约束只检查当前行值，不实现任务合法流转、跨表关联、版本递增或领取幂等；这些由后续Java/条件SQL实现。

数据库客户端可切换到隔离验证结果中所列schema，运行verify_async_schema.sql。首先SELECT DATABASE()确认连接。该文件不插入数据。

## 通过后下一步

将经人工审查的02_async_analysis.sql纳入正式06_项目源代码/sql，再按阶段3A指导第5节检查迁移顺序、备份、开发库执行前后数据量及新环境初始化。不现在同时修改Ticket实体：数据库迁移验收完成后再进行Java映射与全部写路径版本维护。

本次约束测试通过不代表消息已可靠投递、AI任务已自动执行或数据库关联自动校验。首版按决策不建立物理外键，应用须保证关联存在，验证库中不运行消费者。
