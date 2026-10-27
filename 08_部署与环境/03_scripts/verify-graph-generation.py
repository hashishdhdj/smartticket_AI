"""Execute the explicitly authorized G5 36-request representation study, no retries.

Uses the pre-existing cumulative budget ledger and an allowlisted metered proxy.
This is single-turn representation evaluation, NOT three full Agent variants.
"""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import json
import urllib.request
import urllib.error
from local_quality_runtime import ROOT, TEMP
from recall_generation import resolve_chat_key, budget_snapshot, file_hash
from phase4_eval_runtime import BudgetProxy
from graph_generation import validate_review, validate_authorization, G5DispatchGuard, parse_answer
from graph_comparison import verify_dataset


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--review', required=True, type=Path)
    parser.add_argument('--authorization', required=True, type=Path)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--budget-ledger', required=True, type=Path)
    parser.add_argument('--private-config', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Preserve earlier execution; no overwrite or replay')
    verify_dataset()
    review = json.loads(args.review.read_text(encoding='utf-8'))
    grant = json.loads(args.authorization.read_text(encoding='utf-8'))
    bundle = json.loads(args.bundle.read_text(encoding='utf-8'))
    validate_review(review, bundle)
    before = validate_authorization(grant, review, args.review, args.bundle, args.budget_ledger)
    key = resolve_chat_key(args.private_config)
    report = {'status': 'INCOMPLETE', 'date': datetime.now(timezone.utc).isoformat(), 'stage': 'G5_REAL_GENERATION',
        'review_sha256': file_hash(args.review), 'bundle_sha256': file_hash(args.bundle), 'budget_before': before,
        'vendor_calls': 0, 'model': 'deepseek-flash', 'thinking': 'disabled', 'max_tokens': 512, 'temperature': 0,
        'independent_blind_test': False, 'semantic_grades': 'PENDING_HUMAN_REVIEW',
        'scope': '12 cases x A/B/C, common prompt/source budget. B is an adapter; no full-Agent completion metric.', 'rows': []}
    proxy = BudgetProxy(port=0, ledger_path=args.budget_ledger, chat_key=key,
        embedding={'name': 'ollama', 'model': 'bge-m3:latest', 'price_per_million_usd': 0},
        dispatch_guard=G5DispatchGuard(review), external_call_ceiling=400,
        report_path=args.output.with_suffix('.budget.json'))
    # Only reached after the new explicit, ledger/payload-bound authorization above.
    proxy.external_call_ceiling = grant['external_call_ceiling']
    proxy.batch_tag = file_hash(args.review)
    try:
        with proxy:
            endpoint = f'http://127.0.0.1:{proxy.server.server_port}/chat/v1/chat/completions'
            for row in bundle['requests']:
                payload = {'model': 'deepseek-flash', 'messages': row['messages'], 'max_tokens': 512,
                           'temperature': 0, 'response_format': {'type': 'json_object'}}
                request = urllib.request.Request(endpoint, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
                    headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + proxy.gateway_key})
                result = {'id': row['case_id'], 'arm': row['arm'], 'allowed_source_ids': row['source_ids'],
                          'answer_grade': None, 'refusal_correct_grade': None, 'groundedness_grade': None}
                try:
                    with urllib.request.urlopen(request, timeout=30) as response:
                        decoded = json.load(response)
                    choice = decoded['choices'][0]
                    result.update(http_status=200, served_model=decoded.get('model'), finish_reason=choice.get('finish_reason'),
                                  content=choice['message'].get('content'), usage=decoded.get('usage'))
                    try:
                        result['parsed_answer'] = parse_answer(result['content'], set(row['source_ids']))
                        result['output_contract_valid'] = choice.get('finish_reason') == 'stop'
                    except (ValueError, TypeError, KeyError):
                        result['output_contract_valid'] = False
                except urllib.error.HTTPError as error:
                    result.update(http_status=error.code, output_contract_valid=False, failure='UPSTREAM_OR_ENVELOPE_REJECTED')
                except (OSError, ValueError, KeyError, TypeError):
                    result.update(output_contract_valid=False, failure='TRANSPORT_OR_INVALID_RESPONSE')
                report['rows'].append(result)
                report['budget_after'] = budget_snapshot(args.budget_ledger)
                report['vendor_calls'] = report['budget_after']['external_calls'] - before['external_calls']
                args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
                print(json.dumps({'case': row['case_id'], 'arm': row['arm'], 'contract': result['output_contract_valid'],
                                  'vendor_calls': report['vendor_calls']}), flush=True)
                if result.get('http_status') in (401,402,403,429):
                    break  # A systemic credential/budget failure is not a reason to spend the whole batch.
            report['status'] = 'EXECUTED_PENDING_HUMAN_REVIEW' if len(report['rows']) == 36 else 'INCOMPLETE_PENDING_REVIEW'
            report['output_contract_valid_count'] = sum(r['output_contract_valid'] for r in report['rows'])
    finally:
        report['budget_after'] = budget_snapshot(args.budget_ledger)
        report['vendor_calls'] = report['budget_after']['external_calls'] - before['external_calls']
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
