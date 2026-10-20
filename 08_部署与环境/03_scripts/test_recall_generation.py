"""Authorization, one-shot dispatch and local preflight guards; no real vendor calls."""
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest import TestCase, main, mock
import contextlib
import http.client
import importlib.util
import io
import json
import tempfile

from recall_generation import BatchDispatchGuard, CaptureGateway, budget_snapshot, digest, file_hash, validate_grant
import phase4_eval_runtime as runtime
from local_quality_runtime import LocalQualityRuntime


class DispatchGuardTest(TestCase):
    def setUp(self):
        self.messages = [{'role': 'user', 'content': 'Synthetic demo question'}]
        self.preflight = {'budget_before': {'records': 1}, 'rows': [{'messages_sha256': digest(self.messages)}]}
        self.guard = BatchDispatchGuard(self.preflight)
        self.ledger = {'calls': [{'charged_upper_rmb': 1}]}
        self.payload = {'model': 'deepseek-flash', 'messages': self.messages}

    def test_only_preflight_messages_are_allowed(self):
        self.assertFalse(self.guard('chat', {'messages': ['unreviewed']}, .01, self.ledger))
        self.assertTrue(self.guard('chat', self.payload, .01, self.ledger))

    def test_duplicate_request_cannot_be_retried(self):
        self.assertTrue(self.guard('chat', self.payload, .01, self.ledger))
        self.assertFalse(self.guard('chat', self.payload, .01, self.ledger))

    def test_batch_cost_and_call_caps_block_dispatch(self):
        for calls in ([{'charged_upper_rmb': .49}], [{'charged_upper_rmb': 0}] * 8):
            ledger = {'calls': self.ledger['calls'] + calls}
            self.assertFalse(self.guard('chat', self.payload, .02, ledger))

    def test_embedding_tools_and_streaming_are_not_in_this_authorization(self):
        self.assertFalse(self.guard('embed', self.payload, .01, self.ledger))
        for extra in ({'tools': [{'function': {'name': 'tool'}}]}, {'stream': True}):
            self.assertFalse(self.guard('chat', {**self.payload, **extra}, .01, self.ledger))


class GrantGuardTest(TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ledger = self.root / 'existing-ledger.json'
        self.ledger.write_text(json.dumps({'prior_batch_reserved_rmb': 1, 'authorized_rmb': 10,
                                          'calls': [{'kind': 'chat', 'charged_upper_rmb': 0}] * 400}), encoding='utf-8')
        self.preflight_path = self.root / 'preflight.json'
        self.preflight = {'budget_before': budget_snapshot(self.ledger)}
        self.preflight_path.write_text(json.dumps(self.preflight), encoding='utf-8')
        self.grant = {'approved': True, 'source': 'EXPLICIT_USER_APPROVAL',
                      'preflight_sha256': file_hash(self.preflight_path), 'maximum_new_calls': 8,
                      'external_call_ceiling': 408, 'batch_rmb_cap': .5, 'total_rmb_cap': 10,
                      'ledger_sha256_before': file_hash(self.ledger),
                      'expires_at': (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()}

    def test_missing_or_expired_grant_is_rejected(self):
        for changes in ({'approved': False}, {'source': 'AI_ASSUMPTION'}, {'external_call_ceiling': 409},
                        {'expires_at': (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_grant({**self.grant, **changes}, self.preflight, self.preflight_path, self.ledger)

    def test_valid_grant_binds_existing_400_calls_and_changed_ledger_is_rejected(self):
        result = validate_grant(self.grant, self.preflight, self.preflight_path, self.ledger)
        self.assertEqual(400, result['external_calls'])
        with self.ledger.open('a') as stream: stream.write(' ')
        with self.assertRaises(ValueError): validate_grant(self.grant, self.preflight, self.preflight_path, self.ledger)

    def test_execution_without_authorization_starts_neither_proxy_nor_app(self):
        script = Path(__file__).with_name('verify-rag-generation.py')
        spec = importlib.util.spec_from_file_location('generation_runner_for_test', script)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        output = self.root / 'execution.json'
        argv = [str(script), '--budget-ledger', str(self.ledger), '--private-config', 'unused', '--output', str(output)]
        with mock.patch('sys.argv', argv), mock.patch.object(module, 'load_batch', return_value=({}, [], {})), \
             mock.patch.object(module, 'verify_artifact', return_value={'jar_sha256': 'test'}), \
             mock.patch.object(module, 'active_threshold', return_value=.55), \
             mock.patch.object(module, 'resolve_chat_key', return_value='TEST_ONLY_KEY'), \
             mock.patch.object(module, 'BudgetProxy') as proxy, mock.patch.object(module, 'LocalQualityRuntime') as app, \
             contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            module.main()
        self.assertEqual(2, error.exception.code)
        proxy.assert_not_called(); app.assert_not_called(); self.assertFalse(output.exists())

    def test_explicit_missing_budget_ledger_is_never_created(self):
        path = self.root / 'missing.json'
        with mock.patch.object(runtime, 'TEMP', self.root), \
             self.assertRaisesRegex(ValueError, 'must already exist'):
            runtime.BudgetProxy(port=0, ledger_path=path, chat_key='TEST_ONLY_KEY',
                                embedding={'name': 'ollama', 'model': 'bge-m3:latest'})
        self.assertFalse(path.exists())


class CaptureGatewayTest(TestCase):
    def test_actual_chunked_http_uses_local_substitute(self):
        with CaptureGateway() as gateway:
            client = http.client.HTTPConnection('127.0.0.1', gateway.server.server_port, timeout=5)
            try:
                body = json.dumps({'model': 'deepseek-flash', 'messages': [{'role': 'user', 'content': 'synthetic'}]}).encode()
                client.request('POST', '/chat/v1/chat/completions', iter([body[:9], body[9:]]),
                               {'Authorization': 'Bearer ' + gateway.key}, encode_chunked=True)
                response = client.getresponse(); data = json.loads(response.read())
                self.assertEqual(200, response.status)
                self.assertEqual('local-substitute-not-deepseek', data['model'])
                self.assertEqual(1, len(gateway.requests))
            finally: client.close()

    def test_each_case_can_have_a_separate_temporary_submitter(self):
        with mock.patch('pathlib.Path.read_text', return_value='MYSQL_ROOT_PASSWORD=TEST_ONLY\nPOSTGRES_PASSWORD=TEST_ONLY\n'):
            app = LocalQualityRuntime('unused-java', 'unused.jar', extra_submitters=8)
        self.assertEqual(11, len(app.actors))
        self.assertEqual(11, len({actor[0] for actor in app.actors.values()}))
        self.assertTrue(all('CASE_' + str(index) in app.actors for index in range(8)))

    def test_unbounded_extra_identities_are_rejected(self):
        with self.assertRaises(ValueError):
            LocalQualityRuntime('unused-java', 'unused.jar', extra_submitters=9)

    def test_saved_answer_can_be_replayed_without_a_vendor(self):
        saved = {'suggestion': '依照资料核对租约标记', 'similarQuestions': ['合成蓝灯如何恢复'], 'needHuman': False}
        with CaptureGateway(response_payload=saved) as gateway:
            client = http.client.HTTPConnection('127.0.0.1', gateway.server.server_port, timeout=5)
            try:
                body = json.dumps({'model': 'deepseek-flash', 'messages': []})
                client.request('POST', '/chat/v1/chat/completions', body,
                               {'Authorization': 'Bearer ' + gateway.key})
                response = client.getresponse(); data = json.loads(response.read())
                self.assertEqual(200, response.status)
                self.assertEqual(saved, json.loads(data['choices'][0]['message']['content']))
                self.assertEqual('local-substitute-not-deepseek', data['model'])
            finally: client.close()


if __name__ == '__main__': main()
