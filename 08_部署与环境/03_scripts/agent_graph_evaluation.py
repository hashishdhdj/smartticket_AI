"""Separate full-Agent experiment: frozen questions, bounded dynamic history, no forced tool choice."""
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import secrets
import urllib.request
import urllib.error
from local_quality_runtime import ROOT
from recall_generation import budget_snapshot, file_hash

spec = importlib.util.spec_from_file_location('graph_protocol', Path(__file__).with_name('verify-agent-experience-graph.py'))
protocol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protocol)


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def repair_instruction():
    source = (ROOT / '06_项目源代码/ticket-service/src/main/java/com/smartticket/assistance/HandoffDecision.java').read_text(encoding='utf-8')
    block = source.split('REPAIR_INSTRUCTION =', 1)[1].split(';', 1)[0]
    return ''.join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', block))


def validate_grant(grant, review, preflight, review_path, preflight_path, ledger_path):
    before = budget_snapshot(ledger_path)
    if (grant.get('approved') is not True or grant.get('source') != 'EXPLICIT_USER_APPROVAL'
            or grant.get('review_sha256') != file_hash(review_path)
            or grant.get('preflight_sha256') != file_hash(preflight_path)
            or review['preflight_sha256'] != file_hash(preflight_path)
            or preflight['status'] != 'PASS_ZERO_VENDOR_PREFLIGHT'
            or grant.get('maximum_new_calls') != 20 or grant.get('external_call_ceiling') != 463
            or grant.get('batch_rmb_cap') != .5 or grant.get('total_rmb_cap') != 10
            or before != review['budget_before'] or before['external_calls'] != 443
            or before['authorized_rmb'] != 10 or before['estimated_upper_rmb'] + .5 > 10
            or datetime.fromisoformat(grant['expires_at']) <= datetime.now(timezone.utc)):
        raise ValueError('Missing, expired, stale or out-of-scope full-Agent authorization')
    return before


class AgentDispatchGuard:
    """Admit only the payload currently inspected by the loopback trace gateway."""
    def __init__(self, start_records):
        self.start_records = start_records
        self.pending = None
        self.seen = set()

    def __call__(self, kind, payload, reserve, ledger):
        fingerprint = digest(payload)
        batch = ledger['calls'][self.start_records:]
        identity = self.pending
        if (kind != 'chat' or not isinstance(identity, tuple) or len(identity) != 2
                or identity[1] != fingerprint or identity in self.seen or len(batch) >= 20
                or sum(c['charged_upper_rmb'] for c in batch) + reserve > .5):
            return False
        self.seen.add(identity)
        self.pending = None
        return True


class HistoryEnvelope:
    def __init__(self, prefixes, tools):
        self.prefixes = prefixes
        self.tools = tools
        self.repair = repair_instruction()
        self.owned_sources = set()
        self.static_files = {p.relative_to(ROOT / '06_项目源代码/ticket-service/src/main/resources/knowledge').as_posix()
                             for p in (ROOT / '06_项目源代码/ticket-service/src/main/resources/knowledge').rglob('*.md')}
        self.seen = set()
        self.counts = {}

    def validate(self, case, payload):
        messages = payload.get('messages', [])
        repair = len(messages) > 3 and messages[-1] == {'role': 'user', 'content': self.repair}
        if (case not in self.prefixes or messages[:3] != self.prefixes[case]
                or len(messages) > 24 or payload.get('model') != 'deepseek-flash'
                or payload.get('max_tokens') != 512 or payload.get('temperature') != .1
                or payload.get('response_format') != {'type': 'json_object'}
                or payload.get('stream') is True or payload.get('tool_choice') not in (None, 'auto')
                or payload.get('parallel_tool_calls') is True
                or payload.get('tools', []) != ([] if repair else self.tools)):
            raise ValueError('Native Agent request envelope changed')
        for message in messages[3:]:
            role = message.get('role')
            if role == 'user':
                if message != {'role': 'user', 'content': self.repair}:
                    raise ValueError('Unreviewed user turn')
            elif role == 'tool':
                body = json.loads(message['content'])
                if not isinstance(body, dict):
                    raise ValueError('Unexpected tool body')
                if not set(body).intersection({'sources', 'error'}):
                    raise ValueError('Only search evidence/errors may leave this evaluation')
                for source in body.get('sources', []):
                    sid = source.get('sourceId', '')
                    file, _, index = sid.rpartition('#')
                    if sid not in self.owned_sources and not (file in self.static_files and index.isdigit()):
                        raise ValueError('Unreviewed source')
            elif role != 'assistant':
                raise ValueError('Unexpected role in model history')
        identity = (case, digest(payload))
        if identity in self.seen or self.counts.get(case, 0) >= 4:
            raise ValueError('Duplicate dispatch or case limit')
        self.seen.add(identity)
        self.counts[case] = self.counts.get(case, 0) + 1


class AgentTraceGateway:
    """Records native request/reply pairs. Offline mode is explicitly protocol-only."""
    def __init__(self, envelope=None, proxy=None, guard=None):
        self.key = secrets.token_urlsafe(32)
        self.envelope, self.proxy, self.guard = envelope, proxy, guard
        self.case = None
        self.preferred_tool = 'searchExperienceGraph'
        self.rows = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass

            def do_POST(self):
                if self.path != '/chat/v1/chat/completions' or self.headers.get('Authorization') != 'Bearer ' + owner.key:
                    self.send_error(403); return
                payload = json.loads(protocol.read_body(self, maximum=100000))
                row = {'case': owner.case, 'request': payload}
                owner.rows.append(row)
                try:
                    if owner.proxy:
                        owner.envelope.validate(owner.case, payload)
                        forwarded = dict(payload, thinking={'type': 'disabled'})
                        owner.guard.pending = (owner.case, digest(forwarded))
                        req = urllib.request.Request(f'http://127.0.0.1:{owner.proxy.server.server_port}/chat/v1/chat/completions',
                            data=json.dumps(payload, ensure_ascii=False).encode(), headers={
                            'Authorization': 'Bearer ' + owner.proxy.gateway_key, 'Content-Type': 'application/json'})
                        with urllib.request.urlopen(req, timeout=18) as response:
                            decoded = json.load(response)
                        row['response'] = decoded
                        calls = decoded['choices'][0]['message'].get('tool_calls', [])
                        if any(c['function']['name'] not in ('searchKnowledge', 'searchExperienceGraph') for c in calls):
                            raise ValueError('Model chose a tool outside the reviewed read-only search scope')
                    else:
                        tools = [m for m in payload['messages'] if m['role'] == 'tool']
                        if not tools:
                            query = payload['messages'][2]['content']
                            message = {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'offline-' + secrets.token_hex(6),
                                'type': 'function', 'function': {'name': owner.preferred_tool,
                                    'arguments': json.dumps({'query': query}, ensure_ascii=False)}}]}
                        else:
                            rows = json.loads(tools[-1]['content']).get('sources', [])
                            message = {'role': 'assistant', 'content': json.dumps({
                                'answer': '离线协议替身；仅采集本机请求，未验证真实模型能力。',
                                'needHuman': not rows, 'sourceIds': [rows[0]['sourceId']] if rows else [],
                                'handoff': {'state': 'ANSWERED' if rows else 'INSUFFICIENT_EVIDENCE',
                                    'reason': 'NONE' if rows else 'CRITICAL_FACTS_MISSING',
                                    'detail': '' if rows else '缺少本题要求的可验证业务记录'}}, ensure_ascii=False)}
                        decoded = {'id': 'offline-preflight', 'object': 'chat.completion', 'model': 'offline-preflight-not-deepseek',
                            'choices': [{'index': 0, 'message': message, 'finish_reason': 'tool_calls' if not tools else 'stop'}],
                            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}}
                        row['response'] = decoded
                    body = json.dumps(decoded, ensure_ascii=False).encode()
                    self.send_response(200)
                except (ValueError, KeyError, OSError) as error:
                    row['failure_type'] = type(error).__name__
                    body = b'{"error":{"message":"Full-Agent evaluation envelope or upstream failed"}}'
                    self.send_response(502)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                try: self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError): pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.base = f'http://127.0.0.1:{self.server.server_port}/chat'
        self.thread = Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self): self.thread.start(); return self
    def __exit__(self, *args):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=3)


def observations(run, trace, expected_tool, withdrawn_source=None):
    selected = [c['function']['name'] for t in trace for c in t.get('response', {}).get('choices', [{}])[0].get('message', {}).get('tool_calls', [])]
    executed = [s['tool'] for s in run['steps'] if s['outcome'] == 'SUCCESS']
    tool_messages = [m for t in trace for m in t['request']['messages'] if m['role'] == 'tool']
    source_ids = {s['sourceId'] for m in tool_messages for s in json.loads(m['content']).get('sources', [])}
    graph_sources = {s['sourceId'] for m in tool_messages for s in json.loads(m['content']).get('sources', []) if 'facts' in s}
    issued_ids = {c['id'] for t in trace for c in t.get('response', {}).get('choices', [{}])[0].get('message', {}).get('tool_calls', [])}
    result_ids_match = bool(tool_messages) and all(m.get('tool_call_id') in issued_ids for m in tool_messages)
    replies = [t['response']['choices'][0]['message'] for t in trace if t.get('response')]
    final = None
    if replies and not replies[-1].get('tool_calls'):
        try: final = json.loads(replies[-1].get('content', ''))
        except (ValueError, TypeError): pass
    citations = final.get('sourceIds', []) if isinstance(final, dict) else []
    expected_selected = expected_tool in selected if expected_tool else None
    valid_citations = isinstance(citations, list) and all(isinstance(s,str) and s in source_ids for s in citations) if final is not None else None
    graph_complete = ('searchExperienceGraph' in selected and 'searchExperienceGraph' in executed and result_ids_match
        and bool(graph_sources) and valid_citations and bool(set(citations) & graph_sources)
        and run['status'] == 'SUCCEEDED' and not run['needHuman'])
    return {'model_selected_tools': selected, 'executed_tools': executed, 'expected_tool_selected': expected_selected,
        'model_received_tool_results': bool(tool_messages), 'available_source_ids': sorted(source_ids),
        'tool_result_ids_match_model_calls': result_ids_match,
        'graph_source_ids': sorted(graph_sources), 'final_model_json': final,
        'citations_in_delivered_context': valid_citations,
        'full_graph_agent_completed': graph_complete,
        'withdrawn_source_absent': (withdrawn_source not in source_ids and withdrawn_source not in citations)
            if withdrawn_source and any(t in executed for t in ('searchKnowledge','searchExperienceGraph')) else None,
        'semantic_grade': None, 'not_independent_blind_test': True}


def validate_withdrawal_continuation(grant, review, original_preflight, parent, review_path, preflight_path, ledger_path):
    """One failed-harness-case continuation, within the same 20-call/0.5 RMB authorization."""
    current = budget_snapshot(ledger_path)
    original = review['budget_before']
    if (grant.get('approved') is not True or grant.get('source') != 'EXPLICIT_USER_APPROVAL'
            or grant.get('review_sha256') != file_hash(review_path)
            or grant.get('preflight_sha256') != file_hash(preflight_path)
            or review['preflight_sha256'] != file_hash(preflight_path)
            or original_preflight['status'] != 'PASS_ZERO_VENDOR_PREFLIGHT'
            or grant.get('maximum_new_calls') != 20 or grant.get('external_call_ceiling') != 463
            or grant.get('batch_rmb_cap') != .5 or grant.get('total_rmb_cap') != 10
            or datetime.fromisoformat(grant['expires_at']) <= datetime.now(timezone.utc)
            or parent.get('mode') != 'real' or parent.get('budget_before') != original
            or current != parent.get('budget_after') or not parent.get('own_records_cleaned')
            or current['external_calls'] - original['external_calls'] != parent.get('vendor_calls')
            or not 0 < parent['vendor_calls'] < 20 or current['estimated_upper_rmb'] - original['estimated_upper_rmb'] >= .5
            or current['authorized_rmb'] != 10 or original['external_calls'] != 443):
        raise ValueError('Continuation must stay inside the original explicit batch and current ledger')
    case = next((r for r in parent['runs'] if r['id'] == 'AG05'), None)
    if (not case or case['run']['toolCalls'] != 0 or case['checks']['model_selected_tools']
            or not any(t.get('failure_type') for t in case['trace'])):
        raise ValueError('Only the original harness-blocked withdrawal case can continue')
    return original, current
