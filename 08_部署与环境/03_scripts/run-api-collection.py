"""Run current collection assertions through real isolated HTTP, with no vendor model calls."""
from datetime import datetime
import hashlib
import json
import subprocess
from phase4_eval_runtime import ROOT, TEMP, EvalRuntime

source = ROOT/'07_测试与质量/02_接口测试/Postman集合导出文件/SmartTicket-阶段四鉴权与Agent.postman_collection.json'
report = {'date':datetime.now().isoformat(timespec='seconds'), 'status':'INCOMPLETE', 'vendor_calls':0,
          'scope':'isolated stage4, async and model disabled', 'postman_desktop_executed':False,
          'collection_sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
output = ROOT/'07_测试与质量/06_阶段测试报告'/(datetime.now().strftime('%Y%m%d_%H%M%S')+'_接口集合真实HTTP验收.json')
assert not output.exists()
try:
    jar=None
    delivery_pointer=TEMP/'phase4-delivery-build.json'
    if delivery_pointer.exists():
        info=json.loads(delivery_pointer.read_text(encoding='utf-8'))
        delivery=json.loads((ROOT/'07_测试与质量/06_阶段测试报告'/(info['run_id']+'_干净交付包构建与启动.json')).read_text(encoding='utf-8'))
        assert delivery['status']=='PASS','Complete clean delivery build before API verification'
        jar=ROOT/delivery['delivery_artifact_path']
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==delivery['jar_sha256']
        report.update(delivery_artifact_path=delivery['delivery_artifact_path'],jar_sha256=delivery['jar_sha256'])
    with EvalRuntime({'submitter':'SUBMITTER','handler':'HANDLER','admin':'ADMIN'},ai=False,index=False,jar=jar) as app:
        payload={'collection':json.loads(source.read_text(encoding='utf-8')), 'variables':{
            'baseUrl':app.base,'submitterId':str(app.identities['submitter']),
            **{role+'Token':app.auth[role] for role in app.auth}}}
        process=subprocess.run(['node',str(ROOT/'08_部署与环境/03_scripts/run-api-collection.mjs')],
            input=json.dumps(payload).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=160)
        if process.returncode:
            # Never dump request input, tokens or general-purpose runner stderr.
            report['runner_error_type']='ASSERTION_OR_HTTP_FAILURE'
            raise RuntimeError('Collection assertion/HTTP failure; no credentials printed')
        report.update(json.loads(process.stdout))
except Exception as failure:
    report['error_type']=type(failure).__name__
    raise
finally:
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'status':report['status'],'requests':report['requests'],'assertions':report['assertions'],
                  'report':str(output.relative_to(ROOT))},ensure_ascii=True))
