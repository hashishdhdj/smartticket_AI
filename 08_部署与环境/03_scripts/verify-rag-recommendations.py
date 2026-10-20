"""Real local retrieval/HTTP plus replay of saved answer text; zero vendor calls.

Checks recommendation presentation only, not a new DeepSeek quality evaluation.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import json
import math
import os

from local_quality_runtime import LocalQualityRuntime, ROOT, TEMP
from quality_checks import verify_artifact, verify_dataset
from quality_review import LEDGER
from recall_generation import read, file_hash, budget_snapshot, CaptureGateway, publish_fixture
from threshold_policy import active_threshold


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / ('bin/java.exe' if os.name == 'nt' else 'bin/java')))
    parser.add_argument('--budget-ledger', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    output = args.output or TEMP / (datetime.now().strftime('%Y%m%d_%H%M%S_%f') + '_rag-recommendations.json')
    if output.exists():
        parser.error('Preserve earlier results; select a new output filename')
    previous_path = ROOT / 'docs/evaluation/20261020-rag-generation-result.json'
    previous_hash = file_hash(previous_path)
    previous = read(previous_path)
    selected = [row for row in previous['rows'] if not row['case']['should_abstain']]
    if {row['id'] for row in selected} != {'NW02', 'NW13', 'QRP14', 'QDP1'}:
        raise ValueError('Unexpected replay set')
    _, _, fixture = verify_dataset()
    threshold = active_threshold()
    if threshold != .55:
        raise ValueError('The expected generation admission floor remains .55')
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    artifact = verify_artifact(jar)
    budget_before = budget_snapshot(args.budget_ledger) if args.budget_ledger else None
    review_hash = file_hash(LEDGER)
    report = {'date': datetime.now(timezone.utc).isoformat(), 'status': 'INCOMPLETE',
              'vendor_calls': 0, 'threshold': threshold, 'jar_sha256': artifact['jar_sha256'],
              'source_manifest': 'docs/evaluation/current-runtime-baseline.json',
              'previous_real_report_sha256': previous_hash,
              'model_mode': 'LOCAL_REPLAY_OF_SAVED_API_ANSWER_TEXT_NOT_NEW_DEEPSEEK_GENERATION',
              'budget_before': budget_before, 'new_semantic_accuracy': None, 'rows': []}
    try:
        with CaptureGateway() as gateway:
            with LocalQualityRuntime(args.java, jar, chat_gateway=(gateway.base, gateway.key), extra_submitters=4) as app:
                endpoint = None
                for index, old in enumerate(selected):
                    if old['id'] == 'QDP1':
                        endpoint = publish_fixture(app, fixture)
                    saved = old['response']
                    gateway.response_payload = {name: saved[name] for name in ('suggestion', 'similarQuestions', 'needHuman')}
                    start = len(gateway.requests)
                    answer = app.api('/api/ai/knowledge/answer', {'query': old['case']['query']}, 'CASE_' + str(index))
                    report['rows'].append({'id': old['id'], 'query': old['case']['query'],
                                          'before_similar_questions': saved['similarQuestions'],
                                          'response': answer, 'answer_text_unchanged': answer.get('suggestion') == saved['suggestion']})
                    if (answer['statusReason'] != 'ANSWERED' or answer['needHuman'] or answer['degradedSources']
                            or answer.get('suggestion') != saved['suggestion'] or len(gateway.requests) != start + 1):
                        raise AssertionError('Answer/retrieval behavior changed: ' + old['id'])
                    if len(answer['sources']) != len(saved['sources']):
                        raise AssertionError('Evidence was discarded instead of filtering recommendations')
                    low_titles = {s['title'] for s in answer['sources'] if not math.isfinite(s['score']) or s['score'] < threshold}
                    if low_titles.intersection(answer['similarQuestions']):
                        raise AssertionError('Low-score title still recommended')
                    prompt = json.dumps(gateway.requests[-1]['messages'], ensure_ascii=False)
                    if any(source['title'] not in prompt for source in answer['sources']):
                        raise AssertionError('Full source context was lost')
                    if old['id'] == 'QDP1' and answer['similarQuestions'] != [fixture['question']]:
                        raise AssertionError('Synthetic blue-light case still has noisy recommendations')
                    if old['id'] == 'QRP14' and answer['similarQuestions'] != ['工单可以修改哪些内容']:
                        raise AssertionError('PUT case still has noisy recommendations')
                if endpoint is not None:
                    app.api(endpoint + '/retract', {'expectedRevision': 1})
                    app.wait_for(lambda: app.api(endpoint), lambda d: any(j['kind'] == 'DELETE' and j['status'] == 'SUCCEEDED'
                                 for j in d['jobs']), 'Fixture cleanup')
        report['own_records_and_vhost_cleaned'] = True
        report['budget_after'] = budget_snapshot(args.budget_ledger) if args.budget_ledger else None
        if report['budget_after'] != budget_before or file_hash(LEDGER) != review_hash or file_hash(previous_path) != previous_hash:
            raise AssertionError('Budget or historical evaluation changed')
        report['historical_evaluation_and_reviews_unchanged'] = True
        report['local_model_replay_count'] = len(selected)
        report['status'] = 'PASS_LOCAL_RETRIEVAL_HTTP_AND_ANSWER_REPLAY'
    except Exception as error:
        report['status'] = 'FAILED'
        report['error_type'] = type(error).__name__
        raise
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('x', encoding='utf-8', newline='\n') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
    print(json.dumps({'status': report['status'], 'cases': len(report['rows']), 'vendor_calls': 0,
                      'report': str(output), 'rows': [{'id': r['id'], 'before': len(r['before_similar_questions']),
                                                     'after': len(r['response']['similarQuestions'])} for r in report['rows']]}, ensure_ascii=True))


if __name__ == '__main__':
    main()
