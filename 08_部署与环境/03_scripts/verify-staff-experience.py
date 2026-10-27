"""G1-G3: frozen synthetic fixtures -> real isolated middleware, zero vendor calls.

No production import mode. Always removes only this run's own records on exit.
The frozen inputs/labels are never overwritten with execution results.
"""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import os
import uuid
from local_quality_runtime import LocalQualityRuntime, ROOT, TEMP
from quality_checks import verify_artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java.exe'))
    parser.add_argument('--port', type=int, default=18089)
    parser.add_argument('--with-cards', action='store_true', help='G2 cards, review lifecycle and local BGE-M3 text retrieval')
    parser.add_argument('--with-graph', action='store_true', help='G3 real PG graph publication and shared-source withdrawal')
    args = parser.parse_args()
    if args.with_graph and not args.with_cards:
        parser.error('--with-graph requires --with-cards')
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
        raise ValueError('Frozen synthetic fixture changed')
    seed = json.loads(seed_bytes)
    if (seed['metadata']['data_origin'] != 'CODEX_AI_AUTHORED_SYNTHETIC_DEMO'
            or seed['metadata']['contains_real_customer_records'] or len(seed['records']) != 12):
        raise ValueError('Only the reviewed synthetic fixture is supported')
    report = {'status': 'RUNNING', 'date': datetime.now(timezone.utc).isoformat(), 'stage': 'G3' if args.with_graph else ('G2' if args.with_cards else 'G1'),
              'jar_sha256': artifact['jar_sha256'], 'seed_sha256': seed_hash,
              'vendor_calls': 0, 'embedding_calls': None if args.with_cards else 0, 'chat_enabled': False,
              'embedding': 'local Ollama bge-m3:latest' if args.with_cards else None,
              'scope': ('Real structured cards and versioned PG graph lifecycle; no Agent graph tool, training or answer-quality claim'
                        if args.with_graph else 'Real capture, optional normalized cards and reviewed text retrieval; no graph, training or answer-quality claim'),
              'synthetic_business_feedback_is_runtime_success_evidence': False, 'cases': []}
    try:
        with LocalQualityRuntime(args.java, jar, port=args.port, staff_experience=True, extra_handlers=2,
                                 experience_cards=args.with_cards, experience_graph=args.with_graph) as app:
            mappings = {staff['ref']: role for staff, role in zip(seed['staff'], ('HANDLER', 'HANDLER_0', 'HANDLER_1'))}
            report['staff_identity_mapping'] = {ref: app.actors[role][0] for ref, role in mappings.items()}
            report['actor_ids'] = [value[0] for value in app.actors.values()]
            expected_interactions = expected_feedback = 0
            card_rows = []
            card_payloads = {}
            published_candidate = None
            secondary_candidate = None
            graph_snapshots = {}

            def bind_candidate(path, role, prepared, approval_status=200):
                # Deliberate synthetic workflow fixture, separate from preparing a card.
                app.api(path + '/resolve', {'operatorId': app.actors[role][0], 'solution': prepared['card']['answer']}, role=role)
                state = app.wait_for(lambda: app.api(path + '/knowledge'),
                                     lambda rows: rows and rows[0].get('state') == 'DRAFT', 'G2 text candidate')
                candidate = state[0]['resolutionId']
                bound = app.api('/api/knowledge/candidates/' + candidate + '/experience',
                                {'expectedRevision': 0, 'submissionId': prepared['submission']['id']})
                assert bound['revision'] == 0 and bound['cardHash'] == prepared['card']['hash']
                app.api('/api/knowledge/candidates/' + candidate + '/approve',
                        {'expectedRevision': 0, 'question': prepared['card']['question'], 'answer': prepared['card']['answer'],
                         'scope': 'SHARED_DEMO', 'redactionConfirmed': True}, expected=approval_status)
                if approval_status == 200:
                    app.wait_for(lambda: app.api('/api/knowledge/candidates/' + candidate),
                                 lambda result: result['candidate'].get('indexedRevision') == 1, 'G2 local reviewed text index')
                return candidate
            for case in seed['records']:
                role = mappings[case['staff_ref']]
                customer = '\n'.join(item['text'] for item in case['interactions'] if item['role'] == 'CUSTOMER')
                ticket = app.api('/api/tickets', {'title': 'G1合成演示 ' + case['id'] + ' ' + case['experience_card']['problem_type'],
                                 'description': 'AI合成场景，不是真实客户数据。\n' + customer,
                                 'creatorId': app.actors['SUBMITTER'][0]}, role='SUBMITTER')
                path = '/api/tickets/' + str(ticket['id'])
                assigned = app.api(path + '/assign', {'assigneeId': app.actors[role][0],
                                   'operatorId': app.actors['ADMIN'][0], 'expectedVersion': ticket['version']})
                version = assigned['version']
                notes, feedback_ids = [], []

                def note(content, kind='STAFF_REPLY', corrects=None):
                    nonlocal expected_interactions
                    body = {'requestId': str(uuid.uuid4()), 'expectedVersion': version, 'kind': kind,
                            'content': content, 'origin': 'AI_SYNTHETIC_DEMO',
                            'correctsInteractionId': corrects, 'evidenceRefs': case['experience_card']['evidence_refs']}
                    actual = app.api(path + '/interactions', body, role=role)
                    replay = app.api(path + '/interactions', body, role=role)
                    assert actual == replay and actual['authorId'] == app.actors[role][0]
                    assert actual['ticketVersion'] == version and actual['origin'] == 'AI_SYNTHETIC_DEMO'
                    notes.append(actual['id']); expected_interactions += 1
                    return actual['id']

                def feedback(interaction, comment, outcome='UNVERIFIED', correction=None):
                    nonlocal expected_feedback
                    body = {'requestId': str(uuid.uuid4()), 'expectedVersion': version, 'interactionId': interaction,
                            'outcome': outcome, 'origin': 'SIMULATED_DEMO', 'comment': comment,
                            'correctionInteractionId': correction}
                    actual = app.api(path + '/experience-feedback', body, role=role)
                    assert actual == app.api(path + '/experience-feedback', body, role=role)
                    assert actual['origin'] == 'SIMULATED_DEMO'
                    feedback_ids.append(actual['id']); expected_feedback += 1

                wrong = None
                for attempt in case['incorrect_attempts']:
                    assert attempt['usable_as_successful_step'] is False
                    wrong = note(attempt['reply'])
                    feedback(wrong, attempt['why_wrong'], 'FAILED')
                reply = '\n'.join(item['text'] for item in case['interactions'] if item['role'] == 'HANDLER')
                final_note = note(reply, 'CORRECTION' if wrong else 'STAFF_REPLY', wrong)
                steps_note = note('处理步骤：\n' + '\n'.join(case['experience_card']['steps']), 'TROUBLESHOOTING')
                feedback(wrong or final_note, case['simulated_business_feedback'], 'CORRECTED' if wrong else 'UNVERIFIED',
                         final_note if wrong else None)
                captured = app.api(path + '/interactions?limit=100', role=role)
                assert [item['id'] for item in captured['records']] == notes
                assert len(app.api(path + '/experience-feedback?limit=100', role=role)['records']) == len(feedback_ids)
                app.api(path + '/interactions', role='SUBMITTER', expected=403)
                outsider = 'HANDLER_0' if role != 'HANDLER_0' else 'HANDLER'
                app.api(path + '/interactions', role=outsider, expected=403)
                assert len(app.api(path + '/interactions')['records']) == len(notes)
                assert app.api(path)['version'] == version
                assert app.api(path + '/knowledge') == []  # Capture must not silently publish.
                runtime_candidate = None
                if args.with_cards:
                    c = case['experience_card']
                    content = {key: c[source] for key, source in (
                        ('problemType', 'problem_type'), ('symptoms', 'symptoms'), ('conditions', 'conditions'),
                        ('steps', 'steps'), ('contraindications', 'contraindications'), ('hypotheses', 'hypotheses'),
                        ('evidenceRefs', 'evidence_refs'), ('factScope', 'fact_scope'))}
                    content['counterexamples'] = [item['reply'] + '（' + item['why_wrong'] + '）' for item in case['incorrect_attempts']]
                    # Explicit authored development label, not an inferred factual correctness score.
                    content['supportStatus'] = 'REFERENCED_DEMO' if case['expected_publish_eligibility'] else 'UNVERIFIED'
                    payload = {'requestId': str(uuid.uuid4()), 'expectedVersion': version,
                               'positiveInteractionIds': [final_note, steps_note],
                               'counterexampleInteractionIds': [wrong] if wrong else [], 'card': content}
                    prepared = app.api(path + '/experience-cards', payload, role=role)
                    replay = app.api(path + '/experience-cards', payload, role=role)
                    assert prepared['submission'] == replay['submission']
                    assert app.api(path)['status'] == 'PROCESSING'  # No fabricated business resolution.
                    app.api(path + '/experience-cards', role='SUBMITTER', expected=403)
                    row = {'id': case['id'], 'card_id': prepared['card']['id'], 'card_hash': prepared['card']['hash'],
                           'submission_id': prepared['submission']['id'], 'exact_duplicate': prepared['exactDuplicate'],
                           'reusable_knowledge': prepared.get('reusableKnowledge'), 'support_status': content['supportStatus']}
                    card_rows.append(row);card_payloads[case['id']] = (path, role, payload, prepared)
                    if case['id'] == 'EXP01':
                        published_candidate = bind_candidate(path, role, prepared)
                        runtime_candidate = published_candidate
                        answer = app.api('/api/ai/knowledge/answer', {'query': content['problemType']}, role='SUBMITTER')
                        expected = f'resolved-ticket/{published_candidate}/v1.md'
                        assert any(source['sourceFile'] == expected for source in answer['sources'])
                        assert not answer.get('suggestion')
                        report['published_text_source'] = expected
                        if args.with_graph:
                            g = app.api('/api/knowledge/candidates/' + published_candidate + '/graph')
                            assert g['status'] == 'READY' and g['nodes'] and g['edges'] and g['evidence']
                            assert g['publication']['cardHash'] == prepared['card']['hash']
                            graph_snapshots['initial'] = g
                            app.api('/api/knowledge/candidates/' + published_candidate + '/graph', role=role, expected=403)
                    if args.with_graph and case['id'] == 'EXP09':
                        # Deliberately publish the second fixture to test independent evidence support.
                        # Normal callers can use the already returned reuse hint without another review.
                        secondary_candidate = bind_candidate(path, role, prepared)
                        runtime_candidate = secondary_candidate
                        g = app.api('/api/knowledge/candidates/' + secondary_candidate + '/graph')
                        assert g['status'] == 'READY'
                        assert g['publication']['graphHash'] == graph_snapshots['initial']['publication']['graphHash']
                        graph_snapshots['secondary'] = g
                        shared = next(edge['id'] for edge in g['edges'] if edge['relation'] == 'HAS_SYMPTOM')
                        supports = app.sql('pgvector', f"SELECT COUNT(*) FROM experience_edge_evidence WHERE edge_id='{shared}' "
                                           f"AND candidate_id IN ('{published_candidate}','{secondary_candidate}');")
                        assert supports == '2'
                    if case['id'] == 'EXP11':
                        bad = dict(payload, requestId=str(uuid.uuid4()), positiveInteractionIds=[wrong], counterexampleInteractionIds=[])
                        app.api(path + '/experience-cards', bad, role=role, expected=400)
                    if case['id'] == 'EXP12':
                        negative_candidate = bind_candidate(path, role, prepared, approval_status=400)
                        runtime_candidate = negative_candidate
                        assert app.api('/api/knowledge/candidates/' + negative_candidate)['candidate']['state'] == 'DRAFT'
                        assert app.sql('mysql', f"SELECT COUNT(*) FROM knowledge_job WHERE resolution_id='{negative_candidate}' AND kind='INDEX';") == '0'
                report['cases'].append({'id': case['id'], 'ticket_id': ticket['id'], 'staff_id': app.actors[role][0],
                                        'interaction_ids': notes, 'feedback_ids': feedback_ids,
                                        'capture_verified': True, 'business_outcome': 'SYNTHETIC_NOT_INDEPENDENTLY_VERIFIED',
                                        'graph_built': bool(args.with_graph and runtime_candidate and case['id'] != 'EXP12'), 'candidate_id': runtime_candidate})
            owned = ','.join(str(row['ticket_id']) for row in report['cases'])
            rows = app.sql('mysql', f'SELECT COUNT(*) FROM ticket_interaction WHERE ticket_id IN ({owned});'
                           f'SELECT COUNT(*) FROM experience_feedback WHERE ticket_id IN ({owned});'
                           f'SELECT COUNT(*) FROM ticket_resolution WHERE ticket_id IN ({owned});')
            expected_resolutions = 3 if args.with_graph else (2 if args.with_cards else 0)
            assert [int(value) for value in rows.splitlines()] == [expected_interactions, expected_feedback, expected_resolutions]
            report['real_database_counts_before_cleanup'] = {'tickets': len(report['cases']),
                        'interactions': expected_interactions, 'feedback': expected_feedback, 'resolutions': expected_resolutions}
            if args.with_cards:
                by_id = {row['id']: row for row in card_rows}
                assert len({row['card_id'] for row in card_rows}) == 11
                assert by_id['EXP01']['card_id'] == by_id['EXP09']['card_id']
                assert by_id['EXP09']['reusable_knowledge']['candidateId'] == published_candidate
                assert by_id['EXP01']['card_id'] != by_id['EXP10']['card_id']
                path, role, payload, old = card_payloads['EXP01']
                app.api('/api/knowledge/candidates/' + published_candidate + '/retract', {'expectedRevision': 1})
                reusable = app.api(path + '/experience-cards/' + old['submission']['id'])['prepared'].get('reusableKnowledge')
                if args.with_graph:
                    assert reusable['candidateId'] == secondary_candidate
                    assert app.api('/api/knowledge/candidates/' + published_candidate + '/graph')['status'] == 'NOT_VISIBLE'
                else:
                    assert not reusable
                app.wait_for(lambda: app.api('/api/knowledge/candidates/' + published_candidate),
                             lambda result: any(job['kind'] == 'DELETE' and job['status'] == 'SUCCEEDED' for job in result['jobs']),
                             'G2 text retraction')
                if args.with_graph:
                    other = app.api('/api/knowledge/candidates/' + secondary_candidate + '/graph')
                    assert other['status'] == 'READY'
                    supports = app.sql('pgvector', f"SELECT COUNT(*) FROM experience_edge_evidence WHERE edge_id='{shared}' "
                                       f"AND candidate_id IN ('{published_candidate}','{secondary_candidate}');")
                    assert supports == '1'
                    graph_snapshots['secondary_after_first_retraction'] = other
                revised = dict(payload, requestId=str(uuid.uuid4()), expectedVersion=app.api(path)['version'],
                               card=dict(payload['card'], conditions=payload['card']['conditions'] + ['新增修订演示：操作前再次确认适用范围']))
                revision = app.api(path + '/experience-cards', revised, role=role)
                bound = app.api('/api/knowledge/candidates/' + published_candidate + '/experience',
                                {'expectedRevision': 2, 'submissionId': revision['submission']['id']})
                assert bound['revision'] == 3
                app.api('/api/knowledge/candidates/' + published_candidate + '/approve',
                        {'expectedRevision': 3, 'question': revision['card']['question'], 'answer': revision['card']['answer'],
                         'scope': 'SHARED_DEMO', 'redactionConfirmed': True})
                app.wait_for(lambda: app.api('/api/knowledge/candidates/' + published_candidate),
                             lambda result: result['candidate'].get('indexedRevision') == 4, 'G2 revision 4 text index')
                answer = app.api('/api/ai/knowledge/answer', {'query': revision['card']['question']}, role='SUBMITTER')
                sources = [source['sourceFile'] for source in answer['sources']]
                assert f'resolved-ticket/{published_candidate}/v4.md' in sources
                assert f'resolved-ticket/{published_candidate}/v1.md' not in sources and not answer.get('suggestion')
                history = app.sql('mysql', f"SELECT revision FROM knowledge_experience_version WHERE candidate_id='{published_candidate}' ORDER BY revision;")
                assert history.splitlines() == ['0', '1', '2', '3', '4']
                counts = app.sql('mysql', f'SELECT COUNT(DISTINCT card_id) FROM experience_submission WHERE ticket_id IN ({owned});'
                                 f'SELECT COUNT(*) FROM experience_submission WHERE ticket_id IN ({owned});'
                                 'SELECT COUNT(*) FROM knowledge_experience_version WHERE candidate_id IN '
                                 f'(SELECT resolution_id FROM ticket_resolution WHERE ticket_id IN ({owned}));')
                expected_versions = 8 if args.with_graph else 6
                assert [int(value) for value in counts.splitlines()] == [12, 13, expected_versions]
                all_card_ids = {row['card_id'] for row in card_rows} | {revision['card']['id']}
                report['real_database_counts_before_cleanup'].update(cards=12, submissions=13, structure_versions=expected_versions)
                report.update(cards=card_rows, unique_base_cards=11, revised_cards=1,
                              revision_history=[0,1,2,3,4], graph_built=args.with_graph, agent_graph_tool_implemented=False,
                              support_status_origin='AUTHORED_DEVELOPMENT_FIXTURE_LABEL_NOT_MODEL_OR_RUNTIME_FACT_VERIFICATION')
                if args.with_graph:
                    g = app.api('/api/knowledge/candidates/' + published_candidate + '/graph')
                    assert g['status'] == 'READY' and g['revision'] == 4 and g['sourceFile'].endswith('/v4.md')
                    assert g['publication']['cardHash'] == revision['card']['hash']
                    assert g['publication']['graphHash'] != graph_snapshots['initial']['publication']['graphHash']
                    assert any(node['key'] == '新增修订演示：操作前再次确认适用范围' for node in g['nodes'])
                    assert len(g['receiptHistory']) == 2
                    graph_snapshots['revised'] = g
                    assert app.api('/api/knowledge/candidates/' + published_candidate + '/graph/rebuild', {'expectedRevision': 4})['status'] == 'ALREADY_CURRENT'
                    graph_counts = app.sql('pgvector', 'SELECT COUNT(*) FROM experience_graph_publication '
                                           f"WHERE candidate_id IN ('{published_candidate}','{secondary_candidate}') AND state='ACTIVE';"
                                           'SELECT COUNT(*) FROM experience_edge_evidence '
                                           f"WHERE candidate_id IN ('{published_candidate}','{secondary_candidate}');")
                    expected_evidence = len(g['evidence']) + len(graph_snapshots['secondary']['evidence'])
                    assert [int(value) for value in graph_counts.splitlines()] == [2, expected_evidence]
                    receipt_count = app.sql('mysql', f"SELECT COUNT(*) FROM knowledge_graph_receipt WHERE candidate_id IN ('{published_candidate}','{secondary_candidate}');")
                    assert receipt_count == '3'
                    report.update(graph_snapshots=graph_snapshots, graph_database_counts={'active_publications': 2,
                                  'evidence': expected_evidence, 'mysql_receipts': 3, 'shared_supports_before': 2, 'shared_supports_after_retract': 1})
        # Context cleanup already removed only this run's fixtures. Verify rather than assume it did.
        remaining = app.sql('mysql', f'SELECT COUNT(*) FROM ticket WHERE id IN ({owned});'
                            f'SELECT COUNT(*) FROM ticket_interaction WHERE ticket_id IN ({owned});'
                            f'SELECT COUNT(*) FROM experience_feedback WHERE ticket_id IN ({owned});')
        assert [int(value) for value in remaining.splitlines()] == [0, 0, 0]
        assert seed_path.read_bytes() == seed_bytes
        if args.with_cards:
            remaining = app.sql('mysql', f'SELECT COUNT(*) FROM experience_submission WHERE ticket_id IN ({owned});')
            assert remaining == '0'
            for value in all_card_ids:
                assert str(uuid.UUID(value)) == value
            literals = ','.join("'" + value + "'" for value in sorted(all_card_ids))
            orphans = app.sql('mysql', f'SELECT COUNT(*) FROM experience_card c WHERE c.card_id IN ({literals}) '
                              'AND NOT EXISTS(SELECT 1 FROM experience_submission s WHERE s.card_id=c.card_id) '
                              'AND NOT EXISTS(SELECT 1 FROM knowledge_experience_version v WHERE v.card_id=c.card_id);')
            assert orphans == '0'
            report['own_orphan_cards_remaining'] = 0
        if args.with_graph:
            candidates = [row['candidate_id'] for row in report['cases'] if row['candidate_id']]
            literals = ','.join("'" + str(uuid.UUID(value)) + "'" for value in candidates)
            remaining = app.sql('pgvector', f'SELECT COUNT(*) FROM experience_graph_publication WHERE candidate_id IN ({literals});'
                                f'SELECT COUNT(*) FROM experience_edge_evidence WHERE candidate_id IN ({literals});')
            assert remaining.splitlines() == ['0', '0']
            report['own_graph_publications_and_evidence_cleaned'] = True
            node_literals = ','.join("'" + value + "'" for value in sorted(app.graph_nodes_touched))
            edge_literals = ','.join("'" + value + "'" for value in sorted(app.graph_edges_touched))
            orphans = app.sql('pgvector', f'SELECT COUNT(*) FROM experience_edge r WHERE r.edge_id IN ({edge_literals}) '
                              'AND NOT EXISTS(SELECT 1 FROM experience_edge_evidence e WHERE e.edge_id=r.edge_id);'
                              f'SELECT COUNT(*) FROM experience_node n WHERE n.node_id IN ({node_literals}) '
                              'AND NOT EXISTS(SELECT 1 FROM experience_edge r WHERE r.from_node=n.node_id OR r.to_node=n.node_id);')
            assert orphans.splitlines() == ['0', '0']
            report['own_orphan_graph_vocabulary_remaining'] = 0
        report.update(status='PASS', own_records_cleaned=True, frozen_input_unchanged=True,
                      checks=['real HTTP and MySQL persistence', 'trusted staff identity and ticket version',
                              'three isolated staff mappings', 'idempotent interaction and feedback retries',
                              'wrong attempt -> correction -> linked feedback', 'submitter and other handler denied',
                              'capture/preparation alone does not change ticket state or publish knowledge', 'own fixture cleanup verified'])
        if args.with_cards:
            report['checks'] += ['12 base cases -> 11 canonical cards; independent provenance retained',
                                 'exact duplicate reuses approved indexed source; changed condition stays separate',
                                 'UNVERIFIED payment question cannot approve or create INDEX job',
                                 'explicit synthetic resolve -> bound structure -> approval -> local BGE -> HTTP text retrieval',
                                 'retract and revise -> revision 4 source visible; revision 1 absent; history retained',
                                 'no owned orphan canonical cards after scoped cleanup']
        if args.with_graph:
            report['checks'] += ['real PG typed nodes/relations/evidence and MySQL graph receipts',
                                 'two sources support one relation; retracting one keeps the other',
                                 'current graph revision/text/card/compiler/receipt hashes agree',
                                 'admin-only inspection; no Agent graph tool installed']
    except BaseException as error:
        report.update(status='FAILED', error_type=type(error).__name__)
        raise
    finally:
        filename = 'g3-experience-graph.json' if args.with_graph else ('g2-experience-cards.json' if args.with_cards else 'g1-staff-experience.json')
        (TEMP / filename).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key not in ('cases', 'cards', 'graph_snapshots', 'actor_ids', 'staff_identity_mapping')}, ensure_ascii=True))


if __name__ == '__main__':
    main()
