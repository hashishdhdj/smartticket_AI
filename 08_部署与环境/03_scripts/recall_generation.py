"""Frozen recall-first generation batch, local capture preflight and explicit grant guards."""
from contextlib import AbstractContextManager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import hashlib
import json
import os
import re
import secrets
import threading

from local_quality_runtime import ROOT, TEMP
from quality_checks import verify_artifact, verify_dataset
from quality_review import LEDGER, read, resolve_version, validate

PLAN = ROOT / 'docs/evaluation/recall-generation-v1.json'


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def budget_snapshot(path):
    path = Path(path)
    ledger = read(path)  # Mandatory existing canonical ledger; no new ledger fallback.
    return {'path': str(path.resolve()), 'sha256': file_hash(path), 'records': len(ledger['calls']),
            'external_calls': sum(c.get('provider') != 'ollama' for c in ledger['calls']),
            'estimated_upper_rmb': ledger['prior_batch_reserved_rmb'] + sum(c['charged_upper_rmb'] for c in ledger['calls']),
            'authorized_rmb': ledger['authorized_rmb']}


def implementation_hashes():
    return {name: file_hash(Path(__file__).with_name(name)) for name in
            ('recall_generation.py', 'verify-rag-generation.py', 'phase4_eval_runtime.py', 'local_quality_runtime.py')}


def load_batch():
    plan = read(PLAN)
    if plan['maximum_new_calls'] != 8 or plan['threshold'] != .55 or plan['label_review'] != 'PENDING':
        raise ValueError('Unexpected batch contract')
    ledger = validate(read(LEDGER))
    _, static, dynamic = verify_dataset()
    cases = []
    for item in plan['cases']:
        name = item['dataset']
        if name == 'phase4-v3-newwording':
            row = next(r for r in ledger['rows'] if r['dataset'] == name and r['case_id'] == item['id'])
            case = resolve_version(row['versions'][row['target_version']], item['id'])['case']
        elif name == 'quality-r06-v1':
            case = next(c for c in static if c['id'] == item['id'])
        elif name == 'quality-r06-v1-dynamic':
            positive = item['id'] == 'QDP1'
            case = {'id': item['id'], 'query': dynamic['positive_queries'][0] if positive else dynamic['negative_queries'][1],
                    'should_abstain': not positive, 'expected_sources': [],
                    'reference_answer': dynamic['answer'] if positive else '资料没有真实客户赔偿金额，不能确认。'}
        else:
            raise ValueError('Unknown frozen dataset')
        if digest(case) != item['case_sha256']:
            raise ValueError('Frozen case changed: ' + item['id'])
        cases.append({**case, 'dataset': name})
    if len(cases) != 8 or len({c['id'] for c in cases}) != 8:
        raise ValueError('Expected eight unique cases')
    return plan, cases, dynamic


def resolve_chat_key(private_config):
    value = os.environ.get('AI_API_KEY', '')
    if not value:
        values = re.findall(r'^      api-key:\s*(.+)$', Path(private_config).read_text(encoding='utf-8-sig'), re.M)
        value = values[0].strip().strip('\"\'') if values else ''
    if not value or value == 'not-configured' or value.startswith('${'):
        raise ValueError('Chat credential unavailable; no external request')
    return value


class CaptureGateway(AbstractContextManager):
    """Loopback model substitute: save only the exact demo payloads to be reviewed."""
    def __init__(self, response_payload=None):
        self.key = secrets.token_urlsafe(32)
        self.requests = []
        self.response_payload = response_payload or {'suggestion': '资料不足，请由人工核实具体条件。',
                                                    'similarQuestions': ['如何核实资料'], 'needHuman': True}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass

            def do_POST(self):
                if self.headers.get('Authorization') != 'Bearer ' + owner.key or self.path != '/chat/v1/chat/completions':
                    self.send_error(403); return
                if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                    parts, total = [], 0
                    while True:
                        size = int(self.rfile.readline(8192).split(b';', 1)[0].strip(), 16)
                        if size == 0:
                            while self.rfile.readline(8192) not in (b'\r\n', b'\n', b''): pass
                            break
                        total += size
                        if size < 0 or total > 100000:
                            self.send_error(413); return
                        part = self.rfile.read(size)
                        if len(part) != size or self.rfile.read(2) != b'\r\n':
                            self.send_error(400); return
                        parts.append(part)
                    raw = b''.join(parts)
                else:
                    size = int(self.headers.get('Content-Length', 0))
                    if not 0 < size <= 100000:
                        self.send_error(413); return
                    raw = self.rfile.read(size)
                payload = json.loads(raw)
                owner.requests.append(payload)
                content = json.dumps(owner.response_payload, ensure_ascii=False)
                body = json.dumps({'id': 'local-capture', 'object': 'chat.completion', 'created': 0,
                                   'model': 'local-substitute-not-deepseek',
                                   'choices': [{'index': 0, 'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': content}}],
                                   'usage': {'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2}}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.base = f'http://127.0.0.1:{self.server.server_port}/chat'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=3)


def publish_fixture(app, fixture):
    ticket = app.api('/api/tickets', {'title': fixture['question'], 'description': '合成演示巡检；真实生成复验固定资料',
                                    'creatorId': app.actors['SUBMITTER'][0]}, 'SUBMITTER')['id']
    app.api(f'/api/tickets/{ticket}/assign', {'assigneeId': app.actors['HANDLER'][0], 'operatorId': app.actors['ADMIN'][0]})
    app.api(f'/api/tickets/{ticket}/resolve', {'operatorId': app.actors['HANDLER'][0], 'solution': fixture['answer']}, 'HANDLER')
    state = app.wait_for(lambda: app.api(f'/api/tickets/{ticket}/knowledge', role='SUBMITTER'),
                         lambda rows: rows and rows[0].get('state') == 'DRAFT', 'Draft fixture')
    endpoint = '/api/knowledge/candidates/' + state[0]['resolutionId']
    app.api(endpoint + '/approve', {'expectedRevision': 0, 'question': fixture['question'], 'answer': fixture['answer'],
                                  'scope': fixture['scope'], 'redactionConfirmed': True})
    app.wait_for(lambda: app.api(endpoint), lambda detail: detail['candidate'].get('indexedRevision') == 1, 'Local fixture indexing')
    return endpoint


def validate_grant(grant, preflight, preflight_path, ledger_path):
    snapshot = budget_snapshot(ledger_path)
    if (grant.get('approved') is not True or grant.get('source') != 'EXPLICIT_USER_APPROVAL'
            or grant.get('preflight_sha256') != file_hash(preflight_path)
            or grant.get('maximum_new_calls') != 8 or grant.get('external_call_ceiling') != 408
            or grant.get('batch_rmb_cap') != .5 or grant.get('total_rmb_cap') != 10
            or snapshot != preflight['budget_before'] or snapshot['external_calls'] != 400
            or snapshot['authorized_rmb'] != 10 or snapshot['estimated_upper_rmb'] + .5 > 10
            or grant.get('ledger_sha256_before') != snapshot['sha256']):
        raise ValueError('Missing, stale, or out-of-scope explicit authorization')
    if datetime.fromisoformat(grant['expires_at']) <= datetime.now(timezone.utc):
        raise ValueError('Authorization expired')
    return snapshot


class BatchDispatchGuard:
    """Called under the proxy lock. Each preflight message can be sent at most once."""
    def __init__(self, preflight):
        self.allowed = {row['messages_sha256'] for row in preflight['rows'] if row.get('messages_sha256')}
        self.seen = set()
        self.start_records = preflight['budget_before']['records']

    def __call__(self, kind, payload, reserve, ledger):
        fingerprint = digest(payload.get('messages'))
        batch = ledger['calls'][self.start_records:]
        if (kind != 'chat' or fingerprint not in self.allowed or fingerprint in self.seen
                or len(batch) >= 8 or sum(c['charged_upper_rmb'] for c in batch) + reserve > .5
                or payload.get('tools') or payload.get('stream') is True):
            return False
        self.seen.add(fingerprint)
        return True
