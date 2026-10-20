"""Review decisions must stay tied to the actual historical response version."""
from pathlib import Path
import json
import tempfile
import unittest
from quality_review import build, digest, record, resolve_version, validate


class HumanReviewGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.queue = self.root / 'queue.json'
        self.original = {'id': 'A', 'case': {'query': 'synthetic question'}, 'response': {'suggestion': 'original'}}
        self.latest = {**self.original, 'response': {'suggestion': 'corrected response'}}
        for name, row in (('original.json', self.original), ('latest.json', self.latest)):
            (self.root / name).write_text(json.dumps({'rows': [row]}), encoding='utf-8')
        self.queue.write_text(json.dumps({'total_unique_cases': 1, 'rows': [{'dataset': 'v1', 'case_id': 'A',
            'kind': 'rag', 'report': 'original.json', 'supplemental_reports': ['latest.json']}]}), encoding='utf-8')
        self.ledger = build(self.queue, self.root)

    def decide(self, **changes):
        args = dict(dataset='v1', case_id='A', response_hash=digest(self.latest['response']),
            reviewer='Test human', grade='PARTIAL', label_correct=True, refusal_correct=None,
            notes='Source-backed note explaining the remaining condition.')
        args.update(changes)
        return record(self.ledger, **args, root=self.root)

    def test_initial_status_pending_and_history_preserved(self):
        row = validate(self.ledger, self.root)['rows'][0]
        self.assertEqual('PENDING', row['author_review_status'])
        self.assertEqual(2, len(row['versions']))
        self.assertEqual(self.original, resolve_version(row['versions'][0], 'A', self.root))

    def test_stale_original_response_cannot_sign_latest(self):
        with self.assertRaises(ValueError): self.decide(response_hash=digest(self.original['response']))

    def test_forged_top_level_reviewed_is_rejected(self):
        self.ledger['author_review_status'] = 'REVIEWED'
        with self.assertRaises(ValueError): validate(self.ledger, self.root)

    def test_forged_row_reviewed_is_rejected(self):
        self.ledger['rows'][0]['author_review_status'] = 'REVIEWED'
        with self.assertRaises(ValueError): validate(self.ledger, self.root)

    def test_report_tampering_invalidates_existing_binding(self):
        (self.root / 'latest.json').write_text('{}', encoding='utf-8')
        with self.assertRaises(ValueError): validate(self.ledger, self.root)

    def test_signed_decision_and_correction_are_both_kept(self):
        self.decide()
        self.decide(grade='FAIL', notes='A later reading found a missing source condition.')
        self.assertEqual(2, len(self.ledger['rows'][0]['decisions']))
        self.assertEqual('REVIEWED', self.ledger['author_review_status'])
        self.assertEqual('FAIL', self.ledger['rows'][0]['decisions'][-1]['grade'])

    def test_missing_reviewer_or_unsupported_notes_are_rejected(self):
        for change in ({'reviewer': ''}, {'notes': 'good'}, {'label_correct': None}):
            with self.subTest(change=change), self.assertRaises(ValueError): self.decide(**change)

    def test_target_cannot_be_silently_changed_to_original(self):
        self.ledger['rows'][0]['target_version'] = 0
        with self.assertRaises(ValueError): validate(self.ledger, self.root)

    def test_historical_results_cannot_be_claimed_as_new_blind_evaluation(self):
        self.ledger['scope'] = 'CURRENT_BLIND_PASS'
        with self.assertRaises(ValueError): validate(self.ledger, self.root)


if __name__ == '__main__': unittest.main()
