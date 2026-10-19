"""Local-only recall-oriented gate calibration. Does not evaluate generated answer quality."""
from datetime import datetime, timezone, timedelta
from pathlib import Path
import csv
import hashlib
import json
from phase4_eval_runtime import ROOT, TEMP, BudgetProxy, EvalRuntime


def threshold_trials(rows):
    trials=[]
    for n in range(50,81):
        threshold=n/100
        tp=sum(r['positive'] and r['score']>=threshold for r in rows)
        fp=sum(not r['positive'] and r['score']>=threshold for r in rows)
        fn=sum(r['positive'] and r['score']<threshold for r in rows)
        tn=sum(not r['positive'] and r['score']<threshold for r in rows)
        trials.append({'threshold':threshold,'positive_allowed':tp,'positive_blocked':fn,
            'negative_allowed':fp,'negative_blocked':tn,
            'retrieval_gate_f2':5*tp/(5*tp+4*fn+fp) if tp+fp+fn else 0})
    return trials


def select_recall_threshold(rows):
    if not rows or not any(r['positive'] for r in rows) or not any(not r['positive'] for r in rows):
        raise ValueError('Both positive and negative calibration examples required')
    trials=threshold_trials(rows)
    return max(trials,key=lambda t:(t['retrieval_gate_f2'],-t['negative_allowed'],t['threshold'])),trials


def main():
    now=datetime.now(timezone(timedelta(hours=8)))
    output=ROOT/'07_测试与质量/06_阶段测试报告'/(now.strftime('%Y%m%d_%H%M%S')+'_RAG召回优先门槛重新校准.json')
    cases=[];inputs=[]
    source=ROOT/'07_测试与质量/04_AI评估集/RAG问答评估集.csv'
    inputs.append({'path':source.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
    with source.open(encoding='utf-8-sig') as f:
        for r in csv.DictReader(f):cases.append({'group':'historical_development','id':r['id'],
            'query':r['query'],'positive':r['expect']=='hit','expected_sources':r['expected_sources'].split('|') if r['expected_sources'] else []})
    for directory,filename in [('phase4-v1','rag-heldout-v1.jsonl'),('phase4-v2-regression','rag-followup-v2.jsonl'),('phase4-v3-newwording','rag-newwording-v3.jsonl')]:
        source=ROOT/'07_测试与质量/04_AI评估集'/directory/filename
        inputs.append({'path':source.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
        for line in source.read_text(encoding='utf-8').splitlines():
            r=json.loads(line);cases.append({'group':directory,'id':r['id'],'query':r['query'],
                'positive':not r['should_abstain'],'expected_sources':r['expected_sources']})
    ledger=TEMP/'phase4-budget-ledger.json'
    def external_calls(): return sum(r.get('provider')!='ollama' for r in json.loads(ledger.read_text(encoding='utf-8'))['calls'])
    before=external_calls()
    info=json.loads((TEMP/'phase4-delivery-build.json').read_text(encoding='utf-8'))
    previous=ROOT/'07_测试与质量/06_阶段测试报告'/(info['run_id']+'_干净交付包构建与启动.json')
    delivery=json.loads(previous.read_text(encoding='utf-8'));assert delivery['status']=='PASS'
    jar=ROOT/delivery['delivery_artifact_path'];assert hashlib.sha256(jar.read_bytes()).hexdigest()==delivery['jar_sha256']
    report={'date':now.isoformat(timespec='seconds'),'status':'RUNNING','vendor_calls':0,
        'policy':'F2 on 92 known development/regression retrieval examples; grid .50..80 step .01; ties fewer negative allowances then higher threshold',
        'policy_change':'Recall-oriented generation gate, replacing zero-negative-margin calibration. Model semantic refusal and schema/source/permission checks remain mandatory.',
        'independent_evaluation':False,'author_review_status':'PENDING',
        'limits':'All labels are draft and known. Negative allowance means sent to generation, not an incorrect answer. No real chat generation is tested.',
        'starting_threshold':.66,'inputs':inputs,'jar_sha256':delivery['jar_sha256'],
        'embedding_model':'bge-m3:latest',
        'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'corpus_sha256':json.loads((ROOT/'07_测试与质量/06_阶段测试报告/20261014_知识库审查快照.json').read_text(encoding='utf-8'))['corpus_sha256'],
        'rows':[]}
    try:
        # Ceiling equal to already-used calls explicitly prevents additional external dispatch.
        actors={r['group']+'-'+r['id']:'SUBMITTER' for r in cases}
        with BudgetProxy(external_call_ceiling=before) as proxy, EvalRuntime(actors,ai=False,index=False,threshold=.66,
                gateway_key=proxy.gateway_key,jar=jar) as app:
            assert app.embedding['name']=='ollama','Local embeddings only'
            for case in cases:
                answer=app.api('/api/ai/knowledge/answer',{'query':case['query']},actor=case['group']+'-'+case['id'])
                assert not answer['degradedSources'],'Degraded retrieval cannot calibrate'
                report['rows'].append({**case,'score':answer['confidence'],'sources':answer['sources']})
                output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        training=[r for r in report['rows'] if r['group']!='phase4-v3-newwording']
        selected,trials=select_recall_threshold(training)
        report.update(selected_threshold=selected['threshold'],selection_metrics=selected,threshold_trials=trials)
        report['groups']={}
        for group in sorted({r['group'] for r in report['rows']}):
            rows=[r for r in report['rows'] if r['group']==group]
            report['groups'][group]=[t for t in threshold_trials(rows) if t['threshold'] in (.66,selected['threshold'])]
        report['external_calls_before']=before;report['external_calls_after']=external_calls()
        assert before==report['external_calls_after'],'Unexpected external dispatch'
        report['status']='PASS_LOCAL_GATE_CALIBRATION_ONLY'
    except Exception as error:
        report.update(status='FAILED',error_type=type(error).__name__);raise
    finally:output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (TEMP/'phase4-threshold-recalibration.json').write_text(json.dumps({'report':str(output.relative_to(ROOT)),
        'selected_threshold':report['selected_threshold']}),encoding='utf-8')
    print(json.dumps({'report':str(output.relative_to(ROOT)),'selected':selected,'groups':report['groups']},ensure_ascii=True))


if __name__=='__main__':main()
