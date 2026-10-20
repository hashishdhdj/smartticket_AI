"""Version-bound human review of existing responses; never calls a model.

init/status/show/record operate on a new ledger, leaving historical reports intact.
The ledger records decisions supplied by the person running `record`, not AI grades.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import tempfile

ROOT = Path(__file__).resolve().parents[2]
REPORTS = ROOT / '07_测试与质量/06_阶段测试报告'
QUEUE = REPORTS / '20261016_阶段四人工复核队列.json'
LEDGER = ROOT / 'docs/evaluation/quality-review-ledger.json'


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def resolve_version(version, case_id, root=ROOT):
    path = (root / version['report']).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError('Report path escapes project')
    if hashlib.sha256(path.read_bytes()).hexdigest() != version['report_sha256']:
        raise ValueError('Historical report changed; do not reuse its review')
    report = read(path)
    rows = [row for row in report.get('rows', []) if row.get('id') == case_id]
    if len(rows) != 1 or 'response' not in rows[0] or 'case' not in rows[0]:
        raise ValueError('Expected exactly one case and actual response')
    if digest(rows[0]['response']) != version['response_sha256']:
        raise ValueError('Response hash mismatch')
    if digest(rows[0]['case']) != version['case_sha256']:
        raise ValueError('Reference version mismatch')
    return rows[0]


def build(queue_path=QUEUE, root=ROOT):
    queue = read(queue_path)
    entries = []
    seen = set()
    for item in queue['rows']:
        key = (item['dataset'], item['case_id'])
        if key in seen:
            raise ValueError('Duplicate dataset/case identity')
        seen.add(key)
        versions = []
        for name in dict.fromkeys([item['report']] + item.get('supplemental_reports', [])):
            path = queue_path.parent / name
            if not path.resolve().is_relative_to(root.resolve()):
                raise ValueError('Report path escapes project')
            report = read(path)
            rows = [row for row in report.get('rows', []) if row.get('id') == item['case_id']]
            if len(rows) != 1:
                raise ValueError(f'Missing/ambiguous response: {item["case_id"]}')
            row = rows[0]
            version = {'report': path.relative_to(root).as_posix(),
                'report_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'response_sha256': digest(row['response']), 'case_sha256': digest(row['case']),
                'jar_sha256': report.get('jar_sha256'), 'threshold': report.get('threshold')}
            resolve_version(version, item['case_id'], root)
            versions.append(version)
        entries.append({'dataset': key[0], 'case_id': key[1], 'kind': item['kind'],
            'priority': item.get('priority', 'NORMAL'), 'historical_machine_flags': item.get('machine_flags', []),
            'versions': versions, 'target_version': len(versions) - 1,
            'author_review_status': 'PENDING', 'decisions': []})
    if len(entries) != queue['total_unique_cases']:
        raise ValueError('Queue count mismatch')
    return {'schema_version': 1, 'queue': queue_path.relative_to(root).as_posix(),
        'queue_sha256': hashlib.sha256(queue_path.read_bytes()).hexdigest(),
        'scope': 'HISTORICAL_RESPONSES_NOT_CURRENT_JAR_GENERATION_OR_INDEPENDENT_BLIND_TEST',
        'author_review_status': 'PENDING', 'rows': entries}


def validate(ledger, root=ROOT):
    if ledger.get('schema_version') != 1:
        raise ValueError('Unsupported review schema')
    queue = root / ledger['queue']
    if not queue.resolve().is_relative_to(root.resolve()) or hashlib.sha256(queue.read_bytes()).hexdigest() != ledger['queue_sha256']:
        raise ValueError('Historical review queue changed')
    baseline = build(queue, root)
    if ledger.get('scope') != baseline['scope']:
        raise ValueError('Historical review cannot be relabelled as current or blind evaluation')
    rows = ledger['rows']
    if len(rows) != len(baseline['rows']):
        raise ValueError('Review count changed')
    protected = ('dataset', 'case_id', 'kind', 'versions', 'target_version', 'priority', 'historical_machine_flags')
    for row, original in zip(rows, baseline['rows']):
        if any(row.get(key) != original.get(key) for key in protected):
            raise ValueError('Review identity or response binding changed')
        target = row['versions'][row['target_version']]
        for decision in row['decisions']:
            validate_decision(decision, target['response_sha256'])
        expected = 'REVIEWED' if row['decisions'] else 'PENDING'
        if row['author_review_status'] != expected:
            raise ValueError('Review status requires a complete signed decision')
    status = 'REVIEWED' if all(row['decisions'] for row in rows) else 'PENDING'
    if ledger['author_review_status'] != status:
        raise ValueError('Incomplete ledger cannot be marked reviewed')
    return ledger


def validate_decision(decision, response_hash):
    if decision.get('response_sha256') != response_hash:
        raise ValueError('Decision targets a different response')
    if not isinstance(decision.get('reviewer'), str) or not decision['reviewer'].strip():
        raise ValueError('Reviewer name is required')
    if decision.get('grade') not in ('PASS', 'PARTIAL', 'FAIL', 'NOT_APPLICABLE'):
        raise ValueError('Invalid grade')
    if type(decision.get('reference_label_correct')) is not bool:
        raise ValueError('Reference label must be judged explicitly')
    if decision.get('refusal_correct') is not None and type(decision['refusal_correct']) is not bool:
        raise ValueError('Invalid refusal judgment')
    if not isinstance(decision.get('notes'), str) or len(decision['notes'].strip()) < 10:
        raise ValueError('At least 10 characters of source-backed review notes are required')
    if not decision.get('reviewed_at'):
        raise ValueError('Review date is required')


def record(ledger, dataset, case_id, response_hash, reviewer, grade, label_correct, refusal_correct, notes, root=ROOT):
    validate(ledger, root)
    rows = [row for row in ledger['rows'] if row['dataset'] == dataset and row['case_id'] == case_id]
    if len(rows) != 1:
        raise ValueError('Unknown dataset/case')
    row = rows[0]
    target = row['versions'][row['target_version']]
    if response_hash != target['response_sha256']:
        raise ValueError('Use the hash shown for the selected response; stale review rejected')
    decision = {'response_sha256': response_hash, 'reviewer': reviewer.strip(), 'grade': grade,
        'reference_label_correct': label_correct, 'refusal_correct': refusal_correct,
        'notes': notes.strip(), 'reviewed_at': datetime.now(timezone.utc).isoformat()}
    validate_decision(decision, response_hash)
    row['decisions'].append(decision)  # Preserve corrections and earlier reviewer decisions.
    row['author_review_status'] = 'REVIEWED'
    ledger['author_review_status'] = 'REVIEWED' if all(item['decisions'] for item in ledger['rows']) else 'PENDING'
    return validate(ledger, root)


def save(path, ledger):
    path.parent.mkdir(exist_ok=True, parents=True)
    # Atomic replacement avoids a half-written ledger. Run one reviewer process at a time.
    handle, name = tempfile.mkstemp(prefix=path.stem + '-', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(handle, 'w', encoding='utf-8', newline='\n') as output:
            json.dump(ledger, output, ensure_ascii=False, indent=2)
            output.write('\n')
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('init', 'status', 'show', 'record'))
    parser.add_argument('--dataset')
    parser.add_argument('--id')
    parser.add_argument('--response-sha256')
    parser.add_argument('--reviewer')
    parser.add_argument('--grade', choices=('PASS', 'PARTIAL', 'FAIL', 'NOT_APPLICABLE'))
    parser.add_argument('--label-correct', choices=('yes', 'no'))
    parser.add_argument('--refusal-correct', choices=('yes', 'no', 'na'))
    parser.add_argument('--notes')
    args = parser.parse_args()
    if args.action == 'init':
        if LEDGER.exists():
            validate(read(LEDGER))  # Never reset existing human decisions.
        else:
            save(LEDGER, build())
    ledger = validate(read(LEDGER))
    if args.action in ('show', 'record'):
        selected = [row for row in ledger['rows'] if row['dataset'] == args.dataset and row['case_id'] == args.id]
        if len(selected) != 1:
            parser.error('Supply an existing --dataset and --id')
        row = selected[0]
        if args.action == 'show':
            print(json.dumps({'review': row, 'historical_responses': [resolve_version(v, row['case_id']) for v in row['versions']]},
                             ensure_ascii=False, indent=2))
            return
        if any(value is None for value in (args.response_sha256, args.reviewer, args.grade, args.label_correct, args.refusal_correct, args.notes)):
            parser.error('record requires response hash, reviewer, grade, label/refusal judgments and notes')
        ledger = record(ledger, args.dataset, args.id, args.response_sha256, args.reviewer, args.grade,
            args.label_correct == 'yes', {'yes': True, 'no': False, 'na': None}[args.refusal_correct], args.notes)
        save(LEDGER, ledger)
    reviewed = sum(bool(row['decisions']) for row in ledger['rows'])
    print(json.dumps({'total': len(ledger['rows']), 'reviewed': reviewed, 'pending': len(ledger['rows']) - reviewed,
                      'author_review_status': ledger['author_review_status']}, ensure_ascii=True))


if __name__ == '__main__':
    main()
