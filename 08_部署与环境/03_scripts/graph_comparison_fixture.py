"""Loopback capture-only dialogue and optional deterministic CI embeddings. No forwarding."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from pathlib import Path
import hashlib
import importlib.util
import json
import math
import uuid

spec = importlib.util.spec_from_file_location('g5_protocol_body', Path(__file__).with_name('verify-agent-experience-graph.py'))
protocol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protocol)


def vector(text):
    """CI protocol fixture only: deterministic normalized character-bigram feature hashing."""
    values = [0.0] * 1024
    for index in range(max(1, len(text) - 1)):
        gram = text[index:index + 2]
        digest = hashlib.sha256(gram.encode('utf-8')).digest()
        values[int.from_bytes(digest[:2], 'big') % 1024] += 1.0
    length = math.sqrt(sum(x * x for x in values))
    return [x / length for x in values]


class ComparisonFixture:
    def __init__(self, offline_embedding=False):
        self.tool = 'searchKnowledge'
        self.requests = []
        self.contexts = []
        self.embedding_texts = 0
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                request = json.loads(protocol.read_body(self))
                if self.path == '/v1/embeddings' and offline_embedding:
                    texts = request['input']
                    if isinstance(texts, str):
                        texts = [texts]
                    owner.embedding_texts += len(texts)
                    payload = {'object': 'list', 'model': request['model'], 'data': [
                        {'object': 'embedding', 'index': i, 'embedding': vector(text)} for i, text in enumerate(texts)],
                        'usage': {'prompt_tokens': 1, 'total_tokens': 1}}
                elif self.path == '/v1/chat/completions':
                    owner.requests.append(request)
                    messages = request['messages']
                    tools = [m for m in messages if m['role'] == 'tool']
                    if not tools:
                        query = next(m['content'] for m in reversed(messages) if m['role'] == 'user')
                        message = {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'g5-' + uuid.uuid4().hex,
                            'type': 'function', 'function': {'name': owner.tool,
                                'arguments': json.dumps({'query': query}, ensure_ascii=False)}}]}
                        reason = 'tool_calls'
                    else:
                        owner.contexts.append(json.loads(tools[-1]['content']))
                        message = {'role': 'assistant', 'content': json.dumps({'answer': '离线证据采集；未生成业务建议。',
                                   'needHuman': False, 'sourceIds': [],
                                   'handoff': {'state': 'NEEDS_CLARIFICATION', 'reason': 'USER_CONTEXT_REQUIRED',
                                               'detail': '仅采集证据，等待另行提供业务回答；本替身不生成建议'}}, ensure_ascii=False)}
                        reason = 'stop'
                    payload = {'id': 'g5-offline', 'object': 'chat.completion', 'created': 1793000000,
                        'model': 'offline-capture-only', 'choices': [{'index': 0, 'message': message, 'finish_reason': reason}],
                        'usage': {'prompt_tokens': 100, 'completion_tokens': 30, 'total_tokens': 130}}
                else:
                    self.send_error(404)
                    return
                body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.embedding_server = None
        self.embedding_thread = None
        if offline_embedding:
            try:
                # Keep the production Java loopback/port/model fence unchanged.
                # CI has no Ollama here; local users must first free this test port.
                self.embedding_server = ThreadingHTTPServer(('127.0.0.1', 11434), Handler)
                self.embedding_thread = Thread(target=self.embedding_server.serve_forever, daemon=True)
            except BaseException:
                self.server.server_close()
                raise

    @property
    def base(self):
        return 'http://127.0.0.1:' + str(self.server.server_port)

    def __enter__(self):
        self.thread.start()
        if self.embedding_thread:
            self.embedding_thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        if self.embedding_server:
            self.embedding_server.shutdown()
            self.embedding_server.server_close()
            self.embedding_thread.join(timeout=5)
