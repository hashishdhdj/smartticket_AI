"""Actual isolated broker/DB outage and application restart; zero vendor calls.

Uses only smartticket-stage4 project. Writes synthetic fixtures; restores stopped
dependencies and terminates its own Java process in finally.
"""
from pathlib import Path
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone, timedelta

ROOT = Path(__file__).resolve().parents[2]
TEMP = ROOT / '.tmp'
CONFIG = TEMP / 'phase4-integration.properties'
PROPS = dict(line.split('=', 1) for line in CONFIG.read_text(encoding='utf-8').splitlines() if '=' in line)
JAVA = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
if not JAVA.exists():
    JAVA = Path(os.environ['JAVA_HOME']) / 'bin/java'
JAR = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
COMPOSE = ['docker', 'compose', '-p', 'smartticket-stage4', '--env-file', str(TEMP / 'phase4.env'),
           '-f', str(ROOT / '08_部署与环境/01_docker/compose-stage4.yml')]
BASE = 'http://127.0.0.1:18081'
ACTORS = {role: PROPS[f'smartticket.phase3.users[{index}].token']
          for index, role in enumerate(('SUBMITTER', 'HANDLER', 'ADMIN'))}
report = {'date': '2026-10-14', 'mode': 'RULE_ONLY', 'vendor_calls': 0, 'scenarios': []}
process = None
log = None

def stamp():
    return datetime.now(timezone(timedelta(hours=8))).isoformat()

def compose(*args):
    subprocess.run(COMPOSE + list(args), check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=200)

def request(path, body=None, actor='SUBMITTER'):
    headers = {'Content-Type': 'application/json', 'X-API-Key': ACTORS[actor]}
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=12) as response:
        payload = json.loads(response.read())
    if payload.get('code') != 0:
        raise RuntimeError(f'API business error: {payload.get("code")}')
    return payload['data']

def wait_ready():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Java application exited; inspect private .tmp log')
        try:
            request('/api/admin/metrics', actor='ADMIN')
            return
        except (OSError, RuntimeError):
            time.sleep(1)
    raise TimeoutError('Application readiness exceeded 90s')

def start(extra_args=None, jar=None):
    global process, log
    log = (TEMP / 'phase4-fault-java.log').open('ab')
    args = [str(JAVA), f'-Djava.io.tmpdir={TEMP}', f'-Djdk.net.unixdomain.tmpdir={TEMP}', '-jar', str(jar or JAR),
            '--server.port=18081', '--spring.profiles.active=stage4',
            f'--spring.config.additional-location={CONFIG.as_uri()}',
            '--smartticket.ai.enabled=false', '--smartticket.vector.embedding.api-key=not-configured',
            '--smartticket.vector.index-on-startup=false', '--logging.level.com.smartticket=INFO']
    process = subprocess.Popen(args + (extra_args or []), cwd=TEMP, stdout=log, stderr=log)
    wait_ready()

def stop():
    global process, log
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    if log:
        log.close()

def create(label):
    return request('/api/tickets', {'title': '阶段四故障验证-' + label, 'description': '合成支付异常，仅规则验证',
                                   'creatorId': 99999, 'priority': 'URGENT'})['id']

def terminal(ticket):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = request(f'/api/tickets/{ticket}/analysis')
        if result.get('status') in ('SUCCEEDED', 'DEGRADED', 'FAILED', 'STALE'):
            assert result['status'] == 'DEGRADED', result
            detail = request(f'/api/tickets/{ticket}')
            assert detail['creatorId'] == 100 and detail['priority'] == 'URGENT'
            return result
        time.sleep(2)
    raise TimeoutError('Task did not reach terminal state')

def main():
    try:
        start()
        compose('stop', 'rabbitmq')
        scenario = {'name': 'broker_actual_outage', 'fault_at': stamp()}
        ticket = create('broker')
        scenario['ticketId'] = ticket
        scenario['during_fault'] = request(f'/api/tickets/{ticket}/analysis')['status']
        assert scenario['during_fault'] == 'PENDING'
        compose('up', '-d', '--wait', 'rabbitmq')
        scenario['restored_at'] = stamp()
        scenario['terminal'] = terminal(ticket)
        scenario['completed_at'] = stamp()
        report['scenarios'].append(scenario)

        # Real DB outage: API must not report a successful write when persistence is down.
        scenario = {'name': 'database_actual_outage', 'fault_at': stamp()}
        compose('stop', 'mysql')
        try:
            create('db-unavailable')
            raise AssertionError('Creation falsely succeeded during database outage')
        except (urllib.error.HTTPError, OSError, RuntimeError) as failure:
            scenario['rejected_exception'] = type(failure).__name__
        compose('up', '-d', '--wait', 'mysql')
        scenario['restored_at'] = stamp()
        wait_ready()
        ticket = create('db-restored')
        scenario['ticketId'] = ticket
        scenario['terminal'] = terminal(ticket)
        scenario['completed_at'] = stamp()
        scenario['consumer_db_pause_verified'] = False
        report['scenarios'].append(scenario)

        compose('stop', 'rabbitmq')
        ticket = create('restart-pending')
        scenario = {'name': 'application_restart_pending', 'ticketId': ticket, 'before_restart': stamp()}
        stop()
        compose('up', '-d', '--wait', 'rabbitmq')
        start()
        scenario['terminal'] = terminal(ticket)
        scenario['completed_at'] = stamp()
        scenario['running_lease_crash_verified'] = False
        report['scenarios'].append(scenario)
        report['status'] = 'PASS_WITH_LIMITATIONS'
    finally:
        stop()
        compose('up', '-d', '--wait')
        output = ROOT / '07_测试与质量/06_阶段测试报告/20261014_阶段四真实故障冒烟.json'
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Isolated fault scenarios complete. See report for precise coverage limits.')


if __name__ == "__main__":
    main()
