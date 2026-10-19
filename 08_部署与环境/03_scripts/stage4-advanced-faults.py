"""Real isolated Redis/consumer-DB/RUNNING-crash recovery; local model fixture only."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from datetime import datetime, timezone, timedelta

ROOT = Path(__file__).resolve().parents[2]
TEMP = ROOT / '.tmp'
spec = importlib.util.spec_from_file_location('fault_base', Path(__file__).with_name('stage4-fault-smoke.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
entered, release = threading.Event(), threading.Event()
hold_label = ''
hold_next = False
hold_lock = threading.Lock()
counts = {}
report = {'date': '2026-10-15', 'status': 'RUNNING', 'vendor_calls': 0,
          'scope': 'smartticket-stage4 only; ports13306/16379/15673',
          'model': 'local HTTP classification fixture, not a real AI quality evaluation',
          'test_lease_seconds': 20, 'production_lease_seconds_unchanged': 90, 'scenarios': []}


class ModelHandler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass

    def do_POST(self):
        global hold_next
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            parts = []
            while True:
                size = int(self.rfile.readline().split(b';')[0], 16)
                if not size:
                    self.rfile.readline(); break
                parts.append(self.rfile.read(size)); self.rfile.read(2)
            body = b''.join(parts)
        else:
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        text = body.decode('utf-8')
        block = False
        with hold_lock:
            for label in ('ADV_DB_WRITE', 'ADV_RUNNING_CRASH'):
                if label in text: counts[label] = counts.get(label, 0) + 1
            if hold_next and hold_label in text:
                hold_next = False; block = True
        if block:
            entered.set()
            if not release.wait(25): return
        content = json.dumps({'category':'支付问题', 'priority':'MEDIUM', 'confidence':0.9,
                              'keywords':['支付'], 'reason':'本地故障测试模型响应，不代表真实模型效果'}, ensure_ascii=False)
        response = json.dumps({'id':'local-fault-fixture', 'object':'chat.completion', 'created':1,
            'model':'fault-fixture', 'choices':[{'index':0, 'message':{'role':'assistant','content':content},
            'finish_reason':'stop'}], 'usage':{'prompt_tokens':30,'completion_tokens':30,'total_tokens':60}}).encode()
        try:
            self.send_response(200); self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(response))); self.end_headers(); self.wfile.write(response)
        except OSError: pass  # Expected if the Java process was deliberately killed.


def stamp(): return datetime.now(timezone(timedelta(hours=8))).isoformat()


def wait_until(check, timeout=90):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = check()
        if value: return value
        time.sleep(.5)
    raise TimeoutError('Expected fault/recovery observation not reached')


def sql(statement):
    values = dict(line.split('=',1) for line in (TEMP/'phase4.env').read_text().splitlines() if '=' in line)
    environment = os.environ.copy(); environment['MYSQL_PWD'] = values['MYSQL_ROOT_PASSWORD']
    raw = subprocess.check_output(['docker','exec','-e','MYSQL_PWD','smartticket-stage4-mysql-1',
        'mysql','-uroot','--default-character-set=utf8mb4','-N','-B','smartticket','-e',statement],
        env=environment, timeout=15)
    return raw.decode('utf-8').strip()


def task(ticket):
    return json.loads(sql("SELECT JSON_OBJECT('task_id',task_id,'status',status,'attempts',attempts," 
        "'lease_until',CAST(lease_until AS CHAR)) FROM ai_analysis_task WHERE ticket_id="
        + str(int(ticket)) + ' ORDER BY id DESC LIMIT 1'))


def final_task(ticket):
    def check():
        row = task(ticket)
        return row if row['status'] in ('SUCCEEDED','DEGRADED','FAILED','STALE') else None
    row = wait_until(check, 120)
    assert row['status'] == 'SUCCEEDED', row
    assert row['attempts'] >= 2, row
    count = int(sql('SELECT COUNT(*) FROM ai_analysis_result r JOIN ai_analysis_task t ON r.task_id=t.task_id WHERE t.ticket_id=' + str(int(ticket))))
    assert count == 1, count
    detail = base.request(f'/api/tickets/{ticket}')
    assert detail['priority'] == 'URGENT', 'AI must not overwrite human priority'
    return dict(row, persisted_results=count, human_priority=detail['priority'])


def arm(label):
    global hold_next, hold_label
    entered.clear(); release.clear()
    with hold_lock: hold_next = True; hold_label = label


def start(ai=True):
    base.log = (TEMP/'phase4-advanced-fault-java.log').open('ab')
    args = [str(base.JAVA), f'-Djava.io.tmpdir={TEMP}', f'-Djdk.net.unixdomain.tmpdir={TEMP}',
        '-jar',str(base.JAR),'--server.port=18081','--spring.profiles.active=stage4',
        '--spring.config.additional-location='+base.CONFIG.as_uri(),
        '--smartticket.ai.enabled='+str(ai).lower(),
        '--smartticket.phase3.async-enabled='+str(ai).lower(),
        '--smartticket.phase3.record-tasks='+str(ai).lower(),
        '--spring.ai.openai.base-url=http://127.0.0.1:19015','--spring.ai.openai.api-key=local-fixture',
        '--spring.ai.openai.chat.options.model=fault-fixture',
        '--smartticket.vector.embedding.api-key=not-configured','--smartticket.vector.index-on-startup=false',
        '--smartticket.phase3.lease-seconds=20','--smartticket.phase3.scheduler-delay-ms=500']
    base.process = subprocess.Popen(args,cwd=TEMP,stdout=base.log,stderr=base.log)
    base.wait_ready()


def main():
    props = dict(base.PROPS)
    first_id = 1_700_000_000 + secrets.randbelow(100_000_000)
    people = [(first_id+i,role) for i,role in enumerate(('SUBMITTER','SUBMITTER','SUBMITTER','HANDLER','ADMIN'))]
    tokens = []
    for i,(identifier,role) in enumerate(people):
        token=secrets.token_urlsafe(24); tokens.append(token)
        for field,value in [('id',identifier),('role',role),('token',token)]:
            props[f'smartticket.phase3.users[{i}].{field}']=str(value)
    base.CONFIG = TEMP/'phase4-advanced-fault.properties'
    base.CONFIG.write_text(''.join(f'{key}={value}\n' for key,value in props.items()),encoding='utf-8')
    base.ACTORS.update(SUBMITTER=tokens[2],HANDLER=tokens[3],ADMIN=tokens[4])
    server = ThreadingHTTPServer(('127.0.0.1',19015),ModelHandler)
    thread = threading.Thread(target=server.serve_forever,daemon=True); thread.start()
    try:
        start(ai=False)
        scenario={'name':'redis_actual_outage_compensation','started_at':stamp()};report['scenarios'].append(scenario)
        ticket=base.create('ADV_REDIS');scenario['ticketId']=ticket
        old=base.request(f'/api/tickets/{ticket}')
        base.compose('stop','redis');scenario['fault_at']=stamp()
        body={'title':'阶段四Redis故障期间已更新','description':'合成记录','priority':'URGENT','operatorId':1}
        request=urllib.request.Request(base.BASE+f'/api/tickets/{ticket}',data=json.dumps(body).encode(),method='PUT',
            headers={'Content-Type':'application/json','X-API-Key':base.ACTORS['SUBMITTER']})
        with urllib.request.urlopen(request,timeout=30) as response: answer=json.load(response)
        assert answer.get('code')==0, answer
        pending=int(sql('SELECT COUNT(*) FROM cache_invalidation WHERE ticket_id='+str(ticket)))
        assert pending==1, pending
        fresh=base.request(f'/api/tickets/{ticket}')
        assert fresh['title']==body['title'] and fresh['version']>old['version']
        scenario.update(invalidation_pending=pending, db_read_during_outage_is_current=True)
        base.compose('up','-d','--wait','redis');scenario['restored_at']=stamp()
        wait_until(lambda:int(sql('SELECT COUNT(*) FROM cache_invalidation WHERE ticket_id='+str(ticket)))==0,30)
        assert base.request(f'/api/tickets/{ticket}')['title']==body['title']
        scenario.update(status='PASS',invalidation_remaining=0,completed_at=stamp())
        print('Redis actual outage and compensation: PASS',flush=True)
        base.stop()

        base.ACTORS['SUBMITTER']=tokens[0]
        start()
        scenario={'name':'consumer_database_write_outage','started_at':stamp()};report['scenarios'].append(scenario)
        arm('ADV_DB_WRITE');ticket=base.create('ADV_DB_WRITE');scenario['ticketId']=ticket
        assert entered.wait(20),'Consumer did not enter the local model fixture'
        before=task(ticket);assert before['status']=='RUNNING',before;scenario['before_fault']=before
        base.compose('stop','mysql');scenario['fault_at']=stamp();release.set()
        def paused():
            text=(TEMP/'phase4-advanced-fault-java.log').read_text(encoding='utf-8',errors='replace')
            return any('listener paused for recovery' in line and before['task_id'] in line for line in text.splitlines())
        wait_until(paused,35);scenario['listener_pause_observed']=True
        base.compose('up','-d','--wait','mysql');scenario['restored_at']=stamp()
        scenario.update(terminal=final_task(ticket),completed_at=stamp(),status='PASS')
        print('Consumer DB write outage, listener pause, lease recovery: PASS',flush=True)
        base.stop()

        base.ACTORS['SUBMITTER']=tokens[1]
        start()
        scenario={'name':'running_application_crash_lease_recovery','started_at':stamp()};report['scenarios'].append(scenario)
        arm('ADV_RUNNING_CRASH');ticket=base.create('ADV_RUNNING_CRASH');scenario['ticketId']=ticket
        assert entered.wait(20),'Consumer did not enter local fixture'
        before=task(ticket);assert before['status']=='RUNNING',before;scenario['before_crash']=before
        base.process.kill();base.process.wait(timeout=10);release.set();base.stop();scenario['crashed_at']=stamp()
        assert task(ticket)['status']=='RUNNING','Task unexpectedly completed before restart'
        start();scenario['restarted_at']=stamp()
        scenario.update(terminal=final_task(ticket),completed_at=stamp(),status='PASS')
        print('RUNNING crash and real lease expiry recovery: PASS',flush=True)
        report.update(status='PASS',local_fixture_calls=counts)
    except Exception as failure:
        report.update(status='FAILED',error_type=type(failure).__name__)
        raise
    finally:
        release.set();base.stop();server.shutdown();server.server_close();thread.join(timeout=3)
        try:base.compose('up','-d','--wait')
        finally:
            (ROOT/'07_测试与质量/06_阶段测试报告/20261015_阶段四补充真实故障验收.json').write_text(
                json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':main()
