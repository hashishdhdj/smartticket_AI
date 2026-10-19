"""Prepare isolated, zero-vendor integration middleware; never touches development containers."""
from pathlib import Path
import os
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TEMP = ROOT / '.tmp'
TEMP.mkdir(exist_ok=True)
ENV_FILE = TEMP / 'phase4.env'
if not ENV_FILE.exists():
    ENV_FILE.write_text(''.join(f'{key}={secrets.token_hex(24)}\n' for key in
        ('MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD'))
        + 'RABBITMQ_USER=smartticket\n', encoding='utf-8')
VALUES = dict(line.split('=', 1) for line in ENV_FILE.read_text(encoding='utf-8').splitlines() if '=' in line)
for role in ('SUBMITTER', 'HANDLER', 'ADMIN'):
    key = f'SMARTTICKET_{role}_TOKEN'
    if key not in VALUES:
        VALUES[key] = secrets.token_urlsafe(32)
        with ENV_FILE.open('a', encoding='utf-8') as output:
            output.write(f'{key}={VALUES[key]}\n')
COMPOSE = ['docker', 'compose', '-p', 'smartticket-stage4', '--env-file', str(ENV_FILE),
           '-f', str(ROOT / '08_部署与环境/01_docker/compose-stage4.yml')]

def run(args, data=None, env=None):
    result = subprocess.run(args, input=data, capture_output=True, env=env, timeout=240)
    if result.returncode:
        # Do not print command arguments or rendered configuration containing credentials.
        raise RuntimeError(f'Command failed ({args[0]}), exit={result.returncode}; inspect isolated service logs.')
    return result.stdout.decode('utf-8', errors='replace').strip()

run(COMPOSE + ['up', '-d', '--wait', '--wait-timeout', '180'])
containers = {service: run(COMPOSE + ['ps', '-q', service]) for service in ('mysql', 'redis', 'pgvector', 'rabbitmq')}
if any(not value or '\n' in value for value in containers.values()):
    raise RuntimeError('Expected exactly one isolated container per service')
mysql_env = os.environ.copy()
mysql_env['MYSQL_PWD'] = VALUES['MYSQL_ROOT_PASSWORD']
for sql in sorted((ROOT / '06_项目源代码/sql').glob('*.sql')):
    run(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', containers['mysql'], 'mysql', '-uroot',
         '--default-character-set=utf8mb4', 'smartticket'], sql.read_bytes(), mysql_env)
run(['docker', 'exec', containers['rabbitmq'], 'rabbitmqctl', 'add_vhost', 'smartticket-stage3-verify'])
for vhost in ('/', 'smartticket-stage3-verify'):
    run(['docker', 'exec', containers['rabbitmq'], 'rabbitmqctl', 'set_permissions', '-p', vhost,
         'smartticket', '.*', '.*', '.*'])
    run(['docker', 'exec', containers['rabbitmq'], 'rabbitmqctl', 'set_policy', '-p', vhost,
         '--apply-to', 'queues', 'analysis-deadletter', '^smartticket\\.ai\\.analysis$',
         '{"dead-letter-exchange":"smartticket.ai.dead","dead-letter-routing-key":"ticket.analysis.failed"}'])
properties = {
    'spring.datasource.url': 'jdbc:mysql://127.0.0.1:13306/smartticket?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true',
    'spring.datasource.username': 'root', 'spring.datasource.password': VALUES['MYSQL_ROOT_PASSWORD'],
    'spring.data.redis.port': '16379', 'spring.data.redis.password': VALUES['REDIS_PASSWORD'],
    'smartticket.vector.datasource.url': 'jdbc:postgresql://127.0.0.1:15432/smartticket_vector',
    'smartticket.vector.datasource.username': 'postgres', 'smartticket.vector.datasource.password': VALUES['POSTGRES_PASSWORD'],
    'spring.rabbitmq.port': '15673', 'spring.rabbitmq.username': 'smartticket', 'spring.rabbitmq.password': VALUES['RABBITMQ_PASSWORD'],
    'test.redis.port': '16379', 'test.rabbit.port': '15673',
    'spring.ai.openai.api-key': 'not-configured', 'smartticket.vector.embedding.api-key': 'not-configured',
    'smartticket.vector.index-on-startup': 'false',
}
for index, (uid, role) in enumerate(((100, 'SUBMITTER'), (200, 'HANDLER'), (900, 'ADMIN'))):
    properties[f'smartticket.phase3.users[{index}].id'] = str(uid)
    properties[f'smartticket.phase3.users[{index}].role'] = role
    properties[f'smartticket.phase3.users[{index}].token'] = VALUES[f'SMARTTICKET_{role}_TOKEN']
(TEMP / 'phase4-integration.properties').write_text(''.join(f'{key}={value}\n' for key, value in properties.items()), encoding='utf-8')
print('Isolated stage4 middleware ready. Credentials stored only in ignored .tmp files.')
