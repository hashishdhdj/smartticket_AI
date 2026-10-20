"""Verify packaged 0.55 RAG admission using real local embeddings and HTTP.

Chat is disabled. AI_UNAVAILABLE confirms generation admission, not a successful
DeepSeek answer. Historical scores, reports and human judgments are preserved.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import zipfile

from local_quality_runtime import LocalQualityRuntime, ROOT, TEMP
from quality_checks import verify_artifact, verify_dataset
from quality_review import LEDGER, read, resolve_version, validate
from threshold_policy import active_threshold


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / ('bin/java.exe' if os.name == 'nt' else 'bin/java')))
    parser.add_argument('--port', type=int, default=18083)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('Use a free port between 1024 and 65535')
    output = args.output or TEMP / (datetime.now().strftime('%Y%m%d_%H%M%S_%f') + '_rag-admission.json')
    if output.exists():
        parser.error('Output already exists; choose a new filename to preserve earlier results')

    threshold = active_threshold()
    if threshold != .55:
        raise ValueError('This check targets the reviewed 0.55 recall-first policy')
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    artifact = verify_artifact(jar)
    with zipfile.ZipFile(jar) as archive:
        configuration = archive.read('BOOT-INF/classes/application.yml').decode('utf-8')
        if 'confidence-threshold: 0.55' not in configuration:
            raise ValueError('Packaged default does not match the 0.55 policy')
    # This check uses the packaged default, not a command-line threshold override.
    for line in (TEMP / 'phase4-integration.properties').read_text(encoding='utf-8-sig').splitlines():
        if line.strip().startswith('smartticket.vector.confidence-threshold=') and float(line.split('=', 1)[1]) != threshold:
            raise ValueError('Isolated configuration overrides the packaged threshold')
    override = os.environ.get('SMARTTICKET_VECTOR_CONFIDENCE_THRESHOLD')
    if override is not None and float(override) != threshold:
        raise ValueError('Environment overrides the packaged threshold')

    ledger_hash = hashlib.sha256(LEDGER.read_bytes()).hexdigest()
    ledger = validate(read(LEDGER))
    entry = next(row for row in ledger['rows'] if row['dataset'] == 'phase4-v3-newwording' and row['case_id'] == 'NW02')
    historical = resolve_version(entry['versions'][entry['target_version']], 'NW02')
    _, cases, _ = verify_dataset()
    selected = (historical['case'], next(case for case in cases if case['id'] == 'QRP14'))
    report = {'date': datetime.now(timezone.utc).isoformat(), 'status': 'INCOMPLETE', 'threshold': threshold,
              'vendor_calls': 0, 'chat_enabled': False, 'generation_quality_verified': False,
              'jar_sha256': artifact['jar_sha256'], 'runtime_policy': artifact['runtime_policy'],
              'rows': []}
    try:
        with LocalQualityRuntime(args.java, jar, port=args.port) as app:
            for case in selected:
                answer = app.api('/api/ai/knowledge/answer', {'query': case['query']}, 'SUBMITTER')
                sources = [source['sourceFile'] + '#' + source['title'] for source in answer['sources']]
                row = {'id': case['id'], 'query': case['query'], 'confidence': answer['confidence'],
                       'matched': answer['matched'], 'sources': sources,
                       'expected_source_found': bool(set(sources).intersection(case['expected_sources'])),
                       'allowed_at_previous_threshold': answer['confidence'] >= .63,
                       'admitted_to_generation_path': answer['statusReason'] == 'AI_UNAVAILABLE',
                       'status_reason': answer['statusReason'], 'degraded_sources': answer['degradedSources'],
                       'has_generated_answer': bool(answer.get('suggestion'))}
                if case['id'] == 'NW02':
                    row['historical_confidence_not_current'] = historical['response']['confidence']
                report['rows'].append(row)
                if (not row['matched'] or not row['expected_source_found'] or row['degraded_sources']
                        or row['confidence'] < threshold or not row['admitted_to_generation_path']
                        or row['has_generated_answer']):
                    raise AssertionError('RAG admission check failed: ' + case['id'])
        report['own_records_and_vhost_cleaned'] = True
        report['ledger_unchanged'] = ledger_hash == hashlib.sha256(LEDGER.read_bytes()).hexdigest()
        if not report['ledger_unchanged']:
            raise AssertionError('Historical review ledger changed during verification')
        report['status'] = 'PASS_ADMISSION_ONLY'
    except Exception as failure:
        report['status'] = 'FAILED'
        report['error_type'] = type(failure).__name__
        raise
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('x', encoding='utf-8', newline='\n') as destination:
            json.dump(report, destination, ensure_ascii=False, indent=2)
            destination.write('\n')
    # ASCII JSON survives Windows PowerShell's native-output decoding; parsing restores Chinese.
    print(json.dumps({'report': str(output), **report}, ensure_ascii=True))


if __name__ == '__main__':
    main()
