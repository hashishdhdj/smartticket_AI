"""Vector-only recipe diagnostic. Local Ollama only; never writes either vector database.

Existing labelled questions are development/regression observations, not a blind test.
"""
from pathlib import Path
from datetime import datetime
import csv
import hashlib
import json
import math
import os
import re
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
TEMP = ROOT / '.tmp'
REPORTS = ROOT / '07_测试与质量/06_阶段测试报告'
MODEL = 'bge-m3:latest'
MODEL_DIGEST = '7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def local_json(path, payload=None):
    request = urllib.request.Request('http://127.0.0.1:11434' + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json'})
    with OPENER.open(request, timeout=120) as response:
        return json.loads(response.read())


def cosine(a, b):
    denominator = math.sqrt(sum(x*x for x in a)*sum(x*x for x in b))
    return sum(x*y for x,y in zip(a,b))/denominator if denominator else 0


def run():
    models = local_json('/api/tags')['models']
    assert any(m['name'] == MODEL and m['digest'] == MODEL_DIGEST for m in models), 'Unexpected local model'
    chunks = json.loads((TEMP / 'phase4-java-chunks.json').read_text(encoding='utf-8'))
    settings = dict(line.split('=',1) for line in (TEMP/'phase4.env').read_text().splitlines() if '=' in line)
    env = os.environ.copy()
    env['PGPASSWORD'] = settings['POSTGRES_PASSWORD']
    sql = "SELECT json_agg(row_to_json(t)) FROM (SELECT source_file,chunk_index,model,embedding::text AS vector FROM knowledge_chunk ORDER BY source_file,chunk_index)t"
    raw = subprocess.check_output(['docker','exec','-e','PGPASSWORD','smartticket-stage4-pgvector-1',
        'psql','-U','postgres','-d','smartticket_vector','-t','-A','-c',sql],env=env,timeout=20)
    rows = json.loads(raw)
    assert len(rows) == len(chunks) == 32 and all(r['model']==MODEL for r in rows)
    baseline = {(r['source_file'],r['chunk_index']):json.loads(r['vector']) for r in rows}
    assert all(len(v)==1024 for v in baseline.values())
    cachepath = TEMP/'local-retrieval-diagnostic-cache.json'
    cache = json.loads(cachepath.read_text(encoding='utf-8')) if cachepath.exists() else {}

    def embeddings(texts):
        keys = [hashlib.sha256((MODEL_DIGEST+'\n'+text).encode()).hexdigest() for text in texts]
        missing = {key:text for key,text in zip(keys,texts) if key not in cache}
        pending = list(missing.items())
        for offset in range(0,len(pending),2):
            batch = pending[offset:offset+2]
            data = local_json('/api/embed',{'model':MODEL,'input':[text for _,text in batch],'truncate':False})
            assert len(data['embeddings']) == len(batch)
            for (key,_),vector in zip(batch,data['embeddings']):
                assert len(vector)==1024 and all(math.isfinite(x) for x in vector)
                cache[key]=vector
            cachepath.write_text(json.dumps(cache),encoding='utf-8')
        return [cache[key] for key in keys]

    cases=[]
    with (ROOT/'07_测试与质量/04_AI评估集/RAG问答评估集.csv').open(encoding='utf-8-sig') as source:
        for c in csv.DictReader(source):
            cases.append({'group':'historical_development','id':c['id'],'query':c['query'],
                'positive':c['expect']=='hit','sources':c['expected_sources'].split('|') if c['expected_sources'] else []})
    for directory,filename in [('phase4-v1','rag-heldout-v1.jsonl'),('phase4-v2-regression','rag-followup-v2.jsonl')]:
        for line in (ROOT/'07_测试与质量/04_AI评估集'/directory/filename).read_text(encoding='utf-8').splitlines():
            c=json.loads(line)
            cases.append({'group':directory,'id':c['id'],'query':c['query'],'positive':not c['should_abstain'],'sources':c['expected_sources']})
    qvectors=embeddings([c['query'] for c in cases])
    bodies=[re.sub(r'(?m)^\*\*适用范围\*\*[^\r\n]*(?:\r?\n|$)','',c['content']).strip() for c in chunks]
    recipes={'current_database_body':[baseline[(c['sourceFile'],c['chunkIndex'])] for c in chunks],
             'remove_scope_preamble':embeddings(bodies),
             'section_title_and_body_without_scope':embeddings([c['sectionTitle']+'\n'+body for c,body in zip(chunks,bodies)])}
    report={'date':datetime.now().isoformat(timespec='seconds'),'status':'COMPLETE_DIAGNOSTIC_ONLY',
        'vendor_calls':0,'writes_to_vector_database':0,'model':MODEL,'model_digest':MODEL_DIGEST,
        'scope':'Vector-only cosine; does not reproduce keyword retrieval/RRF/generation. No threshold/index changes.',
        'author_review_status':'PENDING','threshold':.66,'groups':{},'rows':[]}
    for name,vectors in recipes.items():
        observed=[]
        for case,query_vector in zip(cases,qvectors):
            ranked=sorted(enumerate(vectors),key=lambda pair:cosine(query_vector,pair[1]),reverse=True)
            top=[(chunks[i],cosine(query_vector,v)) for i,v in ranked[:3]]
            rank=next((i+1 for i,(c,_) in enumerate(top) if c['sourceFile']+'#'+c['sectionTitle'] in case['sources']),0)
            observed.append({**case,'recipe':name,'rank':rank,'top_score':top[0][1],
                'above_threshold':top[0][1]>=.66,'top_sources':[c['sourceFile']+'#'+c['sectionTitle'] for c,_ in top]})
        report['rows'].extend(observed)
        report['groups'][name]={}
        for group in sorted({c['group'] for c in cases}):
            pos=[c for c in observed if c['group']==group and c['positive']]
            neg=[c for c in observed if c['group']==group and not c['positive']]
            report['groups'][name][group]={'positive_count':len(pos),'negative_count':len(neg),
                'hit_at_3':sum(c['rank']>0 for c in pos)/len(pos),
                'positive_above_threshold':sum(c['above_threshold'] for c in pos),
                'negative_above_threshold':sum(c['above_threshold'] for c in neg)}
    output=REPORTS/(datetime.now().strftime('%Y%m%d_%H%M%S')+'_本地检索输入配方诊断.json')
    assert not output.exists()
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'report':str(output.relative_to(ROOT)),'groups':report['groups']},ensure_ascii=True))


if __name__ == '__main__':
    run()
