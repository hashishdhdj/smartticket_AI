"""G5 metric/budget/provenance guards; no model or database access."""
from pathlib import Path
from copy import deepcopy
import json
import math
import shutil
import tempfile
import unittest
from graph_comparison import (DATASET, ROOT, verify_dataset, source_key, card_view, admit,
                              observation, summarize, generation_bundle, canonical_bytes, sha)
from graph_comparison_fixture import vector


class ComparisonContractsTest(unittest.TestCase):
    def setUp(self):
        self.source = 'resolved-ticket/12222222-2222-4222-8222-222222222222/v1.md#0'
        self.card = {'problemType': '合成问题', 'symptoms': ['症状'], 'conditions': ['原手机未停用'],
                     'steps': ['核实身份', '再执行'], 'contraindications': ['未核实不得执行'], 'counterexamples': [],
                     'hypotheses': ['可能配置有误，未证实'], 'factScope': 'PROJECT_RULE',
                     'supportStatus': 'REFERENCED_DEMO', 'evidenceRefs': [{'path': 'docs/demo.md', 'title': '演示'}]}
        self.binding = {'version': {'candidateId': source_key(self.source)[0], 'revision': 1, 'state': 'APPROVED',
                                   'cardId': 'card', 'cardHash': 'hash', 'publicationHash': sha(('适用范围：已审核的合成工单经验，仅限演示；不代表通用产品规则。\n问题：合成问题\n解决方案：合成说明').encode())},
                        'card': {'id': 'card', 'hash': 'hash', 'content': self.card, 'question': '合成问题', 'answer': '合成说明'},
                        'evidenceHealthy': True, 'evidence': {'authorId': 100, 'private_interaction': '内部原文'}}

    def test_frozen_contract_is_development_not_blind_grade(self):
        manifest, cases = verify_dataset()
        self.assertFalse(manifest['independent_blind_test'])
        self.assertEqual(12, len(cases))
        self.assertTrue(all(c['label_review'] == 'PENDING' for c in cases))

    def test_modified_question_is_rejected_before_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'dataset'
            shutil.copytree(DATASET, target)
            (target / 'cases.json').write_text('[]', encoding='utf-8')
            with self.assertRaises(ValueError):
                verify_dataset(target, ROOT)

    def test_machine_cannot_self_sign_frozen_human_labels(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'dataset'
            shutil.copytree(DATASET, target)
            path = target / 'manifest.json'
            value = json.loads(path.read_text(encoding='utf-8'))
            value['label_review'] = 'APPROVED'
            path.write_text(json.dumps(value), encoding='utf-8')
            with self.assertRaises(ValueError):
                verify_dataset(target, ROOT)

    def test_source_key_requires_real_canonical_versioned_source(self):
        self.assertEqual(1, source_key(self.source)[1])
        for bad in ('node-hash', self.source[:-1] + '1', self.source.replace('/v1.', '/v0.'), self.source.upper()):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                source_key(bad)

    def test_card_adapter_drops_privileged_staff_snapshot(self):
        view = card_view(self.binding, self.source)
        self.assertNotIn('authorId', json.dumps(view))
        self.assertNotIn('内部原文', json.dumps(view, ensure_ascii=False))
        self.assertEqual('SHARED_DEMO', view['scope'])
        self.assertIn('未证实', view['facts']['hypotheses'][0])

    def test_card_adapter_rejects_revision_or_hash_mismatch(self):
        for field, bad in (('revision', 2), ('cardHash', 'tampered'), ('candidateId', 'foreign'), ('state', 'DRAFT')):
            value = deepcopy(self.binding)
            value['version'][field] = bad
            with self.subTest(field=field), self.assertRaises(ValueError):
                card_view(value, self.source)

    def test_card_adapter_rejects_failed_feedback_or_unverified_support(self):
        value = deepcopy(self.binding)
        value['evidenceHealthy'] = False
        with self.assertRaises(ValueError):
            card_view(value, self.source)

    def test_card_adapter_checks_publication_text_hash(self):
        value = deepcopy(self.binding)
        value['card']['answer'] = '被篡改的说明'
        with self.assertRaises(ValueError):
            card_view(value, self.source)
        value = deepcopy(self.binding)
        value['card']['content']['supportStatus'] = 'UNVERIFIED'
        with self.assertRaises(ValueError):
            card_view(value, self.source)

    def test_atomic_source_budget_keeps_negation_and_order(self):
        source = card_view(self.binding, self.source)
        result, omitted = admit([source], maximum=10000)
        self.assertEqual([source], result)
        self.assertEqual(['核实身份', '再执行'], result[0]['facts']['steps'])
        self.assertIn('未停用', result[0]['facts']['conditions'][0])
        self.assertEqual(0, omitted)

    def test_oversized_source_has_no_partial_conditions(self):
        source = card_view(self.binding, self.source)
        result, omitted = admit([source], maximum=50)
        self.assertEqual([], result)
        self.assertEqual(1, omitted)

    def test_shared_budget_does_not_reset_for_each_source(self):
        source = card_view(self.binding, self.source)
        result, omitted = admit([source, source], maximum=len(canonical_bytes(source)) + 10)
        self.assertEqual(1, len(result))
        self.assertEqual(1, omitted)

    def obs(self, negative=False, sources=None):
        case = {'expected_experience': [] if negative else ['EXP01'], 'should_abstain': negative}
        seed = {'EXP01': {'experience_card': {'conditions': ['原手机未停用'], 'steps': ['核实身份'], 'contraindications': ['未核实不得执行']}}}
        return observation(case, sources or [], {'EXP01': self.source}, seed)

    def test_negative_retrieval_is_not_automatically_an_incorrect_answer(self):
        result = self.obs(True, [{'sourceId': self.source, 'excerpt': '相关但不回答具体退款时刻'}])
        self.assertTrue(result['negative_has_retrieval_evidence'])
        self.assertIsNone(result['answer_correct'])
        self.assertIsNone(result['refusal_correct'])
        self.assertIsNone(result['hit_at_returned_limit'])

    def test_literal_coverage_is_explicit_and_not_semantic_success(self):
        result = self.obs(sources=[{'sourceId': self.source, 'excerpt': '原手机未停用；核实身份'}])
        self.assertEqual(2 / 3, result['literal_retention_ratio'])
        self.assertIsNone(result['applicability_correct'])
        self.assertIsNone(result['answer_correct'])

    def test_failed_recall_remains_in_positive_denominator(self):
        rows = []
        for check in (self.obs(sources=[{'sourceId': self.source}]), self.obs(), self.obs(True)):
            arm = {'checks': check, 'retrieval_ms': 1, 'representation_bytes': 10}
            rows.append({'arms': {k: arm for k in 'ABC'}})
        result = summarize(rows)
        self.assertEqual(2, result['A']['positive_count'])
        self.assertEqual(.5, result['A']['source_hit_rate'])
        self.assertIsNone(result['C']['semantic_answer_accuracy'])

    def test_wrong_neighbor_cannot_supply_expected_source_literal_credit(self):
        result = self.obs(sources=[{'sourceId': 'wrong-neighbor', 'excerpt': '原手机未停用；核实身份；未核实不得执行'}])
        self.assertEqual(0, result['required_literal_retained'])
        self.assertFalse(result['hit_at_returned_limit'])

    def test_reciprocal_rank_uses_actual_position(self):
        result = self.obs(sources=[{'sourceId': 'other'}, {'sourceId': self.source}])
        self.assertEqual(.5, result['reciprocal_rank'])

    def test_generation_inputs_share_prompt_and_hard_source_count(self):
        sources = [{'sourceId': str(i), 'excerpt': '证据'} for i in range(3)]
        rows = [{'id': 'test', 'query': '问题', 'arms': {a: {'sources': sources} for a in 'ABC'}}]
        bundle = generation_bundle(rows, {'embedding': 'TEST'})
        requests = bundle['requests']
        self.assertEqual(3, bundle['request_count'])
        self.assertEqual(1, len({r['messages'][0]['content'] for r in requests}))
        self.assertTrue(all(len(r['source_ids']) == 2 for r in requests))
        self.assertEqual(sha(canonical_bytes(requests)), bundle['payload_sha256'])

    def test_preparing_generation_never_claims_sent_or_scored(self):
        result = generation_bundle([], {})
        self.assertEqual('PREPARED_NOT_SENT', result['status'])
        self.assertEqual(0, result['vendor_calls'])

    def test_hash_embedding_fixture_is_normalized_and_repeatable(self):
        a, b = vector('手机验证码'), vector('手机验证码')
        self.assertEqual(1024, len(a))
        self.assertEqual(a, b)
        self.assertAlmostEqual(1, math.sqrt(sum(x * x for x in a)))

    def test_ci_hash_embedding_is_not_a_constant_vector(self):
        self.assertNotEqual(vector('手机验证码'), vector('工单版本冲突'))


if __name__ == '__main__':
    unittest.main()
