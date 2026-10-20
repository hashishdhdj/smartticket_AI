"""Local-only, isolated HTTP runtime for R06; no vendor proxy or chat model.

Keep middleware credentials in ignored properties, use fresh actors/vhost, and
clean only data owned by those actors after stopping this process.
"""
from contextlib import AbstractContextManager
from pathlib import Path
import json
import os
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
TEMP = ROOT / '.tmp'


class LocalQualityRuntime(AbstractContextManager):
    def __init__(self, java, jar, port=18083, threshold=None, chat_gateway=None, extra_submitters=0):
        self.java, self.jar, self.port = str(java), Path(jar), port
        if threshold is not None and not 0 < threshold < 1:
            raise ValueError('Threshold must be between zero and one')
        self.threshold = threshold
        if chat_gateway is not None and not chat_gateway[0].startswith('http://127.0.0.1:'):
            raise ValueError('Evaluation chat must use a local metered gateway')
        self.chat_gateway = chat_gateway
        self.base = f'http://127.0.0.1:{port}'
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.run_id = uuid.uuid4().hex[:12]
        self.vhost = 'smartticket-quality-' + self.run_id
        self.actors = {role: (2100000000 + secrets.randbelow(10000000) * 4 + index, secrets.token_urlsafe(32))
                       for index, role in enumerate(('SUBMITTER', 'HANDLER', 'ADMIN'))}
        if type(extra_submitters) is not int or not 0 <= extra_submitters <= 8:
            raise ValueError('At most eight separate evaluation submitters are supported')
        used_ids = {actor[0] for actor in self.actors.values()}
        for index in range(extra_submitters):
            while True:
                actor_id = 2100000000 + secrets.randbelow(10000000) * 4
                if actor_id not in used_ids: break
            used_ids.add(actor_id)
            self.actors['CASE_' + str(index)] = (actor_id, secrets.token_urlsafe(32))
        # Keep IDs within signed INT range for compatibility with older probes.
        self.values = dict(line.split('=', 1) for line in (TEMP / 'phase4.env').read_text(encoding='utf-8').splitlines() if '=' in line)
        self.config = TEMP / f'quality-{self.run_id}.properties'
        self.process = self.log = None
        self.vhost_created = False

    @staticmethod
    def command(args, data=None, env=None):
        result = subprocess.run(args, input=data, capture_output=True, env=env, timeout=45)
        if result.returncode:
            raise RuntimeError('Isolated local command failed; no credentials printed')
        return result.stdout.decode('utf-8', errors='strict').strip()

    def sql(self, engine, statement):
        env = os.environ.copy()
        if engine == 'mysql':
            env['MYSQL_PWD'] = self.values['MYSQL_ROOT_PASSWORD']
            command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', 'smartticket-stage4-mysql-1',
                       'mysql', '-uroot', '--default-character-set=utf8mb4', '-N', '-B', 'smartticket']
        elif engine == 'pgvector':
            env['PGPASSWORD'] = self.values['POSTGRES_PASSWORD']
            command = ['docker', 'exec', '-i', '-e', 'PGPASSWORD', 'smartticket-stage4-pgvector-1',
                       'psql', '-v', 'ON_ERROR_STOP=1', '-At', '-U', 'postgres', '-d', 'smartticket_vector']
        else:
            raise ValueError('Only isolated MySQL/PG are supported')
        return self.command(command, statement.encode('utf-8'), env)

    def rabbit(self, *args):
        return self.command(['docker', 'exec', 'smartticket-stage4-rabbitmq-1', 'rabbitmqctl', *args])

    def api(self, path, body=None, role='ADMIN', expected=200):
        request = urllib.request.Request(self.base + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'),
            headers={'Content-Type': 'application/json', 'X-API-Key': self.actors[role][1]})
        try:
            with self.http.open(request, timeout=90) as response:
                code, result = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            code, result = error.code, json.load(error)
        if code != expected or (expected == 200 and result.get('code') != 0):
            raise RuntimeError(f'Unexpected response on {path}: HTTP {code}')
        return result.get('data')

    @staticmethod
    def wait_for(read, predicate, label, seconds=120):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            result = read()
            if predicate(result):
                return result
            time.sleep(1)
        raise TimeoutError(label)

    def __enter__(self):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', self.port))
        with self.http.open('http://127.0.0.1:11434/api/tags', timeout=20) as response:
            if not any(item['name'] == 'bge-m3:latest' for item in json.load(response)['models']):
                raise RuntimeError('Local BGE-M3 is required')
        try:
            self.rabbit('add_vhost', self.vhost)
            self.vhost_created = True
            self.rabbit('set_permissions', '-p', self.vhost, 'smartticket', '.*', '.*', '.*')
            props = {
                'server.address': '127.0.0.1', 'server.port': self.port,
                'spring.rabbitmq.virtual-host': self.vhost,
                'spring.ai.openai.api-key': 'not-configured', 'spring.ai.openai.base-url': 'http://127.0.0.1:9',
                'smartticket.ai.enabled': 'false', 'smartticket.phase3.security-enabled': 'true',
                'smartticket.phase3.record-tasks': 'false', 'smartticket.phase3.async-enabled': 'false',
                'smartticket.knowledge.enabled': 'true', 'smartticket.knowledge.async-enabled': 'true',
                'smartticket.knowledge.scheduler-delay-ms': 1000,
                'smartticket.vector.index-on-startup': 'true',
                'smartticket.vector.embedding.base-url': 'http://127.0.0.1:11434',
                'smartticket.vector.embedding.api-key': 'ollama-local',
                'smartticket.vector.embedding.model': 'bge-m3:latest',
                'smartticket.vector.embedding.send-dimensions': 'false',
                'smartticket.vector.embedding.dimensions': 1024,
                # Permit a bounded evaluation batch; not a change to the production defaults.
                'smartticket.phase3.requests-per-minute': 200,
            }
            if self.threshold is not None:
                props['smartticket.vector.confidence-threshold'] = self.threshold
            if self.chat_gateway is not None:
                props.update({'smartticket.ai.enabled': 'true',
                              'spring.ai.openai.base-url': self.chat_gateway[0],
                              'spring.ai.openai.api-key': self.chat_gateway[1],
                              'spring.ai.openai.chat.options.model': 'deepseek-flash',
                              'spring.ai.openai.chat.options.max-tokens': 512,
                              'spring.ai.retry.max-attempts': 1})
            for index, (role, (actor, token)) in enumerate(self.actors.items()):
                for key, value in (('id', actor), ('role', 'SUBMITTER' if role.startswith('CASE_') else role), ('token', token)):
                    props[f'smartticket.phase3.users[{index}].{key}'] = value
            self.config.write_text((TEMP / 'phase4-integration.properties').read_text(encoding='utf-8')
                + '\n' + ''.join(f'{key}={value}\n' for key, value in props.items()), encoding='utf-8')
            self.log = (TEMP / f'quality-{self.run_id}.log').open('wb')
            self.process = subprocess.Popen([self.java, f'-Djava.io.tmpdir={TEMP}', f'-Djdk.net.unixdomain.tmpdir={TEMP}',
                '-jar', str(self.jar), '--spring.profiles.active=ollama', f'--spring.config.additional-location={self.config.as_uri()}'],
                cwd=TEMP, stdout=self.log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            deadline = time.monotonic() + 120
            while True:
                if self.process.poll() is not None:
                    raise RuntimeError('Quality application exited; inspect ignored local log')
                try:
                    self.api('/api/knowledge/candidates?limit=1')
                    return self
                except (urllib.error.URLError, OSError):
                    if time.monotonic() > deadline:
                        raise TimeoutError('Quality application startup')
                    time.sleep(1)
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *args):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        if self.log:
            self.log.close()
        try:
            actors = ','.join(str(actor[0]) for actor in self.actors.values())
            rows = self.sql('mysql', 'SELECT r.resolution_id FROM ticket_resolution r JOIN ticket t ON t.id=r.ticket_id '
                            f'WHERE t.creator_id IN ({actors});')
            for value in rows.splitlines():
                if str(uuid.UUID(value)) != value:
                    raise ValueError('Invalid owned resolution UUID')
                self.sql('pgvector', f"DELETE FROM knowledge_chunk WHERE source_file LIKE 'resolved-ticket/{value}/%';"
                         f"DELETE FROM knowledge_publication WHERE candidate_id='{value}';")
            self.sql('mysql', f'DELETE FROM cache_invalidation WHERE ticket_id IN (SELECT id FROM ticket WHERE creator_id IN ({actors}));'
                f'DELETE FROM outbox_event WHERE task_id IN (SELECT task_id FROM ai_analysis_task WHERE ticket_id IN (SELECT id FROM ticket WHERE creator_id IN ({actors})));'
                f'DELETE FROM ai_analysis_result WHERE task_id IN (SELECT task_id FROM ai_analysis_task WHERE ticket_id IN (SELECT id FROM ticket WHERE creator_id IN ({actors})));'
                f'DELETE FROM ai_analysis_task WHERE ticket_id IN (SELECT id FROM ticket WHERE creator_id IN ({actors}));'
                f'DELETE FROM analysis_replay_request WHERE actor_id IN ({actors});DELETE FROM agent_action WHERE actor_id IN ({actors});'
                f'DELETE FROM agent_step WHERE run_id IN (SELECT run_id FROM agent_run WHERE actor_id IN ({actors}));'
                f'DELETE FROM agent_run WHERE actor_id IN ({actors});'
                f'DELETE FROM ticket_log WHERE ticket_id IN (SELECT id FROM ticket WHERE creator_id IN ({actors}));'
                f'DELETE FROM ticket WHERE creator_id IN ({actors});DELETE FROM ai_usage_budget WHERE actor_id IN ({actors});')
        finally:
            try:
                if self.vhost_created:
                    self.rabbit('delete_vhost', self.vhost)  # This run's fresh empty/finished queue namespace only.
            finally:
                self.config.unlink(missing_ok=True)
