"""Refresh isolated vectors and calibrate on the historical DEVELOPMENT set, not held-out questions."""
import csv
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
import subprocess
import time
from phase4_eval_runtime import ROOT, TEMP, BudgetProxy, EvalRuntime, embedding_provider
from threshold_policy import select_threshold

def indexed_rows():
    compose = ['docker', 'compose', '-p', 'smartticket-stage4', '--env-file', str(TEMP / 'phase4.env'),
               '-f', str(ROOT / '08_部署与环境/01_docker/compose-stage4.yml')]
    container = subprocess.check_output(compose + ['ps', '-q', 'pgvector'], timeout=15).decode().strip()
    values = dict(line.split('=', 1) for line in (TEMP / 'phase4.env').read_text().splitlines() if '=' in line)
    env = os.environ.copy(); env['PGPASSWORD'] = values['POSTGRES_PASSWORD']
    sql = "SELECT COALESCE(json_agg(row_to_json(t)), '[]'::json) FROM (SELECT source_file,chunk_index,section_title,content_hash,model,vector_dims(embedding) AS dimensions FROM knowledge_chunk ORDER BY source_file,chunk_index)t"
    raw = subprocess.check_output(['docker', 'exec', '-e', 'PGPASSWORD', container, 'psql', '-U', 'postgres',
                                   '-d', 'smartticket_vector', '-t', '-A', '-c', sql], env=env, timeout=15)
    return json.loads(raw)

cases = list(csv.DictReader((ROOT / '07_测试与质量/04_AI评估集/RAG问答评估集.csv').open(encoding='utf-8-sig')))
actors = {row['id']: 'SUBMITTER' for row in cases}
provider = embedding_provider()
report = {'date': datetime.now(timezone(timedelta(hours=8))).date().isoformat(), 'kind': 'DEVELOPMENT_CALIBRATION', 'independent_evaluation': False,
          'starting_threshold': .66, 'embedding_model': provider['model'], 'embedding_provider': provider['name'], 'dimensions': 1024, 'rows': []}
out = ROOT / '07_测试与质量/06_阶段测试报告/20261014_新语料开发集阈值核查.json'
if out.exists():
    previous = out.read_bytes()
    previous_report = json.loads(previous)
    # Preserve every distinct previous calibration, including cross-provider migrations.
    previous_hash = hashlib.sha256(previous).hexdigest()[:12]
    archive = out.with_name(out.stem + '_archive_' + previous_hash + '.json')
    if not archive.exists(): archive.write_bytes(previous)
try:
    actual_chunks = json.loads((TEMP / 'phase4-java-chunks.json').read_text(encoding='utf-8'))
    expected = {(r['sourceFile'], r['chunkIndex']): hashlib.sha256(r['content'].encode()).hexdigest() for r in actual_chunks}
    with BudgetProxy() as proxy, EvalRuntime(actors, ai=False, index=True, gateway_key=proxy.gateway_key) as app:
        deadline = time.monotonic() + (300 if provider['name'] == 'ollama' else 100)
        while time.monotonic() < deadline:
            vectors = indexed_rows()
            if (len(vectors) == len(actual_chunks)
                    and {(r['source_file'],r['chunk_index']) for r in vectors} == set(expected)
                    and all(r['dimensions'] == 1024 and r['model'] == provider['model']
                            and expected[(r['source_file'],r['chunk_index'])] == r['content_hash'] for r in vectors)): break
            time.sleep(2)
        else: raise RuntimeError('Expected current Java chunks/model vectors not ready; calibration aborted')
        snapshot = json.loads((ROOT / '07_测试与质量/06_阶段测试报告/20261014_知识库审查快照.json').read_text(encoding='utf-8'))
        assert all(expected[(r['source_file'], r['chunk_index'])] == r['content_hash'] for r in vectors), 'Vector/chunk hash mismatch'
        snapshot['vector_index_updated'] = True
        snapshot['last_verified_vector_index'] = {'scope': 'isolated smartticket-stage4 PostgreSQL port15432',
            'model': provider['model'], 'dimensions':1024, 'chunks':len(vectors), 'date':datetime.now(timezone(timedelta(hours=8))).date().isoformat()}
        (ROOT / '07_测试与质量/06_阶段测试报告/20261014_知识库审查快照.json').write_text(
            json.dumps(snapshot,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        report['indexed_rows'] = vectors
        report['corpus_sha256'] = snapshot['corpus_sha256']
        for case in cases:
            response = app.api('/api/ai/knowledge/answer', {'query': case['query']}, actor=case['id'])
            sources = [f'{s["sourceFile"]}#{s["title"]}' for s in response['sources'][:3]]
            acceptable = case['expected_sources'].split('|') if case['expected_sources'] else []
            rank = next((i + 1 for i, source in enumerate(sources) if source in acceptable), 0)
            report['rows'].append({'id': case['id'], 'expect': case['expect'], 'query': case['query'],
                'confidence': response['confidence'], 'rank': rank, 'sources': sources, 'degraded_sources': response['degradedSources']})
            out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        assert not any(r['degraded_sources'] for r in report['rows']), 'Retrieval degraded; cannot calibrate'
        hits = [r for r in report['rows'] if r['expect'] == 'hit']
        misses = [r for r in report['rows'] if r['expect'] == 'miss']
        # Coarse grid and explicit safety margin; never tune on held-out cases.
        max_negative = max(r['confidence'] for r in misses)
        selected, trials = select_threshold(report['rows'])
        report['threshold_trials'] = trials
        report['observed_positive_negative_gap'] = min(r['confidence'] for r in hits) - max_negative
        report['selected_threshold'] = selected
        report['development_hit_at_3'] = sum(r['rank'] > 0 for r in hits) / len(hits)
        report['development_mrr_at_3'] = sum(1 / r['rank'] if r['rank'] else 0 for r in hits) / len(hits)
        report['positive_above_threshold'] = sum(r['confidence'] >= selected for r in hits)
        report['negative_above_threshold'] = sum(r['confidence'] >= selected for r in misses)
        report['status'] = 'PASS_CALIBRATION_ONLY'
        report['selection_note'] = 'Coarse .55/.60/.65/.66/.70/.75 grid, zero development false answers and >=.02 negative margin; maximize positive allowance, retain .66 on ties. No held-out result used. Tiny observed score gap is not fitted exactly; semantic answerability still needs review.'
except Exception as failure:
    report['status'] = 'FAILED_OR_INCOMPLETE'
    report['error_type'] = type(failure).__name__
    raise
finally:
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: report.get(k) for k in ('status', 'selected_threshold', 'development_hit_at_3', 'positive_above_threshold')}))
