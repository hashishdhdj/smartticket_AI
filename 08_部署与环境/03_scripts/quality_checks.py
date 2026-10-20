"""Offline guards and separate retrieval metrics for R06, with no model dispatch."""
from pathlib import Path
import hashlib
import json
import re
import unicodedata
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / '07_测试与质量/04_AI评估集/quality-r06-v1'


def canonical(text):
    return re.sub(r'\s+', '', unicodedata.normalize('NFKC', text))


def verify_dataset(directory=DATASET, root=ROOT):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    if not manifest['labels_locked_before_first_run'] or manifest['label_review'] != 'PENDING':
        raise ValueError('Draft labels must retain their review status')
    for name, info in manifest['files'].items():
        path = (directory / name).resolve()
        if not path.is_relative_to(directory.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != info['sha256']:
            raise ValueError('Frozen dataset changed')
    known = set()
    for directory_name, expected in manifest['known_dataset_manifests'].items():
        path = root / '07_测试与质量/04_AI评估集' / directory_name
        if hashlib.sha256((path / 'manifest.json').read_bytes()).hexdigest() != expected:
            raise ValueError('Known evaluation baseline changed')
        for file in path.glob('*.jsonl'):
            for line in file.read_text(encoding='utf-8').splitlines():
                case = json.loads(line)
                known.add(canonical(case.get('query', case.get('message', ''))))
    cases = [json.loads(line) for line in (directory / 'retrieval.jsonl').read_text(encoding='utf-8').splitlines()]
    ids, queries = set(), set()
    for case in cases:
        query = canonical(case['query'])
        if case['id'] in ids or query in known or query in queries:
            raise ValueError('Duplicate case or known question')
        ids.add(case['id'])
        queries.add(query)
        if bool(case['expected_sources']) == case['should_abstain'] or case['author_review_status'] != 'PENDING':
            raise ValueError('Invalid reference contract')
        for ref in case['expected_sources']:
            name, title = ref.split('#', 1)
            path = root / '06_项目源代码/ticket-service/src/main/resources/knowledge' / name
            parts = re.split(r'^## (.+)\r?$', path.read_text(encoding='utf-8-sig'), flags=re.M)
            sections = {parts[index].strip(): parts[index + 1].strip() for index in range(1, len(parts), 2)}
            if sections.get(title) != case['reference_answer'] or hashlib.sha256(case['reference_answer'].encode()).hexdigest() != case['reference_body_sha256']:
                raise ValueError('Reference source content changed')
    if len(cases) != manifest['files']['retrieval.jsonl']['count']:
        raise ValueError('Case count mismatch')
    return manifest, cases, json.loads((directory / 'dynamic.json').read_text(encoding='utf-8'))


def retrieval_row(case, response, threshold):
    expected = set(case['expected_sources'])
    actual = [source['sourceFile'] + '#' + source['title'] for source in response['sources'][:3]]
    ranks = [index for index, source in enumerate(actual, 1) if source in expected]
    # All responses use needHuman=true with chat disabled. It cannot measure gate quality.
    gate_allowed = bool(response['matched']) and response['confidence'] >= threshold
    return {'id': case['id'], 'should_abstain': case['should_abstain'], 'expected_sources': sorted(expected),
        'actual_sources': actual, 'hit_at_3': bool(ranks) if expected else None,
        'recall_at_3': len(expected.intersection(actual)) / len(expected) if expected else None,
        'reciprocal_rank_at_3': 1 / ranks[0] if ranks else (0 if expected else None),
        'gate_allowed': gate_allowed, 'confidence': response['confidence'],
        'retrieval_healthy': not response['degradedSources'], 'status_reason': response['statusReason'],
        'has_generated_answer': bool(response.get('suggestion'))}


def summarize(rows):
    positives = [row for row in rows if not row['should_abstain']]
    negatives = [row for row in rows if row['should_abstain']]
    def mean(items, key):
        return sum(item[key] for item in items) / len(items) if items else None
    return {'count': len(rows), 'positive_count': len(positives), 'negative_count': len(negatives),
        'hit_at_3': mean(positives, 'hit_at_3'), 'macro_recall_at_3': mean(positives, 'recall_at_3'),
        'mrr_at_3': mean(positives, 'reciprocal_rank_at_3'),
        'positive_gate_coverage': mean(positives, 'gate_allowed'),
        'negative_gate_pass_rate': mean(negatives, 'gate_allowed'),
        'degraded_count': sum(not row['retrieval_healthy'] for row in rows),
        'generated_answer_count': sum(row['has_generated_answer'] for row in rows),
        'semantic_answer_support': None, 'agent_goal_completion': None, 'tool_violation_rate': None,
        'metric_limits': 'Source-reference retrieval and threshold only; not answer accuracy or Agent capability.'}


def validate_index(indexed, expected_refs):
    identities = {(row['source_ref'], row['chunk_index']) for row in indexed}
    if {row['source_ref'] for row in indexed} != expected_refs or len(identities) != len(indexed):
        raise ValueError('Unexpected test corpus; do not delete existing data automatically')
    for row in indexed:
        if row['model'] != 'bge-m3:latest' or row['dimensions'] != 1024 or hashlib.sha256(row['content'].encode()).hexdigest() != row['hash']:
            raise ValueError('Model/dimension/content fingerprint mismatch')
    return [{key: value for key, value in row.items() if key != 'content'} for row in indexed]


def verify_artifact(jar, root=ROOT):
    baseline = json.loads((root / 'docs/evaluation/20261019-dynamic-knowledge.json').read_text(encoding='utf-8'))
    audit = baseline['known_credentials_and_jar_audit']
    actual_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    rebuilt = actual_hash != audit['jar_sha256']
    if rebuilt:
        path = root / '.tmp/quality-artifact-build.json'
        if not path.is_file():
            raise ValueError('Unverified JAR; run build-quality-artifact.py with the current source first')
        build = json.loads(path.read_text(encoding='utf-8'))
        source_hash = hashlib.sha256(json.dumps(audit['source_files'], sort_keys=True).encode()).hexdigest()
        if build.get('status') != 'PASS' or build.get('jar_sha256') != actual_hash or build.get('source_manifest_sha256') != source_hash or build.get('vendor_calls') != 0:
            raise ValueError('Current build evidence does not match this JAR/source')
        for name in ('unit', 'integration'):
            totals = build.get('profiles', {}).get(name, {})
            expected = baseline['java_profiles'][name]['totals']['tests']
            if totals.get('tests') != expected or any(totals.get(key) != 0 for key in ('failures', 'errors', 'skipped')):
                raise ValueError('Rebuilt artifact requires all unit and integration checks')
    service = root / '06_项目源代码/ticket-service'
    sources = audit['source_files']
    for source in sources:
        path = (service / source['path']).resolve()
        if not path.is_relative_to(service.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
            raise ValueError('Runtime/test/source manifest changed; rebuild and revalidate first')
    tracked = {path.relative_to(service).as_posix() for folder in ('src/main', 'src/test')
               for path in (service / folder).rglob('*') if path.is_file()}
    if not tracked.issubset({source['path'] for source in sources}):
        raise ValueError('Unverified source added to the service')
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if any('/com/smartticket/phase3/' in name or Path(name).name in ('.env', 'application-local.yml', 'phase3-local.properties') for name in names):
            raise ValueError('Private config or obsolete package in artifact')
        for name in names:
            if name.startswith('BOOT-INF/classes/') and name.endswith(('.yml', '.yaml', '.properties', '.md', '.json')):
                data = archive.read(name)
                if re.search(rb'(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', data):
                    raise ValueError('Token/private-key pattern in artifact resources')
    return {'jar_sha256': actual_hash, 'source_files_checked': len(sources), 'source_files': sources,
            'private_config_in_jar': False, 'obsolete_package_in_jar': False,
            'runtime_changed_from_R05': False, 'artifact_reused_after_hash_verification': not rebuilt,
            'current_source_rebuilt_and_tested': rebuilt}
