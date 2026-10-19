from pathlib import Path
import os,subprocess,secrets,datetime
root=Path(__file__).resolve().parents[2];env=os.environ.copy()
for line in (root/'08_部署与环境/01_docker/.env').read_text(encoding='utf-8-sig').splitlines():
 if line.startswith('MYSQL_ROOT_PASSWORD='):env['MYSQL_PWD']=line.split('=',1)[1].strip().strip('"\'')
if not env.get('MYSQL_PWD'):raise RuntimeError('Missing MySQL credential')
def run(sql):
 result=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD','smartticket-mysql','mysql','-uroot','--default-character-set=utf8mb4','--batch','--skip-column-names','smartticket'],input=sql.encode(),capture_output=True,env=env,timeout=60)
 if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
 return result.stdout.decode().strip()
(root/'.tmp').mkdir(exist_ok=True)
backup=root/'.tmp'/('smartticket-before-stage3-'+datetime.datetime.now().strftime('%Y%m%d%H%M%S')+'.sql')
with backup.open('wb') as output:
 result=subprocess.run(['docker','exec','-e','MYSQL_PWD','smartticket-mysql','mysqldump','-uroot','--single-transaction','--no-tablespaces','smartticket'],stdout=output,stderr=subprocess.PIPE,env=env,timeout=60)
 if result.returncode:raise RuntimeError('Database backup failed')
counts=run('SELECT (SELECT COUNT(*) FROM ticket),(SELECT COUNT(*) FROM ticket_log);')
for file in ['02_async_analysis.sql','03_phase3_runtime.sql','04_replay_idempotency.sql']:
 run((root/'06_项目源代码/sql'/file).read_text(encoding='utf-8'))
assert run('SELECT (SELECT COUNT(*) FROM ticket),(SELECT COUNT(*) FROM ticket_log);')==counts
print('Migration passed; existing ticket/log row counts retained; backup: '+backup.name)
local=root/'06_项目源代码/ticket-service/phase3-local.properties'
if not local.exists():
 local.write_text(''.join(f'smartticket.phase3.users[{i}].id={uid}\nsmartticket.phase3.users[{i}].role={role}\nsmartticket.phase3.users[{i}].token={secrets.token_urlsafe(32)}\n' for i,uid,role in [(0,100,'SUBMITTER'),(1,200,'HANDLER'),(2,900,'ADMIN')]),encoding='utf-8')
print('Local demo credentials configured in ignored file; credentials not printed')
result=subprocess.run(['docker','exec','smartticket-rabbitmq','rabbitmqctl','set_policy','--apply-to','queues','smartticket-analysis-deadletter','^smartticket\\.ai\\.analysis$','{"dead-letter-exchange":"smartticket.ai.dead","dead-letter-routing-key":"ticket.analysis.failed"}'],capture_output=True,timeout=30)
if result.returncode:raise RuntimeError('Dead-letter policy installation failed')
print('RabbitMQ dead-letter policy installed without deleting queues')

# Dedicated verification vhost. A pre-existing vhost is retained.
subprocess.run(['docker','exec','smartticket-rabbitmq','rabbitmqctl','add_vhost','smartticket-stage3-verify'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
for arguments in [
 ['set_permissions','-p','smartticket-stage3-verify','smartticket','.*','.*','.*'],
 ['set_policy','-p','smartticket-stage3-verify','--apply-to','queues','smartticket-analysis-deadletter','^smartticket\\.ai\\.analysis$','{"dead-letter-exchange":"smartticket.ai.dead","dead-letter-routing-key":"ticket.analysis.failed"}']]:
 result=subprocess.run(['docker','exec','smartticket-rabbitmq','rabbitmqctl',*arguments],capture_output=True,timeout=30)
 if result.returncode:raise RuntimeError('Verification vhost preparation failed')
print('Verification vhost prepared')
