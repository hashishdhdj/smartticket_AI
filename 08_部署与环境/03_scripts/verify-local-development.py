"""Rebuild derived development vectors using the real local,ollama Java profiles.

Chat and background AI work are disabled. Ticket/task records are never deleted.
Run after local model preflight and development-set calibration.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
from datetime import datetime, timezone, timedelta
from threshold_policy import active_threshold

ROOT = Path(__file__).resolve().parents[2]
TEMP = ROOT / '.tmp'
SERVICE = ROOT / '06_项目源代码/ticket-service'


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8-sig').splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def main():
    now = datetime.now(timezone(timedelta(hours=8)))
    report = {'date': now.isoformat(), 'status': 'FAILED', 'scope': 'development PostgreSQL port5432',
              'profiles': ['local', 'ollama'], 'model': 'bge-m3:latest', 'chat_enabled': False}
    calibration = json.loads((ROOT / '07_测试与质量/06_阶段测试报告/20261014_新语料开发集阈值核查.json').read_text(encoding='utf-8'))
    if calibration.get('status') != 'PASS_CALIBRATION_ONLY' or calibration.get('embedding_model') != report['model']:
        raise RuntimeError('Complete local-model calibration before updating the development index')
    expected = {(c['sourceFile'], c['chunkIndex']): hashlib.sha256(c['content'].encode()).hexdigest()
                for c in json.loads((TEMP / 'phase4-java-chunks.json').read_text(encoding='utf-8'))}
    environment = os.environ.copy()
    environment['PGPASSWORD'] = properties(ROOT / '08_部署与环境/01_docker/.env')['POSTGRES_PASSWORD']
    auth = properties(SERVICE / 'phase3-local.properties')['smartticket.phase3.users[0].token']
    java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    log = (TEMP / 'phase4-ollama-development.log').open('ab')
    process = None
    try:
        process = subprocess.Popen([str(java), f'-Djava.io.tmpdir={TEMP}', f'-Djdk.net.unixdomain.tmpdir={TEMP}',
            '-jar', str(SERVICE / 'target/ticket-service-0.0.1-SNAPSHOT.jar'), '--server.port=18084',
            '--spring.profiles.active=local,ollama', '--smartticket.ai.enabled=false',
            '--spring.ai.openai.api-key=not-configured', '--smartticket.phase3.async-enabled=false',
            '--smartticket.vector.index-on-startup=true',
            '--smartticket.vector.confidence-threshold=' + str(active_threshold())],
            cwd=SERVICE, stdout=log, stderr=log)
        deadline = time.monotonic() + 300
        rows = []
        sql = "SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (SELECT source_file,chunk_index,content_hash,model,vector_dims(embedding) dimensions FROM knowledge_chunk)t"
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Development application exited; inspect private development log')
            output = subprocess.check_output(['docker', 'exec', '-e', 'PGPASSWORD', 'smartticket-pgvector',
                'psql', '-U', 'postgres', '-d', 'smartticket_vector', '-t', '-A', '-c', sql],
                env=environment, timeout=15)
            rows = json.loads(output)
            if len(rows) == len(expected) and all(r['model'] == report['model'] and r['dimensions'] == 1024
                    and expected.get((r['source_file'], r['chunk_index'])) == r['content_hash'] for r in rows):
                break
            time.sleep(2)
        else:
            raise TimeoutError('Development index not fully refreshed within deadline')
        request = urllib.request.Request('http://127.0.0.1:18084/api/ai/knowledge/answer',
            data=json.dumps({'query': '工单提交者能查看其他人的工单吗？'}).encode('utf-8'),
            headers={'X-API-Key': auth, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response)
        if result.get('code') != 0:
            raise RuntimeError('Development RAG API returned a business failure')
        data = result['data']
        if data.get('degradedSources') or not data.get('sources'):
            raise RuntimeError('Development RAG returned degraded or empty retrieval')
        if not any(s['sourceFile'] == 'faq/05-项目权限与AI处理.md' for s in data['sources']):
            raise RuntimeError('Expected project-permissions knowledge source not retrieved')
        report.update(status='PASS', indexed_chunks=len(rows), dimensions=1024,
            confidence=data.get('confidence'), threshold=active_threshold(),
            sources=[{'sourceFile':s['sourceFile'], 'title':s['title']} for s in data['sources']],
            degraded_sources=data.get('degradedSources', []), generation_verified=False)
    except Exception as failure:
        report['error_type'] = type(failure).__name__
        raise
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=10)
        log.close()
        path = ROOT / f'07_测试与质量/06_阶段测试报告/{now:%Y%m%d_%H%M%S}_本地模型开发库接入.json'
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
