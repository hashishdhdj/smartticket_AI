"""Check the zero-vendor smoke's loopback model fixture and runtime entry guards."""
import importlib.util
import http.client
import json
from pathlib import Path
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from local_quality_runtime import LocalQualityRuntime

spec = importlib.util.spec_from_file_location('reception_smoke', Path(__file__).with_name('verify-agent-reception.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class ReceptionSmokeTest(unittest.TestCase):
    def test_reception_mode_is_explicit_boolean(self):
        with self.assertRaises(ValueError):
            LocalQualityRuntime('java', 'jar', agent_reception='true')

    def test_reception_does_not_allow_external_chat_gateway(self):
        with self.assertRaises(ValueError):
            LocalQualityRuntime('java', 'jar', agent_reception=True, chat_gateway=('https://api.deepseek.com', 'unused'))

    def test_fixture_tool_call_and_clarification_follow_the_wire_contract(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), smoke.Fixture)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            messages = [{'role': 'user', 'content': '工单ID=123；RECEPTION_ASK'}]
            tools = [{'type': 'function', 'function': {'name': 'getTicketDetail'}}]
            def send(chunked=False):
                data = json.dumps({'messages': messages, 'tools': tools}).encode()
                if chunked:
                    connection=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
                    try:
                        connection.request('POST','/v1/chat/completions',body=iter([data]),encode_chunked=True)
                        return json.loads(connection.getresponse().read())['choices'][0]['message']
                    finally:
                        connection.close()
                request = urllib.request.Request(f'http://127.0.0.1:{server.server_port}/v1/chat/completions', data=data)
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=5) as response:
                    return json.load(response)['choices'][0]['message']
            call = send()['tool_calls'][0]
            self.assertEqual('getTicketDetail', call['function']['name'])
            self.assertEqual({'ticketId': 123}, json.loads(call['function']['arguments']))
            messages.append({'role': 'tool', 'tool_call_id': call['id'], 'content': '{"id":123}'})
            result = json.loads(send(chunked=True)['content'])
            self.assertEqual('NEEDS_CLARIFICATION', result['handoff']['state'])
            self.assertFalse(result['needHuman'])
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=5)

    def test_fixture_refuses_staff_tools(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), smoke.Fixture)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            data = json.dumps({'messages': [{'role': 'user', 'content': '工单ID=123；'}],
                'tools': [{'type': 'function', 'function': {'name': 'prepareAssignTicket'}}]}).encode()
            request = urllib.request.Request(f'http://127.0.0.1:{server.server_port}/v1/chat/completions', data=data)
            with self.assertRaises(urllib.error.HTTPError) as failure:
                urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=5)
            self.assertEqual(400, failure.exception.code)
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=5)
