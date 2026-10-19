"""Inspect a clean delivery JAR and boot it using only external isolated configuration."""
import hashlib
import json
import shutil
import urllib.error
import urllib.request
import zipfile
from datetime import datetime
from phase4_eval_runtime import ROOT, TEMP, EvalRuntime

info = json.loads((TEMP/'phase4-delivery-build.json').read_text(encoding='utf-8'))
jar = __import__('pathlib').Path(info['directory'])/'target/ticket-service-0.0.1-SNAPSHOT.jar'
output = ROOT/f'07_测试与质量/06_阶段测试报告/{info["run_id"]}_干净交付包构建与启动.json'
assert not output.exists(), 'Existing delivery verification preserved'
report = {'date':datetime.now().date().isoformat(),'status':'RUNNING','scope':'Same-machine clean source/build, shared JDK/Maven cache',
          'vendor_calls':0,'source_manifest_sha256':info['source_manifest_sha256'],
          'source_manifest_path':info['source_manifest_path'],
          'jar_path':str(jar.relative_to(ROOT))}
try:
    with zipfile.ZipFile(jar) as archive:
        forbidden=[name for name in archive.namelist() if name.rsplit('/',1)[-1] in
                   ('application-local.yml','phase3-local.properties','.env')]
        if forbidden: raise RuntimeError('Private configuration present in delivery artifact')
    report.update(private_configuration_entries=0,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    development = ROOT/'06_项目源代码/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar'
    if development.exists():
        with zipfile.ZipFile(jar) as clean, zipfile.ZipFile(development) as dev:
            def public_entries(archive):
                return {name:hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                        if name.startswith('BOOT-INF/classes/') and not name.endswith('/')
                        and name.rsplit('/',1)[-1] not in ('application-local.yml','phase3-local.properties','.env')}
            clean_entries, dev_entries = public_entries(clean), public_entries(dev)
            report['public_classpath_equal_to_development_build'] = clean_entries==dev_entries
            changed = [n for n in clean_entries.keys() & dev_entries.keys() if clean_entries[n]!=dev_entries[n]]
            extra = clean_entries.keys() ^ dev_entries.keys()
            empty_marker_hash = hashlib.sha256(b'').hexdigest()
            markers_only = all(n.rsplit('/',1)[-1]=='.gitkeep' and
                               (clean_entries.get(n) or dev_entries.get(n))==empty_marker_hash for n in extra)
            report['runtime_classes_and_resources_equivalent'] = not changed and markers_only
            report['non_runtime_packaging_differences'] = sorted(extra) if markers_only else []
    with EvalRuntime({'submitter':'SUBMITTER','admin':'ADMIN'}, ai=False,index=False,jar=jar) as app:
        ticket=app.api('/api/tickets',{'title':'干净交付包启动验收','description':'合成数据，不调用模型',
                                     'creatorId':1,'priority':'MEDIUM'},actor='submitter')
        detail=app.api(f'/api/tickets/{ticket["id"]}',actor='submitter')
        assert detail['creatorId']==app.identities['submitter']
        app.api('/api/admin/metrics',actor='admin')
        try:
            urllib.request.urlopen(app.base+f'/api/tickets/{ticket["id"]}',timeout=10)
            raise AssertionError('Unauthenticated request unexpectedly succeeded')
        except urllib.error.HTTPError as error:
            assert error.code==401
        report.update(status='PASS',synthetic_ticket_id=ticket['id'],trusted_identity_verified=True,
                      unauthenticated_http_status=401,admin_metrics_verified=True)
    artifacts=ROOT/'08_部署与环境/04_交付产物'
    artifacts.mkdir(exist_ok=True)
    target=artifacts/f'SmartTicket-AI-{info["run_id"][:8]}-{report["jar_sha256"][:12]}.jar'
    if target.exists():
        assert hashlib.sha256(target.read_bytes()).hexdigest()==report['jar_sha256']
    else:
        shutil.copy2(jar,target)
    report['delivery_artifact_path']=target.relative_to(ROOT).as_posix()
except Exception as error:
    report.update(status='FAILED',error_type=type(error).__name__)
    raise
finally:
    output.write_text(
        json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:report[k] for k in ('status','private_configuration_entries','vendor_calls')}))
