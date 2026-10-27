"""G5 authorization, replay and citation protections; zero vendor requests."""
from copy import deepcopy
from datetime import datetime, timezone, timedelta
from pathlib import Path
import json
import tempfile
import unittest
from graph_comparison import generation_bundle, canonical_bytes, sha
from graph_generation import validate_review, validate_authorization, G5DispatchGuard, parse_answer
from recall_generation import budget_snapshot, file_hash


class GraphGenerationTest(unittest.TestCase):
    def setUp(self):
        rows = [{'id': str(i), 'query': '合成问题' + str(i),
                 'arms': {a: {'sources': [{'sourceId': a + str(i), 'excerpt': '合成证据'}]} for a in 'ABC'}} for i in range(12)]
        self.bundle = generation_bundle(rows, {'embedding': 'LOCAL_OLLAMA_BGE_M3'})
        self.review = {'maximum_new_calls': 36, 'payload_sha256': self.bundle['payload_sha256'],
                       'requests': self.bundle['requests'], 'budget_before': {'records': 0}}

    def payload(self, index=0):
        return {'model': 'deepseek-flash', 'messages': self.bundle['requests'][index]['messages'],
                'max_tokens': 512, 'temperature': 0, 'response_format': {'type': 'json_object'}}

    def test_exact_reviewed_batch_passes(self):
        validate_review(self.review, self.bundle)

    def test_ci_embedding_payload_cannot_be_sent_as_real_bge_study(self):
        self.bundle['metadata']['embedding'] = 'OFFLINE_HASHED_BIGRAM_PROTOCOL_FIXTURE_NOT_BGE'
        with self.assertRaises(ValueError):
            validate_review(self.review, self.bundle)

    def test_changed_evidence_is_rejected(self):
        self.bundle['requests'][0]['messages'][1]['content'] += 'changed'
        with self.assertRaises(ValueError):
            validate_review(self.review, self.bundle)

    def test_duplicate_dispatch_is_blocked_after_first_attempt(self):
        guard = G5DispatchGuard(self.review)
        self.assertTrue(guard('chat', self.payload(), .1, {'calls': []}))
        self.assertFalse(guard('chat', self.payload(), .1, {'calls': []}))

    def test_unknown_payload_tools_or_output_increase_blocked(self):
        for field, value in (('tools', [{}]), ('max_tokens', 513), ('temperature', .1), ('model', 'other')):
            p = self.payload()
            p[field] = value
            self.assertFalse(G5DispatchGuard(self.review)('chat', p, .1, {'calls': []}))

    def test_new_embedding_or_more_than_36_calls_blocked(self):
        self.assertFalse(G5DispatchGuard(self.review)('embed', self.payload(), .1, {'calls': []}))
        self.assertFalse(G5DispatchGuard(self.review)('chat', self.payload(), .1, {'calls': [{}] * 36}))

    def test_answer_parser_accepts_only_actual_sources(self):
        answer = {'answer': '合成演示建议', 'needHuman': False, 'sourceIds': ['source']}
        self.assertEqual(answer, parse_answer(json.dumps(answer), {'source'}))
        with self.assertRaises(ValueError):
            parse_answer(json.dumps(answer), {'different'})

    def test_duplicate_fields_or_duplicate_sources_are_rejected(self):
        with self.assertRaises(ValueError):
            parse_answer('{"answer":"a","answer":"b","needHuman":true,"sourceIds":[]}', set())
        with self.assertRaises(ValueError):
            parse_answer('{"answer":"a","needHuman":false,"sourceIds":["s","s"]}', {'s'})

    def test_valid_grant_stale_ledger_and_expiration(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            ledger, review_path, bundle_path = (directory / name for name in ('ledger.json','review.json','bundle.json'))
            ledger.write_text(json.dumps({'calls': [{'charged_upper_rmb': 0}] * 407,
                                         'prior_batch_reserved_rmb': 1, 'authorized_rmb': 10}), encoding='utf-8')
            review_path.write_text('review', encoding='utf-8')
            bundle_path.write_text('bundle', encoding='utf-8')
            before = budget_snapshot(ledger)
            review = {'budget_before': before, 'bundle_sha256': file_hash(bundle_path)}
            grant = {'approved': True, 'source': 'EXPLICIT_USER_APPROVAL', 'maximum_new_calls': 36,
                     'external_call_ceiling': 443, 'total_cumulative_rmb_cap': 10,
                     'review_sha256': file_hash(review_path), 'ledger_sha256_before': before['sha256'],
                     'expires_at': (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()}
            self.assertEqual(before, validate_authorization(grant, review, review_path, bundle_path, ledger))
            grant['approved'] = False
            with self.assertRaises(ValueError):
                validate_authorization(grant, review, review_path, bundle_path, ledger)
            grant['approved'] = True
            grant['expires_at'] = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
            with self.assertRaises(ValueError):
                validate_authorization(grant, review, review_path, bundle_path, ledger)
            grant['expires_at'] = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
            ledger.write_text(ledger.read_text() + '\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                validate_authorization(grant, review, review_path, bundle_path, ledger)


if __name__ == '__main__':
    unittest.main()
