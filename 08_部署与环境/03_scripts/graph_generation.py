"""G5 explicit, hash-bound authorization and at-most-once vendor dispatch guards."""
from datetime import datetime, timezone
import json
from graph_comparison import canonical_bytes, sha, COMMON_PROMPT
from recall_generation import budget_snapshot, digest, file_hash


def validate_review(review, bundle):
    requests = bundle['requests']
    if (bundle['metadata']['embedding'] != 'LOCAL_OLLAMA_BGE_M3'
            or review['maximum_new_calls'] != 36 or bundle['request_count'] != 36 or len(requests) != 36
            or review['payload_sha256'] != sha(canonical_bytes(requests))
            or review['requests'] != requests or bundle['payload_sha256'] != review['payload_sha256']):
        raise ValueError('Only the reviewed local-BGE 36-request batch can be sent')
    identities = set()
    messages = set()
    for row in requests:
        identity = (row['case_id'], row['arm'])
        if (row['arm'] not in {'A','B','C'} or identity in identities or len(row['messages']) != 2
                or row['messages'][0] != {'role': 'system', 'content': COMMON_PROMPT}
                or row['max_tokens'] != 512 or row['temperature'] != 0 or len(row['source_ids']) > 2):
            raise ValueError('Generation study contract changed')
        body = json.loads(row['messages'][1]['content'])
        if (sum(len(canonical_bytes(s)) for s in body['sources']) > 6500
                or [s['sourceId'] for s in body['sources']] != row['source_ids']):
            raise ValueError('Source envelope changed')
        identities.add(identity)
        messages.add(digest(row['messages']))
    if len(identities) != 36 or len(messages) != 36:
        raise ValueError('Duplicate payload/case identities')
    cases = {case for case, arm in identities}
    if len(cases) != 12 or any({arm for case, arm in identities if case == wanted} != {'A','B','C'} for wanted in cases):
        raise ValueError('Expected twelve complete three-arm cases')


def validate_authorization(grant, review, review_path, bundle_path, ledger_path):
    snapshot = budget_snapshot(ledger_path)
    if (grant.get('approved') is not True or grant.get('source') != 'EXPLICIT_USER_APPROVAL'
            or grant.get('review_sha256') != file_hash(review_path)
            or review['bundle_sha256'] != file_hash(bundle_path)
            or grant.get('maximum_new_calls') != 36 or grant.get('external_call_ceiling') != 443
            or grant.get('total_cumulative_rmb_cap') != 10
            or snapshot != review['budget_before'] or snapshot['external_calls'] != 407
            or grant.get('ledger_sha256_before') != snapshot['sha256']
            or snapshot['authorized_rmb'] != 10 or snapshot['estimated_upper_rmb'] >= 10
            or datetime.fromisoformat(grant['expires_at']) <= datetime.now(timezone.utc)):
        raise ValueError('Missing, stale or out-of-scope G5 authorization')
    return snapshot


class G5DispatchGuard:
    def __init__(self, review):
        self.allowed = {digest(r['messages']) for r in review['requests']}
        self.seen = set()
        self.start_records = review['budget_before']['records']

    def __call__(self, kind, payload, reserve, ledger):
        fingerprint = digest(payload.get('messages'))
        if (kind != 'chat' or fingerprint not in self.allowed or fingerprint in self.seen
                or len(ledger['calls']) - self.start_records >= 36 or payload.get('tools') or payload.get('stream')
                or payload.get('model') != 'deepseek-flash' or payload.get('max_tokens') != 512
                or payload.get('temperature') != 0 or payload.get('response_format') != {'type': 'json_object'}):
            return False
        self.seen.add(fingerprint)
        return True


def parse_answer(content, available):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError('Duplicate answer field')
            result[key] = value
        return result
    result = json.loads(content, object_pairs_hook=pairs)
    if (set(result) != {'answer', 'needHuman', 'sourceIds'} or not isinstance(result['answer'], str)
            or not result['answer'].strip() or len(result['answer']) > 4000 or type(result['needHuman']) is not bool
            or not isinstance(result['sourceIds'], list) or len(result['sourceIds']) > 6
            or any(not isinstance(s, str) or s not in available for s in result['sourceIds'])
            or len(set(result['sourceIds'])) != len(result['sourceIds'])):
        raise ValueError('Invalid or unsupported answer contract')
    return result
