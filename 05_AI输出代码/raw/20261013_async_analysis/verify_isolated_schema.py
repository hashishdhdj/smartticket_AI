"""Validate the draft in a new, uniquely named schema. Never migrate smartticket.

Uses the existing Docker MySQL container and local .env credentials without
printing credentials. Retains the verification schema for manual review.
"""
from pathlib import Path
import datetime
import json
import os
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[3]
DRAFT = Path(__file__).resolve().parent
env = os.environ.copy()
credentials_file = ROOT / '08_部署与环境/01_docker/.env'
for line in credentials_file.read_text(encoding='utf-8-sig').splitlines():
    if line.startswith('MYSQL_ROOT_PASSWORD='):
        password = line.split('=', 1)[1].strip()
        if len(password) >= 2 and password[0] == password[-1] and password[0] in "\"'":
            password = password[1:-1]
        env['MYSQL_PWD'] = password
        break
if not env.get('MYSQL_PWD'):
    raise SystemExit('MySQL password was not found; no schema was changed.')


def sql(statement, schema=None, error_code=None):
    command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', 'smartticket-mysql',
               'mysql', '-uroot', '--default-character-set=utf8mb4', '--batch', '--skip-column-names']
    if schema:
        command.extend(['--database', schema])
    result = subprocess.run(command, input=statement.encode('utf-8'),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=60)
    stderr = result.stderr.decode('utf-8', errors='replace')
    if error_code:
        if result.returncode == 0 or not re.search(rf'ERROR {error_code}\b', stderr):
            raise RuntimeError(f'Expected MySQL error {error_code}, got exit {result.returncode}: {stderr}')
        return
    if result.returncode:
        raise RuntimeError(stderr)
    return result.stdout.decode('utf-8').strip()


baseline_query = 'SELECT (SELECT COUNT(*) FROM ticket), (SELECT COUNT(*) FROM ticket_log);'
before = sql(baseline_query, 'smartticket')
business_columns_before = sql("SHOW COLUMNS FROM ticket;", 'smartticket')
schema = 'smartticket_async_verify_' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S') + '_' + uuid.uuid4().hex[:6]
if not re.fullmatch(r'smartticket_async_verify_[0-9_]+[a-f0-9]{6}', schema):
    raise RuntimeError('Unexpected verification schema name')
sql(f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
base_ddl = (ROOT / '06_项目源代码/sql/01_init_schema.sql').read_text(encoding='utf-8-sig')
base_ddl = re.sub(r'^\s*USE\s+smartticket\s*;\s*$', '', base_ddl, flags=re.MULTILINE | re.IGNORECASE)
sql(base_ddl, schema)
sql("INSERT INTO ticket(ticket_no,title,creator_id) VALUES('verify-001','Synthetic ticket',999998);", schema)
sql("INSERT INTO ticket_log(ticket_id,operator_id,action) VALUES(1,999998,'CREATE');", schema)
isolated_before = sql(baseline_query, schema)
migration = (DRAFT / '02_async_analysis.sql').read_text(encoding='utf-8')
sql(migration, schema)
sql(migration, schema)  # Explicit rerun: version is not added twice.
assert sql(baseline_query, schema) == isolated_before, 'Migration changed existing test records'
assert sql('SELECT version FROM ticket WHERE id=1;', schema) == '0'

task, trace, event = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
task_insert = f"INSERT INTO ai_analysis_task(task_id,ticket_id,input_version,trace_id) VALUES('{task}',1,0,'{trace}');"
sql(task_insert, schema)
assert sql('SELECT status,attempts,generation FROM ai_analysis_task;', schema) == 'PENDING\t0\t0'
sql(task_insert, schema, 1062)
sql(f"INSERT INTO ai_analysis_task(task_id,ticket_id,input_version,trace_id) VALUES('{uuid.uuid4()}',1,0,'{trace}');", schema, 1062)
sql(f"INSERT INTO ai_analysis_task(task_id,ticket_id,input_version,generation,replay_of_task_id,trace_id) VALUES('{uuid.uuid4()}',1,0,1,'{task}','{trace}');", schema)
result_insert = f"INSERT INTO ai_analysis_result(task_id,input_version,source_type,cost_ms,confidence,priority) VALUES('{task}',0,'MODEL',10,0.8,'HIGH');"
sql(result_insert, schema)
sql(result_insert, schema, 1062)
payload = json.dumps(dict(schemaVersion=1, eventId=event, taskId=task, ticketId=1,
                          inputVersion=0, traceId=trace, occurredAt='2026-10-13T20:00:00+08:00'))
outbox_insert = f"INSERT INTO outbox_event(event_id,task_id,event_type,payload) VALUES('{event}','{task}','ticket.analysis.requested','{payload}');"
sql(outbox_insert, schema)
sql(outbox_insert, schema, 1062)
sql(f"INSERT INTO outbox_event(event_id,task_id,event_type,payload) VALUES('{uuid.uuid4()}','{task}','ticket.analysis.requested','{payload}');", schema, 1062)
sql(f"INSERT INTO ai_analysis_task(task_id,ticket_id,input_version,status,trace_id) VALUES('{uuid.uuid4()}',2,0,'INVALID','{trace}');", schema, 3819)
sql(f"INSERT INTO ai_analysis_result(task_id,input_version,source_type,cost_ms,confidence) VALUES('{uuid.uuid4()}',0,'MODEL',10,1.5);", schema, 3819)
sql(f"INSERT INTO ai_analysis_task(task_id,ticket_id,input_version,trace_id) VALUES('{uuid.uuid4()}',2,-1,'{trace}');", schema, 3819)

# Neither test rows nor migration target the development schema.
assert sql(baseline_query, 'smartticket') == before, 'Development row counts changed during verification'
assert sql('SHOW COLUMNS FROM ticket;', 'smartticket') == business_columns_before, 'Development ticket schema changed'
report = f'''# 隔离数据库验证结果

验证库：`{schema}`（保留供人工查看，未自动删除）。
MySQL版本：{sql('SELECT VERSION();', schema)}。

- 基础表带合成数据，迁移执行及重复执行成功。
- 既有测试工单/日志数量不变，工单version默认0。
- task_id、任务版本代次、结果task_id、event_id、事件投递次序唯一约束有效（1062）。
- generation=1重放任务允许创建。
- 非法任务状态、负输入版本、confidence>1被CHECK拒绝（3819）。
- 开发库smartticket的ticket字段和工单/日志数量与执行前相同；未向其发送迁移或测试写入。

这是数据库结构与约束验证，不是Java事务、消息投递或消费者验收。
'''
(DRAFT / '隔离验证结果.md').write_text(report, encoding='utf-8')
print(report)
