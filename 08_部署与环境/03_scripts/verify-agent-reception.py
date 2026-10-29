"""Real JAR/HTTP/scheduler/MySQL reception smoke; deterministic loopback chat, zero vendor calls."""
from pathlib import Path
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import json
import threading
import uuid

from local_quality_runtime import LocalQualityRuntime, ROOT
from quality_checks import verify_artifact


class Fixture(BaseHTTPRequestHandler):
    calls = 0
    def log_message(self, *_):
        pass

    def do_POST(self):
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            chunks = []
            while True:
                size = int(self.rfile.readline().strip().split(b';', 1)[0], 16)
                if size == 0:
                    while self.rfile.readline() not in (b'\r\n', b'\n', b''):
                        pass
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)
            body = b''.join(chunks)
        else:
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        payload = json.loads(body)
        Fixture.calls += 1
        messages = payload['messages']
        user = next(m['content'] for m in messages if m['role'] == 'user')
        import re
        ticket_id = int(re.search(r'工单ID=(\d+)', user).group(1))
        tool_names = {t['function']['name'] for t in payload['tools']}
        if not tool_names <= {'getTicketDetail', 'searchKnowledge', 'searchExperienceGraph'}:
            self.send_error(400)
            return
        if not any(m['role'] == 'tool' for m in messages):
            message = {'role': 'assistant', 'content': '', 'tool_calls': [{
                'id': 'call_' + uuid.uuid4().hex, 'type': 'function', 'function': {
                    'name': 'getTicketDetail', 'arguments': json.dumps({'ticketId': ticket_id})}}]}
        else:
            if 'RECEPTION_ASK' in user:
                state, reason, detail = 'NEEDS_CLARIFICATION', 'USER_CONTEXT_REQUIRED', '请补充原手机号是否可用'
                answer = '原手机号是否还能接收短信？'
            elif 'RECEPTION_HUMAN' in user:
                state, reason, detail = 'HUMAN_ACTION_REQUIRED', 'HUMAN_OPERATION_REQUIRED', '用户要求实际退款，系统没有退款执行工具'
                answer = '实际退款操作需要人工办理。'
            else:
                state, reason, detail = 'ANSWERED', 'NONE', ''
                answer = '查询结果：当前工单处理中，尚未指派人工处理人。请确认这是否解答了本次进度查询。'
            message = {'role': 'assistant', 'content': json.dumps({
                'answer': answer, 'needHuman': state == 'HUMAN_ACTION_REQUIRED', 'sourceIds': [],
                'handoff': {'state': state, 'reason': reason, 'detail': detail}}, ensure_ascii=False)}
        result = {'id': 'reception-fixture', 'object': 'chat.completion', 'created': 1793200000,
                  'model': 'offline-reception-protocol-fixture', 'choices': [{
                      'index': 0, 'message': message, 'finish_reason': 'tool_calls' if 'tool_calls' in message else 'stop'}],
                  'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}}
        data = json.dumps(result, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--jar', type=Path, default=ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar')
    args = parser.parse_args()
    artifact = verify_artifact(args.jar)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    checks = []
    try:
        with LocalQualityRuntime(args.java, args.jar, port=18089, staff_experience=True,
                chat_gateway=(f'http://127.0.0.1:{server.server_port}', 'offline-reception-fixture'),
                agent_reception=True) as runtime:
            def create(title):
                ticket = runtime.api('/api/tickets', {'title': title, 'description': '合成接待验收资料',
                    'creatorId': runtime.actors['SUBMITTER'][0]}, role='SUBMITTER')
                assert ticket['status'] == 'PROCESSING' and ticket.get('assigneeId') is None
                return ticket['id']
            def wait(ticket, state):
                return runtime.wait_for(lambda: runtime.api(f'/api/tickets/{ticket}/reception', role='SUBMITTER'),
                    lambda r: r['receptionState'] == state, state, seconds=40)
            def respond(ticket, view, action, text=''):
                return runtime.api(f'/api/tickets/{ticket}/reception/responses', {
                    'requestId': uuid.uuid4().hex, 'expectedTurn': view['turn'], 'action': action, 'message': text}, role='SUBMITTER')
            ticket = create('RECEPTION_STATUS 查询本工单当前处理状态')
            view = wait(ticket, 'WAITING_CUSTOMER')
            assert view['ticketStatus'] == 'PENDING_FEEDBACK' and 'lastRunId' not in view
            done = respond(ticket, view, 'RESOLVED')
            assert done['ticketStatus'] == 'RESOLVED'
            checks.append('HTTP create -> durable queue -> scheduler -> real Agent/tool protocol -> customer confirmation')
            ticket = create('RECEPTION_ASK 原手机号是否可用尚不明确')
            view = wait(ticket, 'WAITING_INPUT')
            assert view['ticketStatus'] == 'PROCESSING'
            respond(ticket, view, 'MESSAGE', '原手机号可用，继续处理')
            view = wait(ticket, 'WAITING_INPUT')
            assert view['turn'] == 2
            checks.append('clarification -> persisted customer response -> next Agent turn; no administrator approval')
            ticket = create('RECEPTION_HUMAN 要求实际退款')
            view = wait(ticket, 'HUMAN_REQUIRED')
            assert view['ticketStatus'] == 'PENDING_ASSIGN'
            admin = runtime.api(f'/api/tickets/{ticket}/reception')
            assert admin['steps'] and admin['handoffSummary']
            runtime.api(f'/api/tickets/{ticket}/assign', {'assigneeId': runtime.actors['HANDLER'][0],
                'operatorId': runtime.actors['ADMIN'][0]})
            assert runtime.api(f'/api/tickets/{ticket}/reception')['receptionState'] == 'HUMAN_ACTIVE'
            runtime.api(f'/api/tickets/{ticket}/reception/responses', {
                'requestId': uuid.uuid4().hex, 'expectedTurn': view['turn'], 'action': 'MESSAGE', 'message': '不可恢复自动接待'},
                role='SUBMITTER', expected=409)
            checks.append('human handoff with trace/context -> real assignment -> automatic continuation rejected')
        report = {'date': datetime.now(timezone.utc).isoformat(), 'status': 'PASS', 'vendor_calls': 0,
                  'model': 'deterministic loopback protocol fixture, NOT DeepSeek quality evaluation',
                  'fixture_chat_requests': Fixture.calls, 'jar_sha256': artifact['jar_sha256'],
                  'checks': checks, 'own_records_cleaned': True}
        destination = ROOT / '.tmp/reception-http-smoke.json'
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    main()
