"""Two existing failure cases, fixed labels. Targeted regression, not independent acceptance."""
from datetime import datetime
import hashlib
import json
from phase4_eval_runtime import ROOT, BudgetProxy, EvalRuntime

reports = ROOT/'07_测试与质量/06_阶段测试报告'
specs = [('20261015_真实评估_rag_30题_ollama_ragv3.json','RH12'),
         ('20261015_真实评估_rag_30题_ollama_v3followup.json','RV08')]
cases=[]
for filename,identifier in specs:
    original=json.loads((reports/filename).read_text(encoding='utf-8'))
    row=next(row for row in original['rows'] if row['id']==identifier)
    cases.append((filename,row['case']))
report={'date':datetime.now().isoformat(timespec='seconds'),'kind':'TARGETED_EXISTING_FAILURE_REGRESSION',
        'status':'INCOMPLETE',
        'author_review_status':'PENDING','semantic_answer_quality':'PENDING_HUMAN_REVIEW',
        'threshold':.66,'jar_sha256':hashlib.sha256((ROOT/'06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar').read_bytes()).hexdigest(),
        'prompt_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'06_项目源代码/ticket-service/src/main/resources/prompts').glob('*')},
        'rows':[]}
output=reports/(datetime.now().strftime('%Y%m%d_%H%M%S')+'_RAG拒答失败题定向复验.json')
assert not output.exists()
try:
    with BudgetProxy() as proxy:
        external=sum(c.get('execution_scope','EXTERNAL')=='EXTERNAL' for c in proxy.ledger['calls'])
        assert external <= 318, 'Fewer than two external dispatch slots remain; no evaluation dispatched'
        with EvalRuntime({c['id']:'SUBMITTER' for _,c in cases},ai=True,index=False,threshold=.66,gateway_key=proxy.gateway_key) as app:
            for filename,case in cases:
                start=len(proxy.ledger['calls'])
                response=app.api('/api/ai/knowledge/answer',{'query':case['query']},actor=case['id'])
                sources=[s['sourceFile']+'#'+s['title'] for s in response['sources']]
                report['rows'].append({'case':case,'reference_report':filename,'response':response,
                    'has_generated_answer':bool(response.get('suggestion')),
                    'expected_source_hit':any(s in case['expected_sources'] for s in sources),
                    'retrieval_healthy':not response.get('degradedSources'),
                    'vendor_calls':proxy.ledger['calls'][start:],'author_semantic_grade':None})
                output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    report['status']='COMPLETED_NOT_SEMANTIC_ACCEPTANCE'
finally:
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'report':str(output.relative_to(ROOT)),'cases':[{'id':r['case']['id'],'generated':r['has_generated_answer'],
    'needHuman':r['response']['needHuman']} for r in report['rows']]},ensure_ascii=True))
