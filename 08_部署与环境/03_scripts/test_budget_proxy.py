"""Real local HTTP contract tests, mocked upstream only: never uses real vendor credentials/calls."""
from pathlib import Path
from unittest import TestCase, main, mock
import http.client
import io
import json
import tempfile
import phase4_eval_runtime as runtime

class Response(io.BytesIO):
    status = 200

class ProxyTests(TestCase):
    def setUp(self):
        (runtime.ROOT / '.tmp').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=runtime.ROOT / '.tmp')
        self.directory = Path(self.temp.name)
        self.temp_patch = mock.patch.object(runtime, 'TEMP', self.directory)
        self.key_patch = mock.patch.object(runtime, 'credential', return_value='TEST_ONLY_KEY')
        self.temp_patch.start(); self.key_patch.start()
        self.proxy = runtime.BudgetProxy(port=0, report_path=self.directory/'test-summary.json')
        self.proxy.__enter__()
        self.client = http.client.HTTPConnection('127.0.0.1', self.proxy.server.server_port, timeout=5)

    def tearDown(self):
        self.client.close(); self.proxy.__exit__(None,None,None)
        self.temp_patch.stop(); self.key_patch.stop(); self.temp.cleanup()

    def send(self, payload, chunked=False, key=True):
        body = json.dumps(payload).encode()
        headers = {'Content-Type':'application/json'}
        if key: headers['Authorization']='Bearer '+self.proxy.gateway_key
        self.client.request('POST','/embed/v1/embeddings' if 'input' in payload else '/chat/v1/chat/completions',
            body=iter([body[:17],body[17:]]) if chunked else body, headers=headers, encode_chunked=chunked)
        response=self.client.getresponse(); response.read(); return response.status

    def test_chunked_sdk_request_records_usage(self):
        payload={'model':'text-embedding-v4','input':['synthetic question'],'dimensions':1024}
        with mock.patch.object(runtime.urllib.request,'urlopen',return_value=Response(
                b'{"usage":{"prompt_tokens":20,"total_tokens":20},"data":[]}')) as upstream:
            self.assertEqual(200,self.send(payload,chunked=True))
            upstream.assert_called_once()
        self.assertEqual(20,self.proxy.ledger['calls'][0]['usage']['total_tokens'])

    def test_budget_exhausted_never_dispatches(self):
        self.proxy.ledger['prior_batch_reserved_rmb']=10
        with mock.patch.object(runtime.urllib.request,'urlopen') as upstream:
            self.assertEqual(429,self.send({'model':'deepseek-flash','messages':[],'max_tokens':128}))
            upstream.assert_not_called()
        self.assertEqual([],self.proxy.ledger['calls'])

    def test_explicit_call_ceiling_blocks_dispatch(self):
        self.proxy.external_call_ceiling=1
        self.proxy.ledger['calls']=[{'charged_upper_rmb':0,'kind':'chat'}]
        with mock.patch.object(runtime.urllib.request,'urlopen') as upstream:
            self.assertEqual(429,self.send({'model':'deepseek-flash','messages':[],'max_tokens':128}))
            upstream.assert_not_called()

    def test_call_ceiling_requires_bounded_integer(self):
        for value in (0,401,True,'400'):
            with self.assertRaises(ValueError):runtime.BudgetProxy(external_call_ceiling=value)

    def test_repeated_embedding_uses_success_cache_without_new_charge(self):
        payload={'model':'text-embedding-v4','input':['synthetic question'],'dimensions':1024}
        with mock.patch.object(runtime.urllib.request,'urlopen',return_value=Response(
                b'{"usage":{"prompt_tokens":20,"total_tokens":20},"data":[]}')) as upstream:
            self.assertEqual(200,self.send(payload))
            self.assertEqual(200,self.send(payload))
            upstream.assert_called_once()
        self.assertEqual(1,len(self.proxy.ledger['calls']))
        self.assertEqual(1,self.proxy.ledger['embedding_cache_hits'])

    def test_unknown_charge_keeps_reservation(self):
        with mock.patch.object(runtime.urllib.request,'urlopen',side_effect=TimeoutError('fake timeout')):
            self.assertEqual(502,self.send({'model':'deepseek-flash','messages':[],'max_tokens':128}))
        row=self.proxy.ledger['calls'][0]
        self.assertEqual('UNKNOWN_CHARGE',row['state'])
        self.assertEqual(row['reserved_rmb'],row['charged_upper_rmb'])
        self.assertEqual('upstream_timeout',row['failure_category'])

    def test_upstream_timeout_precedes_application_read_limit_and_never_retries(self):
        def fail(request,**kwargs):
            self.assertLess(kwargs['timeout'],20)
            raise TimeoutError('synthetic slow upstream')
        with mock.patch.object(runtime.urllib.request,'urlopen',side_effect=fail) as upstream:
            self.assertEqual(502,self.send({'model':'deepseek-flash','messages':[],'max_tokens':128}))
            upstream.assert_called_once()
        self.assertEqual(1,len(self.proxy.ledger['calls']))

    def test_transport_error_is_separate_from_timeout(self):
        with mock.patch.object(runtime.urllib.request,'urlopen',side_effect=runtime.urllib.error.URLError('synthetic TLS EOF')):
            self.assertEqual(502,self.send({'model':'deepseek-flash','messages':[],'max_tokens':128}))
        self.assertEqual('upstream_transport',self.proxy.ledger['calls'][0]['failure_category'])

    def test_non_allowlisted_model_never_dispatches(self):
        with mock.patch.object(runtime.urllib.request,'urlopen') as upstream:
            self.assertEqual(400,self.send({'model':'other-model','messages':[]}))
            upstream.assert_not_called()

    def test_unauthenticated_local_call_never_dispatches(self):
        with mock.patch.object(runtime.urllib.request,'urlopen') as upstream:
            self.assertEqual(401,self.send({'model':'deepseek-flash','messages':[]},key=False))
            upstream.assert_not_called()

    def test_free_provider_omits_dimensions_and_records_zero_embedding_charge(self):
        self.proxy.embedding={'name':'siliconflow','model':'BAAI/bge-m3',
            'endpoint':'https://api.siliconflow.cn/v1/embeddings','price_per_million_usd':0,'send_dimensions':False}
        def fake_upstream(request, **kwargs):
            self.assertEqual('https://api.siliconflow.cn/v1/embeddings', request.full_url)
            self.assertNotIn('dimensions',json.loads(request.data))
            return Response(b'{"usage":{"prompt_tokens":20,"total_tokens":20},"data":[]}')
        with mock.patch.object(runtime.urllib.request,'urlopen',side_effect=fake_upstream):
            self.assertEqual(200,self.send({'model':'BAAI/bge-m3','input':['synthetic question'],'dimensions':1024}))
        self.assertEqual(0,self.proxy.ledger['calls'][0]['charged_upper_rmb'])

    def test_free_provider_rejects_paid_pro_model(self):
        self.proxy.embedding={'name':'siliconflow','model':'BAAI/bge-m3',
            'endpoint':'https://api.siliconflow.cn/v1/embeddings','price_per_million_usd':0,'send_dimensions':False}
        with mock.patch.object(runtime.urllib.request,'urlopen') as upstream:
            self.assertEqual(400,self.send({'model':'Pro/BAAI/bge-m3','input':['synthetic question'],'dimensions':1024}))
            upstream.assert_not_called()

    def test_local_embeddings_ignore_external_budget_and_never_send_vendor_key(self):
        self.proxy.embedding={'name':'ollama','model':'bge-m3:latest',
            'endpoint':'http://127.0.0.1:11434/v1/embeddings','price_per_million_usd':0,'send_dimensions':False}
        self.proxy.keys['embed']='ollama-local'
        self.proxy.ledger['prior_batch_reserved_rmb']=10
        def fake_upstream(request, **kwargs):
            self.assertEqual('http://127.0.0.1:11434/v1/embeddings',request.full_url)
            self.assertEqual('Bearer ollama-local',request.get_header('Authorization'))
            self.assertNotIn('dimensions',json.loads(request.data))
            return Response(b'{"data":[],"usage":{"prompt_tokens":20,"total_tokens":20}}')
        with mock.patch.object(runtime.urllib.request,'urlopen',side_effect=fake_upstream):
            self.assertEqual(200,self.send({'model':'bge-m3:latest','input':['synthetic question']}))
        self.assertEqual(0,self.proxy.ledger['calls'][0]['charged_upper_rmb'])
        self.assertEqual('LOCAL',self.proxy.ledger['calls'][0]['execution_scope'])

    def test_missing_chat_key_never_dispatches(self):
        self.proxy.keys['chat']=''
        with mock.patch.object(runtime.urllib.request,'urlopen') as upstream:
            self.assertEqual(503,self.send({'model':'deepseek-flash','messages':[]}))
            upstream.assert_not_called()

if __name__=='__main__': main()
