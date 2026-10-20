"""Prepare or execute eight reviewed RAG generation cases. Preflight never contacts a vendor.

Execute requires a separate explicit authorization bound to preflight and existing
cumulative budget ledger. No automatic retry, no threshold or label tuning.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import json
import os

from local_quality_runtime import LocalQualityRuntime, ROOT, TEMP
from phase4_eval_runtime import BudgetProxy
from quality_checks import verify_artifact
from quality_review import LEDGER
from threshold_policy import active_threshold
from recall_generation import (PLAN, read, digest, file_hash, budget_snapshot, implementation_hashes,
                               load_batch, resolve_chat_key, CaptureGateway, publish_fixture,
                               validate_grant, BatchDispatchGuard)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preflight-only', action='store_true')
    parser.add_argument('--authorization', type=Path)
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--budget-ledger', required=True, type=Path)
    parser.add_argument('--private-config', required=True, type=Path)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / ('bin/java.exe' if os.name == 'nt' else 'bin/java')))
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Preserve earlier results; choose a new output filename')
    plan, cases, fixture = load_batch()
    if active_threshold() != .55:
        raise ValueError('The frozen generation batch requires threshold .55')
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    artifact = verify_artifact(jar)
    budget = budget_snapshot(args.budget_ledger)
    key = resolve_chat_key(args.private_config)  # Held in memory only; never print or store it.
    preflight = None
    if not args.preflight_only:
        if args.authorization is None or args.preflight is None:
            parser.error('Execution needs --authorization and --preflight; run local preflight first')
        preflight = read(args.preflight)
        if (preflight['status'] != 'PREFLIGHT_PASS_ZERO_VENDOR_CALLS'
                or preflight['plan_sha256'] != file_hash(PLAN) or preflight['jar_sha256'] != artifact['jar_sha256']
                or preflight['implementation_sha256'] != implementation_hashes()):
            raise ValueError('Preflight no longer matches code, cases, or runtime')
        validate_grant(read(args.authorization), preflight, args.preflight, args.budget_ledger)
    report = {'date': datetime.now(timezone.utc).isoformat(), 'status': 'INCOMPLETE', 'threshold': .55,
              'plan_sha256': file_hash(PLAN), 'jar_sha256': artifact['jar_sha256'],
              'implementation_sha256': implementation_hashes(), 'budget_before': budget,
              'vendor_calls': 0, 'human_blind_test': False, 'author_review_status': 'PENDING',
              'semantic_answer_quality': 'PENDING_HUMAN_REVIEW', 'rows': []}
    review_hash = file_hash(LEDGER)
    gateway = None
    try:
        if args.preflight_only:
            gateway = CaptureGateway()
        else:
            gateway = BudgetProxy(ledger_path=args.budget_ledger, chat_key=key,
                                  embedding={'name': 'ollama', 'model': 'bge-m3:latest', 'price_per_million_usd': 0},
                                  dispatch_guard=BatchDispatchGuard(preflight), external_call_ceiling=400,
                                  report_path=args.output.with_suffix('.budget.json'))
            # Activated only after the explicit, hash-bound grant passed above.
            gateway.external_call_ceiling = 408
            gateway.batch_tag = file_hash(args.preflight)
        with gateway:
            base = gateway.base if args.preflight_only else 'http://127.0.0.1:19014/chat'
            token = gateway.key if args.preflight_only else gateway.gateway_key
            with LocalQualityRuntime(args.java, jar, chat_gateway=(base, token), extra_submitters=len(cases)) as app:
                endpoint = None
                for index, case in enumerate(cases):
                    if case['dataset'].endswith('-dynamic') and endpoint is None:
                        endpoint = publish_fixture(app, fixture)
                    start = len(gateway.requests) if args.preflight_only else len(gateway.ledger['calls'])
                    answer = app.api('/api/ai/knowledge/answer', {'query': case['query']}, 'CASE_' + str(index))
                    row = {'id': case['id'], 'case': case, 'response': answer,
                           'answer_grade': None, 'refusal_correct_grade': None}
                    report['rows'].append(row)
                    if args.preflight_only:
                        requests = gateway.requests[start:]
                        if len(requests) > 1:
                            raise ValueError('More than one model attempt for a case')
                        payload = requests[0] if requests else None
                        row.update(messages_sha256=digest(payload['messages']) if payload else None,
                                   captured_request=payload, model_response_origin='LOCAL_SUBSTITUTE_NOT_DEEPSEEK')
                        should_dispatch = answer['matched'] and answer['confidence'] >= .55 and not answer['degradedSources']
                        if bool(payload) != should_dispatch or (payload and answer['statusReason'] != 'MODEL_REFUSED'):
                            raise ValueError('Local preflight failed to exercise the expected model path')
                    else:
                        calls = gateway.ledger['calls'][start:]
                        row['vendor_calls'] = calls
                        expected = next(r for r in preflight['rows'] if r['id'] == case['id'])
                        if len(calls) != (1 if expected['messages_sha256'] else 0):
                            raise ValueError('Unexpected dispatch count; stop this batch without retries')
                        if any(call['state'] != 'RESPONDED' for call in calls):
                            raise ValueError('Upstream failed or charge uncertain; stop without retry')
                    if answer['degradedSources']:
                        raise ValueError('Retrieval degraded; do not continue quality evaluation')
                if endpoint is not None:
                    app.api(endpoint + '/retract', {'expectedRevision': 1})
                    app.wait_for(lambda: app.api(endpoint), lambda d: any(j['kind'] == 'DELETE' and j['status'] == 'SUCCEEDED'
                                 for j in d['jobs']), 'Fixture retraction')
        report['budget_after'] = budget_snapshot(args.budget_ledger)
        report['ledger_unchanged'] = file_hash(LEDGER) == review_hash
        if not report['ledger_unchanged']:
            raise ValueError('Historical human review ledger changed')
        report['own_records_and_vhost_cleaned'] = True
        if args.preflight_only:
            if report['budget_after'] != budget:
                raise ValueError('Budget ledger changed during zero-call preflight')
            report['captured_request_count'] = sum(bool(r['messages_sha256']) for r in report['rows'])
            report['authorization_present'] = False
            report['status'] = 'PREFLIGHT_PASS_ZERO_VENDOR_CALLS'
        else:
            report['vendor_calls'] = report['budget_after']['external_calls'] - budget['external_calls']
            report['status'] = 'EXECUTED_PENDING_HUMAN_SEMANTIC_REVIEW'
    except Exception as failure:
        report['status'] = 'FAILED'
        report['error_type'] = type(failure).__name__
        raise
    finally:
        report['budget_after'] = budget_snapshot(args.budget_ledger)
        report['vendor_calls'] = report['budget_after']['external_calls'] - budget['external_calls']
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x', encoding='utf-8', newline='\n') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
    print(json.dumps({'status': report['status'], 'cases': len(report['rows']), 'vendor_calls': report['vendor_calls'],
                      'report': str(args.output), 'external_calls': report['budget_after']['external_calls']}, ensure_ascii=True))


if __name__ == '__main__':
    main()
