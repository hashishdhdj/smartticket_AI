"""R06 version/label guards and current-JAR local retrieval/API verification.

No paid model invocation. Metrics describe retrieval/threshold only; human
semantic decisions and independent third-party evaluation remain separate.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import subprocess
from local_quality_runtime import LocalQualityRuntime, ROOT, TEMP
from quality_checks import DATASET, verify_artifact, verify_dataset, retrieval_row, summarize, validate_index
from quality_review import LEDGER, read, validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contracts-only', action='store_true')
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--threshold', type=float, help='Explicit post-hoc regression override; frozen R06 defaults to its original threshold')
    args = parser.parse_args()
    manifest, cases, fixture = verify_dataset()
    threshold = manifest['threshold'] if args.threshold is None else args.threshold
    if not 0 < threshold < 1:
        parser.error('threshold must be between zero and one')
    ledger = validate(read(LEDGER))
    if args.contracts_only:
        print(json.dumps({'contracts': 'PASS', 'historical_cases': len(ledger['rows']), 'static_cases': len(cases),
                          'author_reviews_pending': sum(not row['decisions'] for row in ledger['rows'])}))
        return
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    artifact = verify_artifact(jar)
    output = args.output or TEMP / (datetime.now().strftime('%Y%m%d_%H%M%S') + '_quality-closure.json')
    if output.exists():
        raise ValueError('Do not overwrite an earlier execution; select a new output')
    report = {'date': datetime.now(timezone.utc).isoformat(), 'status': 'INCOMPLETE', 'vendor_calls': 0,
        'chat_enabled': False, 'threshold': threshold,
        'threshold_scope': 'FROZEN_R06' if args.threshold is None else 'POST_HOC_REGRESSION_NOT_INDEPENDENT_EVALUATION',
        'dataset_manifest_sha256': hashlib.sha256((DATASET / 'manifest.json').read_bytes()).hexdigest(),
        'freeze_commit': '643efb9', 'label_review': 'PENDING', 'human_blind_test': False,
        'historical_generation_retested': False, 'artifact': artifact,
        'historical_review': {'total': len(ledger['rows']), 'reviewed': sum(bool(row['decisions']) for row in ledger['rows'])},
        'static_rows': [], 'dynamic_rows': [], 'dynamic_state_checks': []}
    try:
        with LocalQualityRuntime(args.java, jar, threshold=threshold) as app:
            # Record the actual model/index snapshot, not just the source-file count.
            indexed = json.loads(app.sql('pgvector', "SELECT coalesce(json_agg(json_build_object('source_ref',source_file||'#'||section_title,"
                "'chunk_index',chunk_index,'content',content,'hash',content_hash,'model',model,'dimensions',vector_dims(embedding))),'[]'::json) FROM knowledge_chunk "
                "WHERE source_file NOT LIKE 'resolved-ticket/%';"))
            expected_refs = {section['source_ref'] for section in read(ROOT / 'docs/evaluation/20261019-dynamic-knowledge.json')['static_corpus_audit']['sections']}
            report['actual_index_snapshot'] = sorted(validate_index(indexed, expected_refs), key=lambda row: (row['source_ref'], row['chunk_index']))
            report['static_section_count'] = len(expected_refs)
            report['static_chunk_count'] = len(indexed)
            for case in cases:
                answer = app.api('/api/ai/knowledge/answer', {'query': case['query']}, 'SUBMITTER')
                row = retrieval_row(case, answer, threshold)
                if row['has_generated_answer']:
                    raise AssertionError('Chat-disabled verification cannot generate an answer')
                report['static_rows'].append(row)
            report['static_metrics'] = summarize(report['static_rows'])

            ticket = app.api('/api/tickets', {'title': fixture['question'], 'description': 'R06合成巡检经验，非真实故障',
                'creatorId': app.actors['SUBMITTER'][0]}, 'SUBMITTER')['id']
            app.api(f'/api/tickets/{ticket}/assign', {'assigneeId': app.actors['HANDLER'][0], 'operatorId': app.actors['ADMIN'][0]})
            app.api(f'/api/tickets/{ticket}/resolve', {'operatorId': app.actors['HANDLER'][0], 'solution': fixture['answer']}, 'HANDLER')
            state = app.wait_for(lambda: app.api(f'/api/tickets/{ticket}/knowledge', role='SUBMITTER'),
                lambda rows: rows and rows[0].get('state') == 'DRAFT', 'Candidate generation')
            candidate = state[0]['resolutionId']
            prefix = f'resolved-ticket/{candidate}/'
            before = app.api('/api/ai/knowledge/answer', {'query': fixture['positive_queries'][0]}, 'SUBMITTER')
            if any(source['sourceFile'].startswith(prefix) for source in before['sources']):
                raise AssertionError('Unreviewed source exposed')
            report['dynamic_state_checks'].append({'name': 'unreviewed_hidden', 'status': 'PASS'})
            endpoint = f'/api/knowledge/candidates/{candidate}'
            app.api(endpoint + '/approve', {'expectedRevision': 0, 'question': fixture['question'], 'answer': fixture['answer'],
                'scope': fixture['scope'], 'redactionConfirmed': True})
            app.wait_for(lambda: app.api(endpoint), lambda detail: detail['candidate'].get('indexedRevision') == 1, 'Local dynamic indexing')
            for positive, queries in ((True, fixture['positive_queries']), (False, fixture['negative_queries'])):
                for index, query in enumerate(queries, 1):
                    # The UUID varies per run; expected identity is the reviewed version-1 source.
                    case = {'id': ('QDP' if positive else 'QDN') + str(index), 'should_abstain': not positive,
                        'expected_sources': [prefix + 'v1.md#' + fixture['question']] if positive else []}
                    answer = app.api('/api/ai/knowledge/answer', {'query': query}, 'SUBMITTER')
                    report['dynamic_rows'].append(retrieval_row(case, answer, threshold))
            report['dynamic_metrics'] = summarize(report['dynamic_rows'])
            app.api(endpoint + '/retract', {'expectedRevision': 1})
            after = app.api('/api/ai/knowledge/answer', {'query': fixture['positive_queries'][0]}, 'SUBMITTER')
            if any(source['sourceFile'].startswith(prefix) for source in after['sources']):
                raise AssertionError('Retracted source exposed')
            report['dynamic_state_checks'].append({'name': 'retracted_hidden', 'status': 'PASS'})
            app.wait_for(lambda: app.api(endpoint), lambda detail: any(job['kind'] == 'DELETE' and job['status'] == 'SUCCEEDED'
                         for job in detail['jobs']), 'Retraction cleanup')

            collection = ROOT / '07_测试与质量/02_接口测试/Postman集合导出文件/SmartTicket-阶段四鉴权与Agent.postman_collection.json'
            variables = {'baseUrl': app.base, 'submitterId': str(app.actors['SUBMITTER'][0]),
                **{role.lower() + 'Token': actor[1] for role, actor in app.actors.items()}}
            process = subprocess.run(['node', str(ROOT / '08_部署与环境/03_scripts/run-api-collection.mjs')],
                input=json.dumps({'collection': read(collection), 'variables': variables}).encode(), capture_output=True, timeout=160)
            if process.returncode:
                raise RuntimeError('Current-JAR collection HTTP assertion failed; credentials not printed')
            report['api_collection'] = json.loads(process.stdout)
        report['own_records_and_vhost_cleaned'] = True
        report['status'] = 'EXECUTED_SEMANTIC_REVIEW_PENDING'
    except Exception as failure:
        report['error_type'] = type(failure).__name__
        raise
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'report': str(output), 'status': report['status'], 'static': report['static_metrics'],
                      'dynamic': report['dynamic_metrics'], 'api_requests': report['api_collection']['requests'],
                      'api_assertions': report['api_collection']['assertions']}, ensure_ascii=True))


if __name__ == '__main__':
    main()
