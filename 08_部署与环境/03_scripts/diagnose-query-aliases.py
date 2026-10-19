"""Local-only diagnostic of technical state aliases; no index or threshold writes."""
from pathlib import Path
from datetime import datetime
import hashlib, json, math, re, urllib.request, subprocess, os

ROOT=Path(__file__).resolve().parents[2]
TEMP=ROOT/'.tmp'
MODEL='bge-m3:latest'
DIGEST='7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab'
ALIASES={'DEGRADED':'规则兜底','RULE':'规则','PENDING_ASSIGN':'待分配','STALE':'分析输入过时','PUT':'全量替换更新','UPDATE':'更新审计'}
pattern=re.compile(r'(?<![A-Za-z0-9_])('+'|'.join(ALIASES)+r')(?![A-Za-z0-9_])')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))

def normalized(q, append=False):
    return pattern.sub(lambda m: m.group(0)+' '+ALIASES[m.group(0)] if append else ALIASES[m.group(0)],q)

def cosine(a,b):
    return sum(x*y for x,y in zip(a,b))/math.sqrt(sum(x*x for x in a)*sum(y*y for y in b))

def run():
    prior=ROOT/'07_测试与质量/06_阶段测试报告/20261015_193556_本地检索输入配方诊断.json'
    cases=[r for r in json.loads(prior.read_text(encoding='utf-8'))['rows'] if r['recipe']=='current_database_body']
    settings=dict(line.split('=',1) for line in (TEMP/'phase4.env').read_text().splitlines() if '=' in line)
    env=os.environ.copy();env['PGPASSWORD']=settings['POSTGRES_PASSWORD']
    sql="SELECT json_agg(row_to_json(t)) FROM (SELECT source_file,section_title,embedding::text AS vector FROM knowledge_chunk)t"
    data=subprocess.check_output(['docker','exec','-e','PGPASSWORD','smartticket-stage4-pgvector-1','psql','-U','postgres','-d','smartticket_vector','-t','-A','-c',sql],env=env,timeout=20)
    rows=json.loads(data)
    cachefile=TEMP/'local-retrieval-diagnostic-cache.json'
    cache=json.loads(cachefile.read_text(encoding='utf-8'))
    def embed(q):
        key=hashlib.sha256((DIGEST+'\n'+q).encode()).hexdigest()
        if key not in cache:
            request=urllib.request.Request('http://127.0.0.1:11434/api/embed',data=json.dumps({'model':MODEL,'input':[q],'truncate':False}).encode(),headers={'Content-Type':'application/json'})
            with opener.open(request,timeout=120) as response: vector=json.loads(response.read())['embeddings'][0]
            assert len(vector)==1024
            cache[key]=vector;cachefile.write_text(json.dumps(cache),encoding='utf-8')
        return cache[key]
    observed=[]
    for name,append in [('replace_code',False),('append_alias',True)]:
        for case in cases:
            q=normalized(case['query'],append)
            if q==case['query']:continue
            vector=embed(q)
            ranked=sorted([(r['source_file']+'#'+r['section_title'],cosine(vector,json.loads(r['vector']))) for r in rows],key=lambda r:r[1],reverse=True)
            observed.append({'recipe':name,'id':case['id'],'group':case['group'],'positive':case['positive'],
                'query':case['query'],'effective_query':q,'old_score':case['top_score'],'new_score':ranked[0][1],
                'old_rank':case['rank'],'new_rank':next((i+1 for i,(s,_) in enumerate(ranked[:3]) if s in case['sources']),0),
                'new_top_sources':[s for s,_ in ranked[:3]]})
    report={'date':datetime.now().isoformat(timespec='seconds'),'status':'DIAGNOSTIC_ONLY','vendor_calls':0,
        'writes_to_vector_database':0,'threshold_unchanged':.66,'scope':'Vector-only on existing seen data; no independent acceptance or generation','rows':observed}
    output=ROOT/'07_测试与质量/06_阶段测试报告'/(datetime.now().strftime('%Y%m%d_%H%M%S')+'_状态词归一化诊断.json')
    assert not output.exists();output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'report':str(output.relative_to(ROOT)),'rows':observed},ensure_ascii=True))

if __name__=='__main__':run()
