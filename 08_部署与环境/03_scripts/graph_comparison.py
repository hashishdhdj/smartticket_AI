"""G5 frozen development comparison. Retention metrics are NOT semantic answer grades."""
from pathlib import Path
import hashlib
import json
import re
import statistics

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / '07_测试与质量/04_AI评估集/experience-g5-v1'
FIELDS = ('symptoms', 'conditions', 'steps', 'contraindications', 'counterexamples', 'hypotheses')
COMMON_PROMPT = ('你是工单辅助助手。仅依据给定sources回答query；各来源的条件、步骤、反例不得混用。'
                 '先核对适用条件与禁止条件；条件未知则提出核实步骤，不把假设当确诊、失败反例当成功步骤。'
                 '资料不足说明不足并转人工，不编造退款到账时刻、赔偿或已执行的操作。'
                 'SHARED_DEMO为合成演示；模拟规则非本项目实际业务能力。只输出JSON对象，字段为'
                 'answer文本、needHuman布尔、sourceIds数组；引用仅复制sources实际提供的sourceId。')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical_bytes(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def verify_dataset(directory=DATASET, root=ROOT):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    if manifest['independent_blind_test'] or manifest['label_review'] != 'PENDING':
        raise ValueError('Do not convert development labels into independent human grades')
    for file, digest in manifest['source_hashes'].items():
        path = (root / file).resolve()
        if not path.is_relative_to(root.resolve()) or sha(path.read_bytes()) != digest:
            raise ValueError('Frozen G5 source changed')
    data = (directory / 'cases.json').read_bytes()
    if sha(data) != manifest['cases_sha256'] or sha(COMMON_PROMPT.encode()) != manifest['prompt_sha256']:
        raise ValueError('Frozen G5 questions/prompt changed')
    cases = json.loads(data)
    ids = [c['id'] for c in cases]
    if len(cases) != 12 or len(set(ids)) != len(ids) or len({c['query'] for c in cases}) != len(cases):
        raise ValueError('Invalid G5 case identities')
    for case in cases:
        if bool(case['expected_experience']) == case['should_abstain'] or case['label_review'] != 'PENDING':
            raise ValueError('Invalid draft expectation')
    return manifest, cases


def source_key(source_id):
    match = re.fullmatch(r'resolved-ticket/([0-9a-f-]{36})/v([1-9][0-9]*)\.md#0', source_id)
    if not match:
        raise ValueError('Not a versioned experience source')
    import uuid
    if str(uuid.UUID(match[1])) != match[1]:
        raise ValueError('Noncanonical candidate')
    return match[1], int(match[2])


def card_view(binding, source_id):
    """Evaluation-only B adapter: receives admin binding data, releases only the immutable card."""
    candidate, revision = source_key(source_id)
    version, stored = binding['version'], binding['card']
    card = stored['content']
    published_text = ('适用范围：已审核的合成工单经验，仅限演示；不代表通用产品规则。\n问题：'
                      + stored['question'] + '\n解决方案：' + stored['answer'])
    if (version['candidateId'] != candidate or version['revision'] != revision or version['state'] != 'APPROVED'
            or version['cardId'] != stored['id'] or version['cardHash'] != stored['hash']
            or version['publicationHash'] != sha(published_text.encode('utf-8'))
            or not binding['evidenceHealthy'] or card['supportStatus'] != 'REFERENCED_DEMO'):
        raise ValueError('Card adapter cannot cross visibility/version/health fences')
    return {'sourceId': source_id, 'title': card['problemType'], 'scope': 'SHARED_DEMO',
            'factScope': card['factScope'], 'revision': revision,
            'facts': {'problem': card['problemType'], **{f: card[f] for f in FIELDS},
                      'outcome': ['业务效果待核实（非独立验证）']},
            'references': card['evidenceRefs'],
            'notice': '评估专用结构化卡片适配器；不是已注册Agent工具。仅限合成演示，实际效果待核实。'}


def admit(sources, maximum=6500):
    """Same representation-study source budget for A/B/C; never cut a condition in half."""
    rows, remaining, omitted = [], maximum, 0
    for row in sources:
        size = len(canonical_bytes(row))
        if size > remaining:
            omitted += 1
            continue
        rows.append(row)
        remaining -= size
    return rows, omitted


def observation(case, sources, source_by_experience, seed_by_id):
    expected = {source_by_experience[e] for e in case['expected_experience']}
    actual = [row['sourceId'] for row in sources]
    ranks = [actual.index(s) + 1 for s in expected if s in actual]
    by_source = {row['sourceId']: row for row in sources}
    retained, total = 0, 0
    for logical in case['expected_experience']:
        card = seed_by_id[logical]['experience_card']
        # Credit only the expected provenance, never matching words borrowed from a wrong neighbor.
        text = json.dumps(by_source.get(source_by_experience[logical], {}), ensure_ascii=False)
        for field in ('conditions', 'steps', 'contraindications'):
            for value in card[field]:
                total += 1
                retained += value in text
    return {'expected_sources': sorted(expected), 'actual_sources': actual,
            'hit_at_returned_limit': bool(ranks) if expected else None,
            'reciprocal_rank': 1 / min(ranks) if ranks else (0 if expected else None),
            'required_literal_retained': retained, 'required_literal_total': total,
            'literal_retention_ratio': retained / total if total else None,
            'should_abstain': case['should_abstain'],
            'negative_has_retrieval_evidence': bool(actual) if case['should_abstain'] else None,
            'applicability_correct': None, 'answer_correct': None, 'refusal_correct': None,
            'measurement_scope': 'Retrieval and literal evidence retention only; no generated-answer grade'}


def summarize(rows):
    result = {}
    for arm in ('A', 'B', 'C'):
        values = [r['arms'][arm] for r in rows]
        pos = [v for v in values if v['checks']['hit_at_returned_limit'] is not None]
        retained = sum(v['checks']['required_literal_retained'] for v in pos)
        total = sum(v['checks']['required_literal_total'] for v in pos)
        result[arm] = {'count': len(values), 'positive_count': len(pos),
            'source_hit_rate': statistics.mean(v['checks']['hit_at_returned_limit'] for v in pos) if pos else None,
            'mrr': statistics.mean(v['checks']['reciprocal_rank'] for v in pos) if pos else None,
            'micro_literal_retention': retained / total if total else None,
            'median_retrieval_ms': statistics.median(v['retrieval_ms'] for v in values),
            'median_representation_bytes': statistics.median(v['representation_bytes'] for v in values),
            'semantic_answer_accuracy': None, 'refusal_accuracy': None,
            'negative_evidence_count': sum(v['checks']['negative_has_retrieval_evidence'] is True for v in values)}
    return result


def generation_bundle(rows, metadata):
    requests = []
    for row in rows:
        for arm in ('A', 'B', 'C'):
            sources, omitted = admit(row['arms'][arm]['sources'][:2])
            omitted += max(0, len(row['arms'][arm]['sources']) - 2)
            request = {'case_id': row['id'], 'arm': arm, 'messages': [
                {'role': 'system', 'content': COMMON_PROMPT},
                {'role': 'user', 'content': json.dumps({'query': row['query'], 'sources': sources}, ensure_ascii=False)}],
                'temperature': 0, 'max_tokens': 512, 'source_ids': [s['sourceId'] for s in sources],
                'omitted_sources': omitted}
            requests.append(request)
    return {'schema_version': 1, 'status': 'PREPARED_NOT_SENT', 'vendor_calls': 0,
            'scope': 'Synthetic development representation study; B is evaluation-only, not an Agent tool comparison',
            'common_source_context_utf8_bytes': 6500, 'metadata': metadata, 'requests': requests,
            'request_count': len(requests), 'payload_sha256': sha(canonical_bytes(requests))}
