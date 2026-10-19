"""Real JAR/HTTP/MQ/PG/Ollama smoke; no chat provider, only synthetic data.

Run after prepare-phase4.py and Maven package, with local Ollama BGE-M3 ready.
Uses only the isolated smartticket-stage4 project. Removes only its own records.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java'))
    parser.add_argument('--port', type=int, default=18087)
    args = parser.parse_args()
    assert 1024 <= args.port <= 65535
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    assert jar.is_file(), 'Build the current JAR first'
    assert (TEMP / 'phase4-integration.properties').is_file(), 'Run prepare-phase4.py first'
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))  # Never attach to an already running application.
    values = dict(line.split('=', 1) for line in (TEMP / 'phase4.env').read_text(encoding='utf-8').splitlines() if '=' in line)
    base = f'http://127.0.0.1:{args.port}'
    # Bypass system HTTP proxies for local verification, including Docker/localhost URLs.
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with http.open('http://127.0.0.1:11434/api/tags', timeout=20) as response:
        assert any(model['name'] == 'bge-m3:latest' for model in json.load(response)['models']), 'Pull bge-m3 first'

    run_id = uuid.uuid4().hex[:12]
    actor_base = 700000 + secrets.randbelow(100000)
    actors = {role: (actor_base + index, secrets.token_urlsafe(32))
              for index, role in enumerate(('SUBMITTER', 'HANDLER', 'ADMIN'))}
    config = TEMP / f'knowledge-smoke-{run_id}.properties'
    props = {
        'server.address': '127.0.0.1', 'server.port': str(args.port),
        'spring.rabbitmq.virtual-host': 'smartticket-knowledge-verify',
        'smartticket.phase3.security-enabled': 'true',
        'smartticket.phase3.record-tasks': 'false', 'smartticket.phase3.async-enabled': 'false',
        'smartticket.ai.enabled': 'false', 'spring.ai.openai.api-key': 'not-configured',
        'spring.ai.openai.base-url': 'http://127.0.0.1:9',
        'smartticket.knowledge.enabled': 'true', 'smartticket.knowledge.async-enabled': 'true',
        'smartticket.knowledge.scheduler-delay-ms': '1000',
        'smartticket.vector.index-on-startup': 'false',
        'smartticket.vector.embedding.base-url': 'http://127.0.0.1:11434',
        'smartticket.vector.embedding.api-key': 'ollama-local',
        'smartticket.vector.embedding.model': 'bge-m3:latest',
        'smartticket.vector.embedding.dimensions': '1024',
        'smartticket.vector.embedding.send-dimensions': 'false',
    }
    for index, (role, (actor_id, token)) in enumerate(actors.items()):
        for key, value in (('id', actor_id), ('role', role), ('token', token)):
            props[f'smartticket.phase3.users[{index}].{key}'] = str(value)
    config.write_text((TEMP / 'phase4-integration.properties').read_text(encoding='utf-8')
                      + '\n' + ''.join(f'{key}={value}\n' for key, value in props.items()), encoding='utf-8')

    def api(path, body=None, role='ADMIN', expected=200):
        request = urllib.request.Request(base + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'),
            headers={'Content-Type': 'application/json', 'X-API-Key': actors[role][1]})
        try:
            with http.open(request, timeout=90) as response:
                code, data = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            code, data = error.code, json.load(error)
        assert code == expected, f'Unexpected HTTP status on {path}: {code}'
        if expected == 200:
            assert data['code'] == 0, f'Business failure on {path}'
        return data.get('data')

    def wait_for(read, predicate, label, seconds=150):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            result = read()
            if predicate(result):
                return result
            time.sleep(1)
        raise AssertionError(f'Timed out: {label}')

    def sql(engine, statement):
        assert engine in ('mysql', 'pgvector')
        environment = os.environ.copy()
        if engine == 'mysql':
            environment['MYSQL_PWD'] = values['MYSQL_ROOT_PASSWORD']
            command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', 'smartticket-stage4-mysql-1',
                       'mysql', '-uroot', '--default-character-set=utf8mb4', '-N', '-B', 'smartticket']
        else:
            environment['PGPASSWORD'] = values['POSTGRES_PASSWORD']
            command = ['docker', 'exec', '-i', '-e', 'PGPASSWORD', 'smartticket-stage4-pgvector-1',
                       'psql', '-v', 'ON_ERROR_STOP=1', '-At', '-U', 'postgres', '-d', 'smartticket_vector']
        result = subprocess.run(command, input=statement.encode('utf-8'), capture_output=True, env=environment, timeout=30)
        assert result.returncode == 0, f'Isolated {engine} verification failed'
        return result.stdout.decode('utf-8').strip()

    report = {'date': datetime.now(timezone.utc).isoformat(), 'status': 'INCOMPLETE',
              'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'vendor_calls': 0,
              'embedding': 'local Ollama bge-m3:latest', 'chat_enabled': False, 'checks': []}
    ticket_id = resolution = None
    process = None
    log = (TEMP / f'knowledge-smoke-{run_id}.log').open('wb')
    try:
        process = subprocess.Popen([args.java, f'-Djava.io.tmpdir={TEMP}', f'-Djdk.net.unixdomain.tmpdir={TEMP}',
            '-jar', str(jar), '--spring.profiles.active=ollama', f'--spring.config.additional-location={config.as_uri()}'],
            cwd=ROOT / '06_项目源代码/ticket-service', stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        deadline = time.monotonic() + 120
        while True:
            assert process.poll() is None, 'Application exited; inspect the ignored local log'
            try:
                api('/api/knowledge/candidates?limit=1')
                break
            except (urllib.error.URLError, OSError):
                assert time.monotonic() < deadline, 'Application startup timed out'
                time.sleep(1)
        question = '合成演示缓存连接中断应如何恢复'
        answer = '先核对演示服务的连接配置，再重新建立连接并验证缓存读写恢复。此步骤只用于合成演示工单。'
        ticket_id = api('/api/tickets', {'title': question, 'description': f'本地验收合成工单 {run_id}',
            'creatorId': actors['SUBMITTER'][0]}, 'SUBMITTER')['id']
        api(f'/api/tickets/{ticket_id}/assign', {'assigneeId': actors['HANDLER'][0],
            'operatorId': actors['ADMIN'][0]})
        api(f'/api/tickets/{ticket_id}/resolve', {'operatorId': actors['HANDLER'][0],
            'solution': answer}, 'HANDLER')
        rows = api(f'/api/tickets/{ticket_id}/knowledge', role='SUBMITTER')
        resolution = rows[0]['resolutionId']
        assert str(uuid.UUID(resolution)) == resolution
        candidate_path = f'/api/knowledge/candidates/{resolution}'
        def draft_state():
            state = api(f'/api/tickets/{ticket_id}/knowledge', role='SUBMITTER')[0]
            return state
        wait_for(draft_state, lambda row: row.get('state') == 'DRAFT', 'RabbitMQ draft consumer')
        detail = api(candidate_path)
        assert detail['candidate']['revision'] == 0 and detail['jobs'][0]['status'] == 'SUCCEEDED'
        report['checks'].append('real HTTP resolve -> committed snapshot/outbox -> RabbitMQ -> DRAFT')
        before = api('/api/ai/knowledge/answer', {'query': question}, 'SUBMITTER')
        assert not any(source['sourceFile'].startswith(f'resolved-ticket/{resolution}/') for source in before['sources'])
        api(candidate_path + '/approve', {'expectedRevision': 0, 'question': question, 'answer': answer,
            'scope': 'SHARED_DEMO', 'redactionConfirmed': True}, 'HANDLER', expected=403)
        report['checks'].append('unreviewed source hidden; handler review forbidden')
        api(candidate_path + '/approve', {'expectedRevision': 0, 'question': question, 'answer': answer,
            'scope': 'SHARED_DEMO', 'redactionConfirmed': True})
        detail = wait_for(lambda: api(candidate_path), lambda row: row['candidate'].get('indexedRevision') == 1,
                          'real local BGE-M3 incremental indexing')
        assert any(job['kind'] == 'INDEX' and job['status'] == 'SUCCEEDED' for job in detail['jobs'])
        source_file = f'resolved-ticket/{resolution}/v1.md'
        assert sql('pgvector', f"SELECT vector_dims(embedding)||'|'||model FROM knowledge_chunk WHERE source_file='{source_file}';") == '1024|bge-m3:latest'
        after = api('/api/ai/knowledge/answer', {'query': '演示服务与缓存的连接断开了，恢复时先检查什么？'}, 'SUBMITTER')
        assert after['degradedSources'] == [], 'Both retrieval paths must be available'
        hit = next(source for source in after['sources'] if source['sourceFile'] == source_file)
        assert hit['score'] >= .63, 'Real query embedding must retrieve the published experience'
        assert after['needHuman'] and not after.get('suggestion') and after['statusReason'] == 'AI_UNAVAILABLE'
        report['vector_score'] = hit['score']
        report['checks'].append('admin review -> RabbitMQ -> local BGE-M3 -> 1024-dimensional PG -> HTTP RAG source')
        report['checks'].append('chat disabled; no generated answer fabricated')
        api(candidate_path + '/retract', {'expectedRevision': 1})
        withdrawn = api('/api/ai/knowledge/answer', {'query': question}, 'SUBMITTER')
        assert not any(source['sourceFile'].startswith(f'resolved-ticket/{resolution}/') for source in withdrawn['sources'])
        wait_for(lambda: api(candidate_path), lambda row: any(job['kind'] == 'DELETE' and job['status'] == 'SUCCEEDED'
                                                            for job in row['jobs']), 'asynchronous PG retraction')
        assert sql('pgvector', f"SELECT count(*) FROM knowledge_chunk WHERE source_file LIKE 'resolved-ticket/{resolution}/%';") == '0'
        assert sql('pgvector', f"SELECT state||'|'||revision FROM knowledge_publication WHERE candidate_id='{resolution}';") == 'RETRACTED|2'
        assert sql('mysql', f"SELECT GROUP_CONCAT(action ORDER BY revision) FROM knowledge_review_audit WHERE candidate_id='{resolution}';") == 'APPROVE,RETRACT'
        report['checks'].append('retraction hidden from new retrieval; PG chunks deleted; revision-2 tombstone/audit retained')
        report['status'] = 'PASS'
    except Exception as error:
        report['error_type'] = type(error).__name__
        raise
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        log.close()
        try:
            if ticket_id is not None:
                assert isinstance(ticket_id, int) and ticket_id > 0
                if resolution is None:
                    resolution = sql('mysql', f'SELECT resolution_id FROM ticket_resolution WHERE ticket_id={ticket_id};') or None
                if resolution:
                    assert str(uuid.UUID(resolution)) == resolution
                    sql('pgvector', f"DELETE FROM knowledge_chunk WHERE source_file LIKE 'resolved-ticket/{resolution}/%';"
                        f"DELETE FROM knowledge_publication WHERE candidate_id='{resolution}';")
                sql('mysql', f'DELETE FROM cache_invalidation WHERE ticket_id={ticket_id};'
                    f'DELETE FROM ticket_log WHERE ticket_id={ticket_id};DELETE FROM ticket WHERE id={ticket_id};')
            # Only this run's random actors are removed; other budget ledgers stay intact.
            sql('mysql', 'DELETE FROM ai_usage_budget WHERE actor_id IN ('
                + ','.join(str(actor[0]) for actor in actors.values()) + ');')
            report['own_records_cleaned'] = True
        finally:
            config.unlink(missing_ok=True)
            (TEMP / 'knowledge-local-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True))


if __name__ == '__main__':
    main()
