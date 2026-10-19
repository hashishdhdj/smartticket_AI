"""One classification and one Agent run through the actual application.

Authorized first-batch budget RMB10; no background tasks/indexing/embeddings,
at most 1 classification +4 Agent model calls, max512 output per model call.
This is a smoke test, not held-out task-completion measurement.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import time
import urllib.request
import urllib.error

root = Path(__file__).resolve().parents[2]
temp = root / '.tmp'
local = root / '06_项目源代码/ticket-service/src/main/resources/application-local.yml'
key = os.environ.get('AI_API_KEY', '')
if not key:
    match = re.search(r'^      api-key:\s*(.+)$', local.read_text(encoding='utf-8-sig'), re.M)
    if match:
        key = match.group(1).strip().strip('"\'')
if not key or key.startswith('${') or key == 'not-configured':
    raise RuntimeError('No usable AI key; no requests sent')
base_config = (temp / 'phase4-integration.properties').read_text(encoding='utf-8')
token = dict(line.split('=', 1) for line in base_config.splitlines() if '=' in line)['smartticket.phase3.users[0].token']
config = temp / 'phase4-real.properties'
config.write_text(base_config + '\n' + '\n'.join([
    'spring.ai.openai.api-key=' + key,
    'spring.ai.openai.base-url=https://api.deepseek.com',
    'spring.ai.openai.chat.options.model=deepseek-flash',
    'spring.ai.openai.chat.options.extra-body.thinking.type=disabled',
    'smartticket.phase3.users[0].id=109014',
    'smartticket.phase3.users[1].id=209014',
    'smartticket.phase3.users[2].id=909014',
    'smartticket.ai.enabled=true', 'smartticket.phase3.async-enabled=false',
    'smartticket.phase3.record-tasks=false', 'smartticket.vector.index-on-startup=false',
    'smartticket.vector.embedding.api-key=not-configured',
]) + '\n', encoding='utf-8')
java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
if not java.exists(): java = Path(os.environ['JAVA_HOME']) / 'bin/java'
base = 'http://127.0.0.1:18082'
report = {'date': '2026-10-14', 'model': 'deepseek-flash', 'kind': 'PROJECT_REAL_SMOKE',
          'independent_evaluation': False, 'authorized_batch_budget_rmb': 10,
          'embedding_calls': 0, 'background_model_calls': 0, 'results': []}

def api(path, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json', 'X-API-Key': token})
    with urllib.request.urlopen(req, timeout=75) as response: result = json.loads(response.read())
    if result.get('code') != 0: raise RuntimeError('Business error ' + str(result.get('code')))
    return result['data']

log = (temp / 'phase4-real-java.log').open('wb')
process = subprocess.Popen([str(java), f'-Djava.io.tmpdir={temp}', f'-Djdk.net.unixdomain.tmpdir={temp}',
    '-jar', str(root / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'),
    '--server.port=18082', '--spring.profiles.active=stage4',
    f'--spring.config.additional-location={config.as_uri()}'], cwd=temp, stdout=log, stderr=log)
try:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None: raise RuntimeError('Java process exited')
        try:
            api('/api/tickets')
            break
        except (OSError, RuntimeError): time.sleep(1)
    else: raise TimeoutError('Startup timed out')
    classification = api('/api/ai/tickets/analyze', {'title': '忘记登录密码', 'description': '我的个人账号忘记密码，只有我无法登录，其他人正常。'})
    report['results'].append({'kind': 'classification', 'response': classification,
                              'passed': not classification.get('degraded', True) and classification.get('category') == '账户问题'})
    ticket = api('/api/tickets', {'title': '真实Agent冒烟-合成工单', 'description': '仅验证查询与日志工具，不涉及真实用户', 'creatorId': 100, 'priority': 'MEDIUM'})['id']
    agent = api('/api/agent/runs', {'message': f'请查询{ticket}号工单的详情与操作日志，告诉我标题、当前状态以及最近的操作。必须使用getTicketDetail和getTicketLogs，不要检索知识或进行指派。'})
    steps = agent.get('steps', [])
    evidence = agent.get('evidence', [])
    passed = (agent.get('status') == 'SUCCEEDED' and not agent.get('needHuman', True)
              and {s['tool'] for s in steps} == {'getTicketDetail', 'getTicketLogs'}
              and all(s['outcome'] == 'SUCCESS' for s in steps)
              and '真实Agent冒烟-合成工单' in agent.get('answer', '')
              and 'PENDING_ASSIGN' in agent.get('answer', '') and 'CREATE' in agent.get('answer', '')
              and any(isinstance(e, dict) and e.get('id') == ticket for e in evidence))
    report['results'].append({'kind': 'agent_detail_logs', 'ticketId': ticket, 'response': agent, 'passed': passed})
    report['status'] = 'PASS' if all(r['passed'] for r in report['results']) else 'FAIL_OR_INCOMPLETE'
except (OSError, RuntimeError, ValueError) as error:
    report['status'] = 'FAILED_OR_INCOMPLETE'
    report['error_type'] = type(error).__name__
finally:
    process.terminate()
    try: process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill(); process.wait(timeout=10)
    log.close()
    output = root / '07_测试与质量/06_阶段测试报告/20261014_项目真实模型冒烟.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'status': report['status'], 'result_count': len(report['results'])}))
