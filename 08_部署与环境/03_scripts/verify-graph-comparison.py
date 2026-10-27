"""G5 A/B/C real retrieval/representation study, capture-only dialogue, no vendor calls.

A/C are native Agent tools. B is an explicitly privileged evaluation adapter,
not a registered Agent tool. Optional CI embeddings are a protocol fixture;
default local mode uses real Ollama BGE-M3. Neither mode grades generated answers.
"""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import json
import os
import re
import time
import uuid
from local_quality_runtime import LocalQualityRuntime, ROOT, TEMP
from quality_checks import verify_artifact
from graph_comparison import (verify_dataset, card_view, source_key, admit, observation, summarize,
                              generation_bundle, canonical_bytes, sha)
from graph_comparison_fixture import ComparisonFixture


class ComparisonRuntime(LocalQualityRuntime):
    """Own a fresh PG database so corpus/model differences never contaminate existing indices."""
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.vector_database = 'g5_' + self.run_id
        self.database_created = False

    def maintenance(self, sql):
        database = self.vector_database
        try:
            self.vector_database = 'smartticket_vector'
            return self.sql('pgvector', sql)
        finally:
            self.vector_database = database

    def drop_owned_database(self):
        if self.database_created:
            if self.vector_database != 'g5_' + self.run_id or not re.fullmatch(r'g5_[0-9a-f]{12}', self.vector_database):
                raise ValueError('Refusing to drop an unowned database')
            self.maintenance('DROP DATABASE ' + self.vector_database + ';')
            self.database_created = False

    def __enter__(self):
        self.maintenance('CREATE DATABASE ' + self.vector_database + ';')
        self.database_created = True
        try:
            for file in sorted((ROOT / '06_项目源代码/sql-vector').glob('*.sql')):
                self.sql('pgvector', file.read_text(encoding='utf-8'))
            return super().__enter__()
        except BaseException:
            self.drop_owned_database()
            raise

    def __exit__(self, *args):
        graph_mode = self.experience_graph
        try:
            # The entire private database will be dropped below. Per-node orphan
            # collection is for a shared DB and would issue hundreds of needless CLI calls.
            self.experience_graph = False
            return super().__exit__(*args)
        finally:
            self.experience_graph = graph_mode
            self.drop_owned_database()


def collect(app, fixture, query, tool, role):
    fixture.tool = tool
    prior = len(fixture.contexts)
    start = time.perf_counter()
    run = app.api('/api/agent/runs', {'message': query}, role=role)
    wall = round((time.perf_counter() - start) * 1000, 3)
    if (run['status'] != 'WAITING_INPUT' or run['statusReason'] != 'CLARIFICATION_REQUIRED' or run['needHuman']
            or run['modelCalls'] != 2 or run['toolCalls'] != 1 or len(fixture.contexts) != prior + 1
            or run['steps'][0]['outcome'] != 'SUCCESS' or run['steps'][0]['tool'] != tool):
        raise RuntimeError('Capture-only protocol did not complete: ' + run['statusReason'])
    context = fixture.contexts[-1]
    if 'sources' not in context:
        raise RuntimeError('Missing real model-visible tool context')
    return {'sources': context['sources'], 'model_context': context, 'native_tool_result': run['evidence'][0],
            'retrieval_ms': run['steps'][0]['costMs'], 'http_wall_ms': wall,
            'model_calls': run['modelCalls'], 'tool_calls': run['toolCalls'],
            'capture_only_need_human': run['needHuman'], 'run_id': run['runId'],
            'representation_bytes': len(canonical_bytes(context['sources']))}


def prepare_case(app, case):
    card = case['experience_card']
    t = app.api('/api/tickets', {'title': card['problem_type'], 'description': 'G5合成对照，非真实客户数据。',
                'creatorId': app.actors['SUBMITTER'][0]}, role='SUBMITTER')
    path = '/api/tickets/' + str(t['id'])
    current = app.api(path + '/assign', {'assigneeId': app.actors['HANDLER'][0],
                      'operatorId': app.actors['ADMIN'][0], 'expectedVersion': t['version']})

    def note(content, corrects=None):
        return app.api(path + '/interactions', {'requestId': str(uuid.uuid4()), 'expectedVersion': current['version'],
            'kind': 'CORRECTION' if corrects else 'STAFF_REPLY', 'content': content, 'origin': 'AI_SYNTHETIC_DEMO',
            'correctsInteractionId': corrects, 'evidenceRefs': card['evidence_refs']}, role='HANDLER')

    wrong = None
    for attempt in case['incorrect_attempts']:
        wrong = note(attempt['reply'])
        app.api(path + '/experience-feedback', {'requestId': str(uuid.uuid4()), 'expectedVersion': current['version'],
            'interactionId': wrong['id'], 'outcome': 'FAILED', 'origin': 'SIMULATED_DEMO',
            'comment': attempt['why_wrong'], 'correctionInteractionId': None}, role='HANDLER')
    reply = note('\n'.join(i['text'] for i in case['interactions'] if i['role'] == 'HANDLER'), wrong['id'] if wrong else None)
    if wrong:
        app.api(path + '/experience-feedback', {'requestId': str(uuid.uuid4()), 'expectedVersion': current['version'],
            'interactionId': wrong['id'], 'outcome': 'CORRECTED', 'origin': 'SIMULATED_DEMO',
            'comment': '合成纠正：保留错误原文及后续纠正，非独立效果核实。', 'correctionInteractionId': reply['id']}, role='HANDLER')
    content = {key: card[source] for key, source in (
        ('problemType', 'problem_type'), ('symptoms', 'symptoms'), ('conditions', 'conditions'), ('steps', 'steps'),
        ('contraindications', 'contraindications'), ('hypotheses', 'hypotheses'), ('evidenceRefs', 'evidence_refs'), ('factScope', 'fact_scope'))}
    content['counterexamples'] = [a['reply'] + '（' + a['why_wrong'] + '）' for a in case['incorrect_attempts']]
    content['supportStatus'] = 'REFERENCED_DEMO' if case['expected_publish_eligibility'] else 'UNVERIFIED'
    payload = {'requestId': str(uuid.uuid4()), 'expectedVersion': current['version'],
               'positiveInteractionIds': [reply['id']], 'counterexampleInteractionIds': [wrong['id']] if wrong else [], 'card': content}
    prepared = app.api(path + '/experience-cards', payload, role='HANDLER')
    replay = app.api(path + '/experience-cards', payload, role='HANDLER')
    if prepared['submission'] != replay['submission']:
        raise RuntimeError('Experience request idempotence failed')
    return path, prepared


def bind(app, path, prepared):
    app.api(path + '/resolve', {'operatorId': app.actors['HANDLER'][0], 'solution': prepared['card']['answer']}, role='HANDLER')
    state = app.wait_for(lambda: app.api(path + '/knowledge'), lambda rows: rows and rows[0].get('state') == 'DRAFT', 'G5 draft')
    candidate = state[0]['resolutionId']
    base = '/api/knowledge/candidates/' + candidate
    app.api(base + '/experience', {'expectedRevision': 0, 'submissionId': prepared['submission']['id']})
    return candidate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / ('bin/java.exe' if os.name == 'nt' else 'bin/java')))
    parser.add_argument('--port', type=int, default=18091)
    parser.add_argument('--offline-embedding', action='store_true', help='CI protocol fixture, NOT real BGE retrieval evaluation')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        raise ValueError('Invalid local port')
    manifest, cases = verify_dataset()
    seed = json.loads((ROOT / '07_测试与质量/07_经验图谱合成演示数据/staff_experience_seed.json').read_text(encoding='utf-8'))
    seed_by_id = {c['id']: c for c in seed['records']}
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    artifact = verify_artifact(jar)
    output = args.output or TEMP / ('g5-ci-comparison.json' if args.offline_embedding else 'g5-local-comparison.json')
    report = {'schema_version': 1, 'status': 'RUNNING', 'date': datetime.now(timezone.utc).isoformat(), 'stage': 'G5',
        'vendor_calls': 0, 'jar_sha256': artifact['jar_sha256'], 'dataset_sha256': manifest['cases_sha256'],
        'embedding': 'OFFLINE_HASHED_BIGRAM_PROTOCOL_FIXTURE_NOT_BGE' if args.offline_embedding else 'LOCAL_OLLAMA_BGE_M3',
        'dialogue': 'OFFLINE_CAPTURE_ONLY_NO_BUSINESS_ANSWER', 'semantic_quality_tested': False,
        'independent_blind_test': False, 'label_review': 'PENDING', 'B_is_evaluation_adapter_not_Agent_tool': True,
        'native_limits_differ': manifest['native_limits_differ'], 'rows': [], 'lifecycle': {}, 'published': {}}
    try:
        with ComparisonFixture(offline_embedding=args.offline_embedding) as fixture:
            app = ComparisonRuntime(args.java, jar, port=args.port, staff_experience=True, experience_cards=True,
                experience_graph=True, extra_submitters=8, offline_graph_fixture=fixture.base,
                offline_embedding_fixture='http://127.0.0.1:11434' if args.offline_embedding else None)
            with app:
                roles = ['SUBMITTER'] + ['CASE_' + str(i) for i in range(8)]
                counter = 0

                def probe(query, tool):
                    nonlocal counter
                    role = roles[counter % len(roles)]
                    counter += 1
                    return collect(app, fixture, query, tool, role)

                source_by_experience, owned, cards = {}, {}, {}
                for case in seed['records']:
                    path, prepared = prepare_case(app, case)
                    cards[case['id']] = prepared['card']['id']
                    if case['id'] == 'EXP09':
                        if not prepared['exactDuplicate'] or prepared['reusableKnowledge']['candidateId'] != owned['EXP01']:
                            raise RuntimeError('Duplicate did not reuse reviewed experience')
                        source_by_experience['EXP09'] = source_by_experience['EXP01']
                        continue
                    candidate = bind(app, path, prepared)
                    base = '/api/knowledge/candidates/' + candidate
                    if case['id'] == 'EXP01':
                        before = probe(cases[0]['query'], 'searchExperienceGraph')
                        if before['sources']:
                            raise RuntimeError('Unreviewed evidence leaked')
                        report['lifecycle']['before_approval'] = before
                    app.api(base + '/approve', {'expectedRevision': 0, 'question': prepared['card']['question'],
                        'answer': prepared['card']['answer'], 'scope': 'SHARED_DEMO', 'redactionConfirmed': True},
                        expected=200 if case['expected_publish_eligibility'] else 400)
                    if not case['expected_publish_eligibility']:
                        report['unverified_refund_not_published'] = candidate
                        continue
                    app.wait_for(lambda: app.api(base), lambda r: r['candidate'].get('indexedRevision') == 1, 'G5 real graph index')
                    graph = app.api(base + '/graph')
                    if graph['status'] != 'READY':
                        raise RuntimeError('Graph publication not ready')
                    owned[case['id']] = candidate
                    source_by_experience[case['id']] = f'resolved-ticket/{candidate}/v1.md#0'
                    report['published'][case['id']] = {'candidate': candidate, 'card': prepared['card']['id'],
                        'nodes': len(graph['nodes']), 'edges': len(graph['edges']), 'evidence': len(graph['evidence']),
                        'graph_hash': graph['publication']['graphHash']}
                if cards['EXP01'] != cards['EXP09'] or cards['EXP01'] == cards['EXP10']:
                    raise RuntimeError('Exact dedup/condition distinction failed')
                report['dedup_and_distinct_condition'] = True
                # Rotate execution order before collecting timings, avoiding a fixed A-first warm-cache bias.
                for index, case in enumerate(cases):
                    native = {}
                    for arm in (('A', 'C') if index % 2 == 0 else ('C', 'A')):
                        native[arm] = probe(case['query'], 'searchKnowledge' if arm == 'A' else 'searchExperienceGraph')
                    start = time.perf_counter()
                    structured = []
                    for source in native['A']['native_tool_result']['sources']:
                        if len(structured) >= 2:
                            break
                        candidate, revision = source_key(source['sourceId'])
                        if candidate not in owned.values():
                            raise RuntimeError('Unexpected corpus source')
                        base = '/api/knowledge/candidates/' + candidate
                        current = app.api(base)['candidate']
                        if current['state'] != 'APPROVED' or current['revision'] != revision or current.get('indexedRevision') != revision:
                            continue
                        binding = app.api(base + '/experience')
                        structured.append(card_view(binding, source['sourceId']))
                    structured, omitted = admit(structured)
                    extra = round((time.perf_counter() - start) * 1000, 3)
                    native['B'] = {'sources': structured, 'retrieval_ms': native['A']['retrieval_ms'] + extra,
                        'adapter_incremental_ms': extra, 'latency_scope': 'A retrieval plus extra admin HTTP adapter reads; not independent Agent latency',
                        'representation_bytes': len(canonical_bytes(structured)), 'omitted_sources': omitted,
                        'model_calls': 0, 'tool_calls': 0, 'shared_A_retrieval': True}
                    for arm, value in native.items():
                        value['checks'] = observation(case, value['sources'], source_by_experience, seed_by_id)
                    report['rows'].append({'id': case['id'], 'query': case['query'], 'arms': native})
                source = source_by_experience['EXP01']
                app.api('/api/knowledge/candidates/' + owned['EXP01'] + '/retract', {'expectedRevision': 1})
                after = probe(cases[0]['query'], 'searchExperienceGraph')
                if any(s['sourceId'] == source for s in after['sources']):
                    raise RuntimeError('Retracted graph source leaked')
                report['lifecycle']['after_retraction'] = after
                report['summary'] = summarize(report['rows'])
                report['offline_protocol_requests'] = len(fixture.requests)
                if len(fixture.requests) != 52:
                    raise RuntimeError('Unexpected protocol count')
                report['embedding_texts'] = fixture.embedding_texts if args.offline_embedding else None
                report['actor_ids'] = [v[0] for v in app.actors.values()]
                # Bundle is prepared before cleanup, and never sent by this script.
                bundle = generation_bundle(report['rows'], {'dataset_sha256': manifest['cases_sha256'],
                    'jar_sha256': artifact['jar_sha256'], 'embedding': report['embedding']})
                bundle_path = output.with_name(output.stem + '-generation-inputs.json')
                bundle_path.write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
                report['generation_bundle'] = {'path': bundle_path.relative_to(ROOT).as_posix(),
                    'sha256': sha(bundle_path.read_bytes()), 'request_count': bundle['request_count'], 'status': 'PREPARED_NOT_SENT'}
            actors = ','.join(map(str, report['actor_ids']))
            if app.sql('mysql', f'SELECT COUNT(*) FROM ticket WHERE creator_id IN ({actors});') != '0':
                raise RuntimeError('Owned tickets not cleaned')
            if app.sql('mysql', f'SELECT COUNT(*) FROM agent_run WHERE actor_id IN ({actors});') != '0':
                raise RuntimeError('Owned Agent records not cleaned')
            if app.database_created:
                raise RuntimeError('Owned vector database not cleaned')
            report['own_records_cleaned'] = True
        verify_dataset()
        report['frozen_inputs_unchanged'] = True
        report['status'] = 'PASS'
    except BaseException as error:
        report['status'] = 'FAIL'
        report['error_type'] = type(error).__name__
        raise
    finally:
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k: report.get(k) for k in ('status', 'stage', 'embedding', 'offline_protocol_requests',
                         'vendor_calls', 'own_records_cleaned', 'error_type')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
