"""Zero-vendor preflight or explicitly authorized five-case full native Agent experiment.

No forced tool_choice, no tool name in user questions, no evidence injected as a fake tool result.
Real mode uses SpringAgentModel -> DeepSeek -> Java tools -> model -> final answer.
"""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import importlib.util
import json
import os
from agent_graph_evaluation import (AgentTraceGateway, AgentDispatchGuard, HistoryEnvelope, observations,
                                   validate_grant, validate_withdrawal_continuation, digest)
from local_quality_runtime import ROOT, TEMP
from quality_checks import verify_artifact
from recall_generation import budget_snapshot, file_hash, resolve_chat_key
from phase4_eval_runtime import BudgetProxy

spec = importlib.util.spec_from_file_location('comparison_setup', Path(__file__).with_name('verify-graph-comparison.py'))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)
PLAN = ROOT / '07_测试与质量/04_AI评估集/agent-graph-v1/cases.json'


def implementation_hashes():
    result = {name: file_hash(Path(__file__).with_name(name)) for name in (
        'verify-real-agent-graph.py', 'agent_graph_evaluation.py', 'local_quality_runtime.py',
        'verify-graph-comparison.py', 'phase4_eval_runtime.py')}
    seed = ROOT / '07_测试与质量/07_经验图谱合成演示数据/staff_experience_seed.json'
    manifest = json.loads(seed.with_name('manifest.json').read_bytes())
    result['frozen_staff_seed_sha256'] = file_hash(seed)
    if result['frozen_staff_seed_sha256'] != manifest['sha256']:
        raise ValueError('Frozen synthetic experience material changed')
    return result


def execute(args, plan, report, gateway):
    jar = ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    # A fresh private PG database and vhost; neither existing corpus nor production limits are changed.
    app = setup.ComparisonRuntime(args.java, jar, port=args.port, staff_experience=True, experience_cards=True,
        experience_graph=True, extra_submitters=5, chat_gateway=(gateway.base, gateway.key), real_agent_evaluation=True)
    seed_path = ROOT / '07_测试与质量/07_经验图谱合成演示数据/staff_experience_seed.json'
    seed = {r['id']: r for r in json.loads(seed_path.read_bytes())['records']}
    report['actor_ids'] = [v[0] for v in app.actors.values()]
    report['runs'] = []
    owned = {}
    with app:
        for seed_id in plan['seed_ids']:
            path, prepared = setup.prepare_case(app, seed[seed_id])
            candidate = setup.bind(app, path, prepared)
            base = '/api/knowledge/candidates/' + candidate
            app.api(base + '/approve', {'expectedRevision': 0, 'question': prepared['card']['question'],
                'answer': prepared['card']['answer'], 'scope': 'SHARED_DEMO', 'redactionConfirmed': True})
            app.wait_for(lambda: app.api(base), lambda r: r['candidate'].get('indexedRevision') == 1, 'Native Agent graph indexing')
            graph = app.api(base + '/graph')
            if graph['status'] != 'READY': raise RuntimeError('Owned graph not ready')
            owned[seed_id] = {'candidate': candidate, 'source_id': f'resolved-ticket/{candidate}/v1.md#0', 'graph': graph}
        report['publications'] = owned
        if gateway.envelope:
            gateway.envelope.owned_sources = {v['source_id'] for v in owned.values()}
        cases = [c for c in plan['cases'] if not args.resume_report or c['id'] == 'AG05']
        for index, case in enumerate(cases):
            withdrawn = None
            if case.get('retract_seed'):
                value = owned[case['retract_seed']]
                app.api('/api/knowledge/candidates/' + value['candidate'] + '/retract', {'expectedRevision': 1})
                withdrawn = value['source_id']
                if gateway.envelope: gateway.envelope.owned_sources.discard(withdrawn)
            before = len(gateway.rows)
            gateway.case = case['id']
            gateway.preferred_tool = case['expected_tool'] or 'searchKnowledge'
            run = app.api('/api/agent/runs', {'message': case['query']}, role='CASE_' + str(index))
            trace = gateway.rows[before:]
            observation = observations(run, trace, case['expected_tool'], withdrawn)
            report['runs'].append({'id': case['id'], 'query': case['query'], 'kind': case['kind'],
                'run': run, 'trace': trace, 'checks': observation})
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(json.dumps({'case': case['id'], 'status': run['status'], 'selected': observation['model_selected_tools'],
                'graph_completed': observation['full_graph_agent_completed'], 'real_vendor_mode': args.mode == 'real'}, ensure_ascii=False), flush=True)
            if args.mode == 'preflight' and (len(trace) != 2 or run['modelCalls'] != 2 or run['toolCalls'] != 1):
                raise RuntimeError('Zero-vendor native protocol preflight did not complete')
            if args.mode == 'real' and any(t.get('failure_type') for t in trace):
                report['stopped_after_gateway_or_transport_failure'] = case['id']
                break  # Do not spend the remaining batch on a systemic failure.
    actors = ','.join(str(v[0]) for v in app.actors.values())
    if (app.sql('mysql', f'SELECT COUNT(*) FROM ticket WHERE creator_id IN ({actors});') != '0'
            or app.sql('mysql', f'SELECT COUNT(*) FROM agent_run WHERE actor_id IN ({actors});') != '0' or app.database_created):
        raise RuntimeError('Owned evaluation data not fully cleaned')
    report['own_records_cleaned'] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=('preflight', 'real'))
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java.exe'))
    parser.add_argument('--port', type=int, default=18092)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--budget-ledger', required=True, type=Path)
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--review', type=Path)
    parser.add_argument('--authorization', type=Path)
    parser.add_argument('--private-config', type=Path)
    parser.add_argument('--resume-report', type=Path, help='Only the harness-blocked AG05 within the original authorized batch')
    args = parser.parse_args()
    args.output = args.output.resolve()
    if args.output.exists(): raise ValueError('Preserve earlier execution; no output overwrite or replay')
    plan = json.loads(PLAN.read_bytes())
    if len(plan['cases']) != 5 or plan['maximum_model_calls'] != 20:
        raise ValueError('Unexpected frozen full-Agent plan')
    for c in plan['cases']:
        if any(s in c['query'] for s in ('searchExperienceGraph', 'searchKnowledge', '图谱', '工具')):
            raise ValueError('Question must not force or name a tool')
    artifact = verify_artifact(ROOT / '06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar')
    before = budget_snapshot(args.budget_ledger)
    report = {'schema_version': 1, 'date': datetime.now(timezone.utc).isoformat(), 'status': 'RUNNING',
        'mode': args.mode, 'plan_sha256': file_hash(PLAN), 'jar_sha256': artifact['jar_sha256'],
        'implementation_hashes': implementation_hashes(), 'budget_before': before, 'vendor_calls': 0,
        'scope': 'Five known-material targeted full-Agent cases, not independent blind evaluation or population accuracy.',
        'no_forced_tool_choice': True, 'real_local_embedding': 'Ollama bge-m3:latest', 'runs': []}
    try:
        if args.mode == 'preflight':
            with AgentTraceGateway() as gateway:
                execute(args, plan, report, gateway)
            report['initial_requests'] = {r['id']: r['trace'][0]['request'] for r in report['runs']}
            report['status'] = 'PASS_ZERO_VENDOR_PREFLIGHT'
        else:
            if not all((args.review, args.preflight, args.authorization, args.private_config)):
                raise ValueError('Real mode requires concrete review, successful preflight, authorization and private key location')
            review = json.loads(args.review.read_bytes()); preflight = json.loads(args.preflight.read_bytes())
            grant = json.loads(args.authorization.read_bytes())
            if args.resume_report:
                parent = json.loads(args.resume_report.read_bytes())
                batch_before, before = validate_withdrawal_continuation(grant,review,preflight,parent,args.review,args.preflight,args.budget_ledger)
                old, current = preflight['implementation_hashes'], implementation_hashes()
                permitted_changes = {'agent_graph_evaluation.py','verify-real-agent-graph.py'}
                if any(old[k] != current[k] for k in old if k not in permitted_changes):
                    raise ValueError('Only evaluation dedup/observation and AG05 continuation code may change')
                report['continuation_of_sha256'] = file_hash(args.resume_report)
                report['continuation_reason'] = 'Original AG05 blocked by harness cross-case duplicate check; no Java/prompt/material changes.'
                report['original_batch_budget_before'] = batch_before
                report['budget_before'] = before
            else:
                before = validate_grant(grant, review, preflight, args.review, args.preflight, args.budget_ledger)
                batch_before = before
            if (preflight['plan_sha256'] != file_hash(PLAN) or preflight['jar_sha256'] != artifact['jar_sha256']
                    or (not args.resume_report and preflight['implementation_hashes'] != implementation_hashes())):
                raise ValueError('Frozen plan/runtime/implementation changed after preflight')
            consumed = args.authorization.with_suffix('.withdrawal-continuation-consumed.json' if args.resume_report else '.consumed.json')
            if args.resume_report and not args.authorization.with_suffix('.consumed.json').is_file():
                raise ValueError('No original authorized execution to continue')
            with consumed.open('x', encoding='utf-8') as handle:
                json.dump({'status': 'STARTED_AT_MOST_ONCE', 'date': report['date'], 'authorization_sha256': file_hash(args.authorization)}, handle)
            prefixes = {k:v['messages'] for k,v in preflight['initial_requests'].items()}
            tools = next(iter(preflight['initial_requests'].values()))['tools']
            envelope = HistoryEnvelope(prefixes, tools); guard = AgentDispatchGuard(batch_before['records'])
            proxy = BudgetProxy(port=0, ledger_path=args.budget_ledger, chat_key=resolve_chat_key(args.private_config),
                embedding={'name': 'ollama', 'model': 'bge-m3:latest', 'price_per_million_usd': 0},
                external_call_ceiling=400, dispatch_guard=guard, report_path=args.output.with_suffix('.budget.json'))
            # This assignment is reached only after the new explicit batch authorization passes.
            proxy.external_call_ceiling = grant['external_call_ceiling']; proxy.batch_tag = file_hash(args.review)
            with proxy, AgentTraceGateway(envelope, proxy, guard) as gateway:
                execute(args, plan, report, gateway)
            report['status'] = 'REAL_BATCH_EXECUTED_REVIEW_PENDING'
    except BaseException as error:
        report['status'] = 'FAILED'; report['error_type'] = type(error).__name__
        raise
    finally:
        report['budget_after'] = budget_snapshot(args.budget_ledger)
        report['vendor_calls'] = report['budget_after']['external_calls'] - before['external_calls']
        if args.mode == 'preflight' and report['budget_after'] != before:
            report['status'] = 'FAILED_BUDGET_CHANGED_DURING_PREFLIGHT'
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'status': report['status'], 'vendor_calls': report['vendor_calls'],
            'own_records_cleaned': report.get('own_records_cleaned')}, ensure_ascii=False))


if __name__ == '__main__': main()
