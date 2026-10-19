"""Fresh Linux container/cache, public-source snapshot, isolated dependencies; no model calls."""
from pathlib import Path
from datetime import datetime
import hashlib
import json
import re
import secrets
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[2]
TEMP=ROOT/'.tmp'
IMAGE='maven:3.9.16-eclipse-temurin-17'


def run():
    run_id=datetime.now().strftime('%Y%m%d_%H%M%S')+'_'+secrets.token_hex(3)
    work=TEMP/('linux-repro-'+run_id)
    source=work/'source';out=work/'out';config=work/'config';scripts=work/'scripts'
    for directory in (source,out,config,scripts):directory.mkdir(parents=True,exist_ok=False)
    info=json.loads((TEMP/'phase4-delivery-build.json').read_text(encoding='utf-8'))
    original=json.loads((ROOT/info['source_manifest_path']).read_text(encoding='utf-8'))
    service=ROOT/'06_项目源代码/ticket-service'
    entries=[]
    for entry in original['files']:
        path=(service/entry['path']).resolve()
        assert path.is_relative_to(service.resolve()) and path.name not in ('application-local.yml','phase3-local.properties','.env')
        destination=source/entry['path'];destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(path,destination)
        entries.append({'path':entry['path'],'sha256':hashlib.sha256(destination.read_bytes()).hexdigest()})
    public={'created_at':datetime.now().isoformat(timespec='seconds'),'scope':'Public Java service source for clean Linux reproduction',
            'files':entries,'source_manifest_sha256':hashlib.sha256(json.dumps(entries,sort_keys=True).encode()).hexdigest()}
    reports=ROOT/'07_测试与质量/06_阶段测试报告'
    manifest=reports/(run_id+'_Linux复现源码清单.json')
    manifest.write_text(json.dumps(public,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    props=(TEMP/'phase4-integration.properties').read_text(encoding='utf-8')
    props=props.replace('127.0.0.1:13306/','mysql:3306/').replace('127.0.0.1:15432/','pgvector:5432/')
    assert 'jdbc:mysql://mysql:3306/' in props and 'jdbc:postgresql://pgvector:5432/' in props
    props+='\n'+'\n'.join(['spring.data.redis.host=redis','spring.data.redis.port=6379','test.redis.host=redis','test.redis.port=6379',
        'spring.rabbitmq.host=rabbitmq','spring.rabbitmq.port=5672','test.rabbit.host=rabbitmq','test.rabbit.port=5672',
        'smartticket.ai.enabled=false','smartticket.phase3.async-enabled=false','smartticket.vector.index-on-startup=false'])+'\n'
    (config/'integration.properties').write_text(props,encoding='utf-8')
    shutil.copy2(Path(__file__).with_name('run-linux-reproduction.sh'),scripts/'run.sh')
    digest=subprocess.check_output(['docker','image','inspect',IMAGE,'--format','{{index .RepoDigests 0}}'],timeout=20).decode().strip()
    report={'date':datetime.now().isoformat(timespec='seconds'),'status':'INCOMPLETE','vendor_calls':0,'image':IMAGE,'image_digest':digest,
        'scope':'Fresh Linux container and empty Maven cache on the same Docker host; not another physical machine or remote CI',
        'source_manifest':manifest.relative_to(ROOT).as_posix(),'source_manifest_sha256':public['source_manifest_sha256'],
        'private_configuration_in_source':False,'private_logs':out.relative_to(ROOT).as_posix()}
    output=reports/(run_id+'_Linux空缓存复现.json')
    command=['docker','run','--rm','--name','smartticket-linux-'+run_id.lower(),
        '--network','smartticket-stage4_default','-e','AI_SMOKE=false','-e','TZ=Asia/Shanghai',
        '-e','MAVEN_OPTS=-Duser.timezone=Asia/Shanghai']
    for path,target,readonly in [(source,'/source',True),(out,'/out',False),(config,'/config',True),(scripts,'/scripts',True)]:
        command+=['--mount',f'type=bind,source={path},target={target}'+(',readonly' if readonly else '')]
    command+=[IMAGE,'sh','/scripts/run.sh']
    (TEMP/'phase4-linux-reproduction-state.json').write_text(json.dumps({'work':str(work),'report':str(output)},ensure_ascii=False),encoding='utf-8')
    try:
        process=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=900)
        report['container_exit_code']=process.returncode
        for profile in ('unit','integration'):
            log=out/(profile+'.log')
            if not log.exists():continue
            text=log.read_text(encoding='utf-8',errors='replace')
            totals=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',text)
            if totals:report[profile]=dict(zip(('tests','failures','errors','skipped'),map(int,totals[-1])))
            report[profile+'_build_success']='BUILD SUCCESS' in text
        if process.returncode or not all(report.get(name+'_build_success') for name in ('unit','integration')):
            report['status']='FAILED';raise RuntimeError('Linux reproduction failed; inspect ignored local logs')
        assert all(report[name]['failures']==report[name]['errors']==report[name]['skipped']==0 for name in ('unit','integration'))
        report['status']='PASS'
    except Exception as error:
        report['error_type']=type(error).__name__
        raise
    finally:
        output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True))


if __name__=='__main__':run()
