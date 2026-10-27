"""G4: real HTTP Agent/Spring AI protocol + real graph + local BGE; OFFLINE dialogue fixture.

The fixture copies tool evidence deterministically. This checks execution/provenance,
not DeepSeek intelligence, semantic answer quality or a retrieval comparison experiment.
"""
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import argparse
import hashlib
import json
import os
import uuid
from local_quality_runtime import LocalQualityRuntime, ROOT, TEMP
from quality_checks import verify_artifact


def read_body(handler, maximum=262144):
    """Spring AI/JDK may stream chunked HTTP/1.1 rather than send Content-Length."""
    if handler.headers.get('Transfer-Encoding', '').lower() == 'chunked':
        result = bytearray()
        while True:
            line = handler.rfile.readline(1024)
            if not line.endswith(b'\r\n'):
                raise ValueError('Invalid fixture chunk header')
            length = int(line.split(b';', 1)[0].strip(), 16)
            if length < 0 or len(result) + length > maximum:
                raise ValueError('Fixture body limit exceeded')
            if length == 0:
                while True:
                    trailer = handler.rfile.readline(1024)
                    if trailer == b'\r\n':
                        return bytes(result)
                    if not trailer.endswith(b'\r\n'):
                        raise ValueError('Invalid fixture trailer')
            chunk = handler.rfile.read(length)
            if len(chunk) != length or handler.rfile.read(2) != b'\r\n':
                raise ValueError('Truncated fixture chunk')
            result.extend(chunk)
    length = int(handler.headers.get('Content-Length', '-1'))
    if not 0 <= length <= maximum:
        raise ValueError('Invalid fixture body length')
    result = handler.rfile.read(length)
    if len(result) != length:
        raise ValueError('Truncated fixture body')
    return result


class OfflineGraphFixture:
    def __init__(self):
        self.requests = []
        self.on_final = None
        self.bad_citation = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if self.path != '/v1/chat/completions':
                    self.send_error(404)
                    return
                request = json.loads(read_body(self))
                owner.requests.append(request)
                assert any(t['function']['name'] == 'searchExperienceGraph' for t in request['tools'])
                assert request['response_format']['type'] == 'json_object'
                messages = request['messages']
                tool_rows = [m for m in messages if m['role'] == 'tool']
                if not tool_rows:
                    query = next(m['content'] for m in reversed(messages) if m['role'] == 'user')
                    message = {'role': 'assistant', 'content': '', 'tool_calls': [{'id': 'fixture-' + uuid.uuid4().hex,
                        'type': 'function', 'function': {'name': 'searchExperienceGraph',
                        'arguments': json.dumps({'query': query}, ensure_ascii=False)}}]}
                    finish = 'tool_calls'
                else:
                    tool = json.loads(tool_rows[-1]['content'])
                    rows = tool.get('sources', [])
                    if owner.on_final:
                        callback, owner.on_final = owner.on_final, None
                        callback()
                    ids = [rows[0]['sourceId']] if rows else []
                    if owner.bad_citation:
                        ids = ['not-a-returned-source-node-id']
                    if rows:
                        facts = rows[0]['facts']
                        answer = ('离线模型替身演示：先核对适用条件：' + '；'.join(facts['conditions'])
                                  + '。处理步骤：' + '；'.join(facts['steps'])
                                  + '。禁止条件：' + '；'.join(facts['contraindications'])
                                  + '。此处为模拟规则，非本项目已实现短信能力，效果待核实。')
                    else:
                        answer = '未找到可验证的已审核图谱经验，请补充资料或转人工。'
                    message = {'role': 'assistant', 'content': json.dumps({'answer': answer,
                               'needHuman': not rows, 'sourceIds': ids}, ensure_ascii=False)}
                    finish = 'stop'
                payload = {'id': 'offline-g4', 'object': 'chat.completion', 'created': 1792700000,
                    'model': 'offline-g4-tool-fixture', 'choices': [{'index': 0, 'message': message, 'finish_reason': finish}],
                    'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}}
                encoded = json.dumps(payload, ensure_ascii=False).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    @property
    def base(self):
        return 'http://127.0.0.1:' + str(self.server.server_port)

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java.exe'))
    parser.add_argument('--port', type=int, default=18090)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        raise ValueError('Invalid local port')
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    artifact = verify_artifact(jar)
    directory = ROOT / '07_测试与质量/07_经验图谱合成演示数据'
    seed_path = directory / 'staff_experience_seed.json'
    seed_bytes = seed_path.read_bytes()
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    seed_hash = hashlib.sha256(seed_bytes).hexdigest()
    if seed_hash != manifest['sha256']:
        raise ValueError('Frozen synthetic input changed')
    case = next(c for c in json.loads(seed_bytes)['records'] if c['id'] == 'EXP01')
    report = {'status': 'RUNNING', 'stage': 'G4', 'date': datetime.now(timezone.utc).isoformat(),
              'jar_sha256': artifact['jar_sha256'], 'seed_sha256': seed_hash, 'vendor_calls': 0,
              'chat_provider': 'OFFLINE_DETERMINISTIC_PROTOCOL_FIXTURE_NOT_DEEPSEEK',
              'embedding': 'local Ollama bge-m3:latest', 'embedding_calls': None,
              'semantic_answer_quality_tested': False, 'retrieval_comparison_completed': False,
              'business_effect_independently_verified': False, 'checks': [], 'runs': {}}
    try:
        with OfflineGraphFixture() as fixture:
            with LocalQualityRuntime(args.java, jar, port=args.port, staff_experience=True, experience_cards=True,
                                     experience_graph=True, offline_graph_fixture=fixture.base, extra_submitters=1) as app:
                report['actor_ids'] = [v[0] for v in app.actors.values()]
                c = case['experience_card']
                ticket = app.api('/api/tickets', {'title': c['problem_type'], 'description': 'G4合成演示，无真实客户数据。',
                                 'creatorId': app.actors['SUBMITTER'][0]}, role='SUBMITTER')
                path = '/api/tickets/' + str(ticket['id'])
                assigned = app.api(path + '/assign', {'assigneeId': app.actors['HANDLER'][0],
                                   'operatorId': app.actors['ADMIN'][0], 'expectedVersion': ticket['version']})
                note = app.api(path + '/interactions', {'requestId': str(uuid.uuid4()), 'expectedVersion': assigned['version'],
                    'kind': 'STAFF_REPLY', 'origin': 'AI_SYNTHETIC_DEMO', 'correctsInteractionId': None,
                    'content': next(i['text'] for i in case['interactions'] if i['role'] == 'HANDLER'),
                    'evidenceRefs': c['evidence_refs']}, role='HANDLER')
                card = {key: c[source] for key, source in (
                    ('problemType', 'problem_type'), ('symptoms', 'symptoms'), ('conditions', 'conditions'), ('steps', 'steps'),
                    ('contraindications', 'contraindications'), ('hypotheses', 'hypotheses'), ('evidenceRefs', 'evidence_refs'), ('factScope', 'fact_scope'))}
                card.update({'counterexamples': [], 'supportStatus': 'REFERENCED_DEMO'})
                prepared = app.api(path + '/experience-cards', {'requestId': str(uuid.uuid4()), 'expectedVersion': assigned['version'],
                    'positiveInteractionIds': [note['id']], 'counterexampleInteractionIds': [], 'card': card}, role='HANDLER')
                app.api(path + '/resolve', {'operatorId': app.actors['HANDLER'][0], 'solution': prepared['card']['answer']}, role='HANDLER')
                state = app.wait_for(lambda: app.api(path + '/knowledge'), lambda rows: rows and rows[0].get('state') == 'DRAFT', 'G4 draft')
                candidate = state[0]['resolutionId']
                base = '/api/knowledge/candidates/' + candidate
                app.api(base + '/experience', {'expectedRevision': 0, 'submissionId': prepared['submission']['id']})
                before = app.api('/api/agent/runs', {'message': c['problem_type']}, role='SUBMITTER')
                report['runs']['before_approval'] = before
                assert before['status'] == 'PARTIAL' and before['needHuman']
                assert not before['evidence'][0]['sources']
                report['runs']['before_approval'] = before
                app.api(base + '/approve', {'expectedRevision': 0, 'question': prepared['card']['question'],
                    'answer': prepared['card']['answer'], 'scope': 'SHARED_DEMO', 'redactionConfirmed': True})
                app.wait_for(lambda: app.api(base), lambda row: row['candidate'].get('indexedRevision') == 1, 'G4 graph index')
                graph = app.api(base + '/graph')
                assert graph['status'] == 'READY'
                source_id = f'resolved-ticket/{candidate}/v1.md#0'
                before_ticket = app.api(path, role='SUBMITTER')
                published = app.api('/api/agent/runs', {'message': c['problem_type']}, role='SUBMITTER')
                report['runs']['published'] = published
                assert published['status'] == 'SUCCEEDED', published['statusReason']
                assert published['modelCalls'] == 2 and published['toolCalls'] == 1
                row = next(s for s in published['evidence'][0]['sources'] if s['sourceId'] == source_id)
                assert row['graphHash'] == graph['publication']['graphHash'] and row['revision'] == 1
                assert row['facts']['steps'] == c['steps'] and set(row['facts']['conditions']) == set(c['conditions'])
                assert row['facts']['contraindications'] == c['contraindications'] and row['paths'] and row['evidence']
                assert source_id in published['answer'] and '合成演示' in published['answer']
                assert app.api(path, role='SUBMITTER') == before_ticket
                app.api(base + '/graph', role='HANDLER', expected=403)
                report['runs']['published'] = published
                fixture.bad_citation = True
                bad = app.api('/api/agent/runs', {'message': c['problem_type']}, role='SUBMITTER')
                report['runs']['unsupported_citation'] = bad
                assert bad['statusReason'] == 'UNSUPPORTED_SOURCE' and bad['status'] == 'PARTIAL'
                fixture.bad_citation = False
                report['runs']['unsupported_citation'] = bad
                fixture.on_final = lambda: app.api(base + '/retract', {'expectedRevision': 1})
                during = app.api('/api/agent/runs', {'message': c['problem_type']}, role='SUBMITTER')
                report['runs']['retracted_during_answer'] = during
                assert during['statusReason'] == 'STALE_GRAPH_SOURCE' and source_id not in during['answer']
                report['runs']['retracted_during_answer'] = during
                # Independent user budget for the last scenario; never raise/reset production limits.
                after = app.api('/api/agent/runs', {'message': c['problem_type']}, role='CASE_0')
                report['runs']['after_retraction'] = after
                assert after['status'] == 'PARTIAL' and not after['evidence'][0]['sources']
                report['runs']['after_retraction'] = after
                report['graph'] = {'nodes': len(graph['nodes']), 'edges': len(graph['edges']),
                                   'evidence': len(graph['evidence']), 'graph_hash': row['graphHash'], 'source_id': source_id}
                assert len(fixture.requests) == 10
                report['offline_protocol_requests'] = len(fixture.requests)
                report['checks'] = ['unreviewed graph hidden through real Agent HTTP tool protocol',
                    'real RabbitMQ -> local BGE -> PG nodes/relations/evidence -> read-only graph tool',
                    'same-source facts, complete conditions, ordered steps, scope and current version reach model context',
                    'returned source cited; fabricated node/source ID rejected',
                    'retraction during model response rejected before final output',
                    'new retrieval after retraction returns no withdrawn graph',
                    'shared graph readable; internal admin inspection forbidden; ticket unchanged']
            actors = ','.join(map(str, report['actor_ids']))
            assert app.sql('mysql', f'SELECT COUNT(*) FROM ticket WHERE creator_id IN ({actors});') == '0'
            assert app.sql('mysql', f'SELECT COUNT(*) FROM agent_run WHERE actor_id IN ({actors});') == '0'
            assert app.sql('pgvector', f"SELECT COUNT(*) FROM experience_graph_publication WHERE candidate_id='{candidate}';") == '0'
            for graph_id in app.graph_nodes_touched | app.graph_edges_touched:
                assert len(graph_id) == 64 and all(ch in '0123456789abcdef' for ch in graph_id)
            report['own_records_cleaned'] = True
        assert seed_path.read_bytes() == seed_bytes
        report['frozen_input_unchanged'] = True
        report['status'] = 'PASS'
    except BaseException as error:
        report['status'] = 'FAIL'
        report['error_type'] = type(error).__name__
        raise
    finally:
        output = TEMP / 'g4-agent-experience-graph.json'
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({key: report.get(key) for key in ('status', 'stage', 'vendor_calls', 'offline_protocol_requests', 'own_records_cleaned', 'error_type')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
