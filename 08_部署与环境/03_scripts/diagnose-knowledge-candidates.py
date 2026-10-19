"""Compare source-backed concise sections locally before adopting them. No DB writes."""
from pathlib import Path
from datetime import datetime
import hashlib, importlib.util, json, os, subprocess

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('local_diagnostic',Path(__file__).with_name('diagnose-local-retrieval.py'))
local=importlib.util.module_from_spec(spec);spec.loader.exec_module(local)
TEMP=ROOT/'.tmp'

def run():
    assert any(m['name']==local.MODEL and m['digest']==local.MODEL_DIGEST for m in local.local_json('/api/tags')['models'])
    candidate=ROOT/'05_AI输出代码/raw/20261015_知识正文候选.json'
    proposed=json.loads(candidate.read_text(encoding='utf-8'))['sections']
    chunks=json.loads((TEMP/'phase4-java-chunks.json').read_text(encoding='utf-8'))
    settings=dict(line.split('=',1) for line in (TEMP/'phase4.env').read_text().splitlines() if '=' in line)
    env=os.environ.copy();env['PGPASSWORD']=settings['POSTGRES_PASSWORD']
    sql="SELECT json_agg(row_to_json(t)) FROM (SELECT source_file,chunk_index,embedding::text AS vector FROM knowledge_chunk)t"
    data=json.loads(subprocess.check_output(['docker','exec','-e','PGPASSWORD','smartticket-stage4-pgvector-1','psql','-U','postgres','-d','smartticket_vector','-t','-A','-c',sql],env=env,timeout=20))
    baseline={(r['source_file'],r['chunk_index']):json.loads(r['vector']) for r in data}
    cachefile=TEMP/'local-retrieval-diagnostic-cache.json';cache=json.loads(cachefile.read_text(encoding='utf-8'))
    def embed(text):
        key=hashlib.sha256((local.MODEL_DIGEST+'\n'+text).encode()).hexdigest()
        if key not in cache:
            cache[key]=local.local_json('/api/embed',{'model':local.MODEL,'input':[text],'truncate':False})['embeddings'][0]
            cachefile.write_text(json.dumps(cache),encoding='utf-8')
        return cache[key]
    replacements={(c['sourceFile'],c['sectionTitle']):c['content'] for c in proposed}
    current=[baseline[(c['sourceFile'],c['chunkIndex'])] for c in chunks]
    revised=[embed(replacements[(c['sourceFile'],c['sectionTitle'])]) if (c['sourceFile'],c['sectionTitle']) in replacements else vector for c,vector in zip(chunks,current)]
    prior=ROOT/'07_测试与质量/06_阶段测试报告/20261015_193556_本地检索输入配方诊断.json'
    cases=[r for r in json.loads(prior.read_text(encoding='utf-8'))['rows'] if r['recipe']=='current_database_body']
    report={'date':datetime.now().isoformat(timespec='seconds'),'status':'DIAGNOSTIC_ONLY','vendor_calls':0,
        'writes_to_vector_database':0,'threshold':.66,'candidate_sha256':hashlib.sha256(candidate.read_bytes()).hexdigest(),
        'scope':'Existing 92 development/regression questions; vector only. Does not evaluate generation or independent quality.', 'groups':{},'rows':[]}
    recipes=[('current',current),('concise_sections',revised)]
    for c in proposed:
        key=(c['sourceFile'],c['sectionTitle'])
        recipes.append(('only:'+c['sectionTitle'], [replacement if (chunk['sourceFile'],chunk['sectionTitle'])==key else original
            for chunk,original,replacement in zip(chunks,current,revised)]))
    for name,vectors in recipes:
        observed=[]
        for c in cases:
            vector=embed(c['query'])
            ranked=sorted(enumerate(vectors),key=lambda pair:local.cosine(vector,pair[1]),reverse=True)[:3]
            sources=[chunks[i]['sourceFile']+'#'+chunks[i]['sectionTitle'] for i,_ in ranked]
            rank=next((i+1 for i,s in enumerate(sources) if s in c['sources']),0)
            observed.append({'recipe':name,'id':c['id'],'group':c['group'],'positive':c['positive'],'rank':rank,
                'score':local.cosine(vector,ranked[0][1]),'sources':sources})
        report['rows'].extend(observed);report['groups'][name]={}
        for group in sorted({c['group'] for c in cases}):
            pos=[r for r in observed if r['group']==group and r['positive']];neg=[r for r in observed if r['group']==group and not r['positive']]
            report['groups'][name][group]={'positive_count':len(pos),'negative_count':len(neg),'hit_at_3':sum(r['rank']>0 for r in pos)/len(pos),
                'positive_above_threshold':sum(r['score']>=.66 for r in pos),'negative_above_threshold':sum(r['score']>=.66 for r in neg)}
    output=ROOT/'07_测试与质量/06_阶段测试报告'/(datetime.now().strftime('%Y%m%d_%H%M%S')+'_知识正文候选对照.json')
    assert not output.exists();output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'report':str(output.relative_to(ROOT)),'groups':report['groups']},ensure_ascii=True))

if __name__=='__main__':run()
