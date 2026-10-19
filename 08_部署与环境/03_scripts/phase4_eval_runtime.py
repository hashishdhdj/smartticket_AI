"""Isolated application + metered vendor proxy for repeatable stage4 evaluation.

Fixed vendor/model allowlist, no forwarding retries, persistent RMB10 envelope.
Prior batch reserves RMB1 conservatively because classification usage was absent.
Never writes or prints keys, prompts or responses to the budget ledger.
"""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from contextlib import AbstractContextManager
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
import re
import secrets
import subprocess
import threading
import time
import urllib.request
import urllib.error
from threshold_policy import active_threshold

ROOT = Path(__file__).resolve().parents[2]
TEMP = ROOT / '.tmp'
UPSTREAM_CHAT_TIMEOUT_SECONDS = 15  # Leave room before the application's 20s read timeout; no automatic retry.

def embedding_provider():
    path = TEMP / 'phase4-embedding-provider.properties'
    values = dict(line.split('=', 1) for line in path.read_text(encoding='utf-8-sig').splitlines()
                  if '=' in line and not line.lstrip().startswith('#')) if path.exists() else {}
    name = values.get('provider', 'alicloud').strip()
    if name == 'ollama':
        return {'name': name, 'model': 'bge-m3:latest', 'endpoint': 'http://127.0.0.1:11434/v1/embeddings',
                'price_per_million_usd': 0, 'send_dimensions': False}
    if name == 'siliconflow':
        return {'name': name, 'model': 'BAAI/bge-m3', 'endpoint': 'https://api.siliconflow.cn/v1/embeddings',
                'price_per_million_usd': 0, 'send_dimensions': False}
    if name == 'alicloud':
        return {'name': name, 'model': 'text-embedding-v4',
                'endpoint': 'https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings',
                'price_per_million_usd': .072, 'send_dimensions': True}
    raise RuntimeError('Embedding provider not allowlisted')

def credential(variable, index):
    if variable == 'EMBEDDING_API_KEY' and embedding_provider()['name'] == 'ollama':
        return 'ollama-local'  # Ignored by the local server; never send a vendor key.
    if variable == 'EMBEDDING_API_KEY' and embedding_provider()['name'] == 'siliconflow':
        value = os.environ.get('SILICONFLOW_API_KEY', '')
        if not value:
            path = TEMP / 'phase4-embedding-provider.properties'
            values = dict(line.split('=', 1) for line in path.read_text(encoding='utf-8-sig').splitlines()
                          if '=' in line and not line.lstrip().startswith('#'))
            value = values.get('siliconflow.api-key', '').strip()
        if not value or value == 'not-configured': raise RuntimeError('Configure SILICONFLOW_API_KEY or the ignored provider file; no request sent')
        return value
    value = os.environ.get(variable, '')
    if not value:
        text = (ROOT / '06_项目源代码/ticket-service/src/main/resources/application-local.yml').read_text(encoding='utf-8-sig')
        found = re.findall(r'^      api-key:\s*(.+)$', text, re.M)
        if len(found) > index: value = found[index].strip().strip('"\'')
    if not value or value == 'not-configured' or value.startswith('${'):
        raise RuntimeError(f'{variable} unavailable; no vendor dispatch')
    return value

class BudgetProxy:
    def __init__(self, port=19014, report_path=None, external_call_ceiling=320, capture_agent_outputs=False):
        if type(external_call_ceiling) is not int or not 1 <= external_call_ceiling <= 400:
            raise ValueError('External call ceiling must be an explicit integer within1..400')
        self.external_call_ceiling = external_call_ceiling
        self.capture_agent_outputs = capture_agent_outputs
        self.gateway_key = secrets.token_urlsafe(32)
        run_date = datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d')
        self.report_path = report_path or ROOT / f'07_测试与质量/06_阶段测试报告/{run_date}_阶段四调用预算汇总.json'
        self.embedding = embedding_provider()
        try:
            chat_key = credential('AI_API_KEY', 0)
        except (RuntimeError, FileNotFoundError):
            if self.embedding['name'] != 'ollama': raise
            chat_key = ''  # Local-only indexing/calibration needs no chat credential.
        self.keys = {'chat': chat_key, 'embed': credential('EMBEDDING_API_KEY', 1)}
        self.path = TEMP / 'phase4-budget-ledger.json'
        self.lock = threading.Lock()
        self.cache_dir = TEMP / 'phase4-embedding-cache'
        self.cache_dir.mkdir(exist_ok=True)
        self.ledger = json.loads(self.path.read_text()) if self.path.exists() else {
            'authorized_rmb': 10, 'prior_batch_reserved_rmb': 1, 'fx_budget_factor': 10,
            'prices_checked_date': '2026-10-14', 'calls': []}
        self.save()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass

            def reply(self, status, body):
                try:
                    self.send_response(status)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
                    pass  # Accounting remains recorded after a client disconnect.

            def do_POST(self):
                if self.headers.get('Authorization') != 'Bearer ' + owner.gateway_key:
                    self.send_error(401); return
                kind = 'chat' if self.path == '/chat/v1/chat/completions' else 'embed' if self.path == '/embed/v1/embeddings' else None
                if kind is None:
                    self.send_error(404); return
                if kind == 'chat' and not owner.keys['chat']:
                    self.send_error(503, 'Chat credential unavailable; no external dispatch'); return
                self.connection.settimeout(25)
                if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                    parts, total = [], 0
                    try:
                        while True:
                            line = self.rfile.readline(8192)
                            size = int(line.split(b';', 1)[0].strip(), 16)
                            if size == 0:
                                # Consume trailers through the terminating CRLF.
                                while self.rfile.readline(8192) not in (b'\r\n', b'\n', b''): pass
                                break
                            total += size
                            if size < 0 or total > 100_000: raise ValueError('Body ceiling')
                            part = self.rfile.read(size)
                            if len(part) != size or self.rfile.read(2) != b'\r\n': raise ValueError('Invalid chunk')
                            parts.append(part)
                        raw = b''.join(parts)
                    except (OSError, ValueError):
                        self.send_error(413); return
                else:
                    size = int(self.headers.get('Content-Length', '0'))
                    if not 0 < size <= 100_000:
                        self.send_error(413); return
                    raw = self.rfile.read(size)
                if not raw:
                    self.send_error(400); return
                try: payload = json.loads(raw)
                except ValueError:
                    self.send_error(400); return
                model = 'deepseek-flash' if kind == 'chat' else owner.embedding['model']
                if payload.get('model') != model:
                    self.send_error(400, 'Model not allowlisted'); return
                if kind == 'chat':
                    maximum = payload.get('max_tokens', payload.get('max_completion_tokens', 512))
                    if not isinstance(maximum, int) or not 1 <= maximum <= 512:
                        self.send_error(400, 'Output envelope exceeded'); return
                    payload['thinking'] = {'type': 'disabled'}
                    reserve = ((len(raw) + 2048) * .3 + maximum * 1.2) / 1_000_000 * 10
                else:
                    maximum = 0
                    inputs = payload.get('input', [])
                    if not isinstance(inputs, list) or not 1 <= len(inputs) <= 10 or payload.get('dimensions', 1024) != 1024:
                        self.send_error(400, 'Embedding shape exceeded'); return
                    reserve = (len(raw) + 2048) * owner.embedding['price_per_million_usd'] / 1_000_000 * 10
                    if not owner.embedding['send_dimensions']: payload.pop('dimensions', None)
                cache = None
                if kind == 'embed':
                    digest = hashlib.sha256((owner.embedding['name'] + json.dumps(payload, sort_keys=True, ensure_ascii=False)).encode()).hexdigest()
                    cache = owner.cache_dir / (digest + '.json')
                    if cache.exists():
                        body = cache.read_bytes()
                        with owner.lock:
                            owner.ledger['embedding_cache_hits'] = owner.ledger.get('embedding_cache_hits', 0) + 1
                            owner.save()
                        self.reply(200, body); return
                with owner.lock:
                    spent = owner.ledger['prior_batch_reserved_rmb'] + sum(c['charged_upper_rmb'] for c in owner.ledger['calls'])
                    is_local = kind == 'embed' and owner.embedding['name'] == 'ollama'
                    external_calls = sum(c.get('provider') != 'ollama' for c in owner.ledger['calls'])
                    if not is_local and (spent + reserve > owner.ledger['authorized_rmb'] or external_calls >= owner.external_call_ceiling):
                        self.send_error(429, 'Evaluation budget/call ceiling exhausted'); return
                    record = {'kind': kind, 'model': model, 'reserved_rmb': reserve, 'charged_upper_rmb': reserve,
                              'dispatched_at': datetime.now(timezone(timedelta(hours=8))).isoformat(), 'state': 'DISPATCHED'}
                    owner.ledger['calls'].append(record)
                    if kind == 'embed': record['provider'] = owner.embedding['name']
                    record['execution_scope'] = 'LOCAL' if is_local else 'EXTERNAL'
                    owner.save()
                target = 'https://api.deepseek.com/v1/chat/completions' if kind == 'chat' else owner.embedding['endpoint']
                req = urllib.request.Request(target, data=json.dumps(payload).encode(),
                    headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + owner.keys[kind]})
                try:
                    if kind == 'embed': time.sleep(1)
                    with urllib.request.urlopen(req, timeout=120 if is_local else UPSTREAM_CHAT_TIMEOUT_SECONDS) as response:
                        status, body = response.status, response.read()
                    decoded = json.loads(body)
                    if kind == 'chat':
                        choice = (decoded.get('choices') or [{}])[0]
                        record['finish_reason'] = choice.get('finish_reason')
                        record['tool_calls'] = choice.get('message', {}).get('tool_calls', [])
                        if owner.capture_agent_outputs and payload.get('tools') and not record['tool_calls']:
                            # Explicit synthetic-evaluation debugging only; never store prompts or keys.
                            directory = TEMP/'agent-output-diagnostics'
                            directory.mkdir(exist_ok=True)
                            diagnostic = directory/f'final-{len(owner.ledger["calls"])}.json'
                            if not diagnostic.exists():
                                diagnostic.write_text(json.dumps({'content':choice.get('message',{}).get('content'),
                                    'finish_reason':choice.get('finish_reason')},ensure_ascii=False),encoding='utf-8')
                    usage = decoded.get('usage', {})
                    if isinstance(usage.get('prompt_tokens'), int) and isinstance(usage.get('total_tokens'), int):
                        cost = (usage['prompt_tokens'] * (.3 if kind == 'chat' else owner.embedding['price_per_million_usd'])
                                + usage.get('completion_tokens', 0) * (1.2 if kind == 'chat' else 0)) / 1_000_000 * 10
                        record.update(usage=usage, charged_upper_rmb=max(cost, 0))
                    record.update(state='RESPONDED', http_status=status)
                    if cache is not None:
                        pending = cache.with_suffix('.pending')
                        pending.write_bytes(body); pending.replace(cache)
                except urllib.error.HTTPError as failure:
                    try:
                        rejected = json.loads(failure.read())
                        error = rejected.get('error', {})
                        detail = str(error.get('message', rejected.get('message', '')))[:300]
                        for secret in owner.keys.values():
                            if secret: detail = detail.replace(secret, '[REDACTED]')
                        record['vendor_error_code'] = error.get('code', rejected.get('code'))
                        record['vendor_error_detail'] = detail
                    except (ValueError, OSError): pass
                    status, body = failure.code, json.dumps({'error': {'message': 'Upstream rejected request', 'type': 'vendor_error'}}).encode()
                    record.update(state='VENDOR_ERROR', http_status=status)
                except (OSError, ValueError, KeyError) as failure:
                    cause = getattr(failure, 'reason', failure)
                    category = ('upstream_timeout' if isinstance(cause, TimeoutError) or 'timed out' in str(cause).lower()
                                else 'upstream_transport' if isinstance(failure, OSError) else 'upstream_invalid_response')
                    status, body = 502, json.dumps({'error':{'message':'Upstream request failed','type':category}}).encode()
                    record.update(state='UNKNOWN_CHARGE', http_status=status, error_type=type(failure).__name__)
                    record['failure_category'] = category
                    detail = str(getattr(failure, 'reason', failure))[:160]
                    for secret in owner.keys.values():
                        if secret: detail = detail.replace(secret, '[REDACTED]')
                    record['error_detail'] = detail
                with owner.lock: owner.save()
                self.reply(status, body)

        self.server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def save(self):
        temporary = self.path.with_suffix('.pending')
        temporary.write_text(json.dumps(self.ledger, indent=2) + '\n', encoding='utf-8')
        temporary.replace(self.path)

    def __enter__(self): self.thread.start(); return self

    def __exit__(self, *args):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=3)
        output = self.report_path
        summary = dict(self.ledger)
        summary['estimated_total_upper_rmb'] = summary['prior_batch_reserved_rmb'] + sum(c['charged_upper_rmb'] for c in summary['calls'])
        summary['billing_note'] = 'Conservative envelope using peak/no-cache prices and budgeting factor10; not the actual CNY vendor bill.'
        output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

class EvalRuntime(AbstractContextManager):
    def __init__(self, actors, ai=False, index=False, threshold=None, gateway_key=None, jar=None):
        threshold = active_threshold() if threshold is None else threshold
        self.actors = actors
        self.auth = {}
        self.identities = {}
        self.embedding = embedding_provider()
        self.jar = Path(jar) if jar is not None else ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
        self.base = 'http://127.0.0.1:18083'
        config = (TEMP / 'phase4-integration.properties').read_text(encoding='utf-8')
        config += '\n' + '\n'.join([
            'spring.ai.openai.api-key=' + (gateway_key or 'unconfigured-proxy'), 'spring.ai.openai.base-url=http://127.0.0.1:19014/chat',
            'spring.ai.openai.chat.options.model=deepseek-flash',
            'smartticket.vector.embedding.api-key=' + (gateway_key or 'unconfigured-proxy'),
            'smartticket.vector.embedding.base-url=http://127.0.0.1:19014/embed',
            'smartticket.vector.embedding.model=' + self.embedding['model'],
            'smartticket.vector.embedding.send-dimensions=' + str(self.embedding['send_dimensions']).lower(),
            'smartticket.vector.embedding.batch-size=' + ('2' if self.embedding['name'] == 'ollama' else '10'),
            'smartticket.ai.enabled=' + str(ai).lower(),
            'smartticket.vector.index-on-startup=' + str(index).lower(),
            'smartticket.vector.confidence-threshold=' + str(threshold),
            'smartticket.phase3.record-tasks=false', 'smartticket.phase3.async-enabled=false',
        ]) + '\n'
        first_id = 2_000_000_000 + secrets.randbelow(100_000_000)
        for i, (name, role) in enumerate(actors.items()):
            self.auth[name] = secrets.token_urlsafe(24)
            self.identities[name] = first_id + i
            config += ''.join(f'smartticket.phase3.users[{i}].{field}={value}\n' for field, value in
                             [('id', first_id + i), ('role', role), ('token', self.auth[name])])
        self.config = TEMP / 'phase4-eval.properties'
        self.config.write_text(config, encoding='utf-8')
        self.process = None
        self.log = None

    def __enter__(self):
        java = Path(os.environ['JAVA_HOME']) / ('bin/java.exe' if os.name == 'nt' else 'bin/java')
        self.log = (TEMP / 'phase4-eval-java.log').open('ab')
        self.process = subprocess.Popen([str(java), f'-Djava.io.tmpdir={TEMP}', f'-Djdk.net.unixdomain.tmpdir={TEMP}', '-jar',
            str(self.jar),
            '--server.port=18083', '--spring.profiles.active=stage4', f'--spring.config.additional-location={self.config.as_uri()}'],
            cwd=TEMP, stdout=self.log, stderr=self.log)
        try:
            deadline = time.monotonic() + 100
            while time.monotonic() < deadline:
                if self.process.poll() is not None: raise RuntimeError('Eval application exited')
                try:
                    self.api('/api/tickets', actor=next(iter(self.auth))); return self
                except (OSError, RuntimeError): time.sleep(1)
            raise TimeoutError('Eval readiness deadline')
        except Exception:
            self.__exit__(None, None, None); raise

    def api(self, path, body=None, actor=None):
        actor = actor or next(iter(self.auth))
        req = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json', 'X-API-Key': self.auth[actor]})
        with urllib.request.urlopen(req, timeout=75) as response: result = json.loads(response.read())
        if result.get('code') != 0: raise RuntimeError('Business error ' + str(result.get('code')))
        return result['data']

    def __exit__(self, *args):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.wait(timeout=10)
        if self.log: self.log.close()
