"""Check final-JAR RAG routing/diagnostic fields through real HTTP, using local embeddings only."""
from datetime import datetime,timezone,timedelta
import hashlib,json
from phase4_eval_runtime import ROOT,TEMP,BudgetProxy,EvalRuntime,embedding_provider
from threshold_policy import active_threshold

def main():
    assert embedding_provider()['name']=='ollama','Local Ollama is required; cloud embeddings are not allowed here'
    reports=ROOT/'07_测试与质量/06_阶段测试报告'
    info=json.loads((TEMP/'phase4-delivery-build.json').read_text(encoding='utf-8'))
    delivery=json.loads((reports/(info['run_id']+'_干净交付包构建与启动.json')).read_text(encoding='utf-8'))
    assert delivery['status']=='PASS'
    jar=ROOT/delivery['delivery_artifact_path']
    assert hashlib.sha256(jar.read_bytes()).hexdigest()==delivery['jar_sha256']
    dataset=ROOT/'07_测试与质量/04_AI评估集/phase4-v3-newwording'
    manifest=json.loads((dataset/'manifest.json').read_text(encoding='utf-8'))
    name='rag-newwording-v3.jsonl';raw=(dataset/name).read_bytes()
    assert hashlib.sha256(raw).hexdigest()==manifest['files'][name]['sha256']
    cases={case['id']:case for case in map(json.loads,raw.decode('utf-8').splitlines())}
    selected=[cases[case_id] for case_id in ('NW02','NW19','NW22')]
    ledger=json.loads((TEMP/'phase4-budget-ledger.json').read_text(encoding='utf-8'))
    before=sum(call.get('provider')!='ollama' for call in ledger['calls'])
    assert 0<=before<=400
    now=datetime.now(timezone(timedelta(hours=8)))
    output=reports/(now.strftime('%Y%m%d_%H%M%S')+'_RAG最终版本地HTTP验收.json')
    report={'date':now.isoformat(timespec='seconds'),'status':'INCOMPLETE','vendor_calls':0,
        'jar_sha256':delivery['jar_sha256'],'threshold':active_threshold(),
        'scope':'Actual final-JAR HTTP, local embeddings and chat disabled. Routing/diagnostics only, not generated answer correctness.','rows':[]}
    try:
        # At a zero-use fresh ledger, ai=False still forbids chat. Existing ledgers cannot dispatch another call.
        with BudgetProxy(external_call_ceiling=max(1,before)) as proxy,EvalRuntime(
                {case['id']:'SUBMITTER' for case in selected},ai=False,index=False,
                jar=jar,gateway_key=proxy.gateway_key) as app:
            for case in selected:
                answer=app.api('/api/ai/knowledge/answer',{'query':case['query']},actor=case['id'])
                expected='BELOW_THRESHOLD' if case['should_abstain'] else 'AI_UNAVAILABLE'
                assert answer['statusReason']==expected,(case['id'],answer.get('statusReason'))
                assert answer['needHuman'] and not answer.get('suggestion')
                assert (answer['confidence']>=report['threshold']) != case['should_abstain']
                report['rows'].append({'id':case['id'],'confidence':answer['confidence'],'statusReason':answer['statusReason']})
            after=sum(call.get('provider')!='ollama' for call in proxy.ledger['calls'])
            assert before==after,'Unexpected external dispatch'
            report['external_calls_before']=before;report['external_calls_after']=after
        report['status']='PASS'
    except Exception as error:
        report['error_type']=type(error).__name__;raise
    finally:output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'report':str(output.relative_to(ROOT)),**report},ensure_ascii=True))

if __name__=='__main__':main()
