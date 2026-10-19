"""Boot the shareable Windows-built JAR under Linux with external synthetic configuration."""
from pathlib import Path
from datetime import datetime
import hashlib,json,secrets,shutil,subprocess
ROOT=Path(__file__).resolve().parents[2]
TEMP=ROOT/'.tmp'

def run():
    now=datetime.now();run_id=now.strftime('%Y%m%d_%H%M%S')+'_'+secrets.token_hex(3)
    work=TEMP/('linux-runtime-'+run_id)
    for name in ('config','scripts','out'):(work/name).mkdir(parents=True)
    info=json.loads((TEMP/'phase4-delivery-build.json').read_text(encoding='utf-8'))
    previous=ROOT/'07_测试与质量/06_阶段测试报告'/(info['run_id']+'_干净交付包构建与启动.json')
    delivery=json.loads(previous.read_text(encoding='utf-8'));assert delivery['status']=='PASS'
    jar=ROOT/delivery['delivery_artifact_path']
    assert hashlib.sha256(jar.read_bytes()).hexdigest()==delivery['jar_sha256']
    state=json.loads((TEMP/'phase4-linux-reproduction-state.json').read_text(encoding='utf-8'))
    props=(Path(state['work'])/'config/integration.properties').read_text(encoding='utf-8')
    props+='\nsmartticket.phase3.security-enabled=true\nsmartticket.phase3.record-tasks=false\n'
    first=1_800_000_000+secrets.randbelow(100_000_000)
    for index,role in enumerate(('SUBMITTER','HANDLER','ADMIN')):
        for field,value in [('id',first+index),('role',role),('token',secrets.token_urlsafe(32))]:
            props+=f'smartticket.phase3.users[{index}].{field}={value}\n'
    (work/'config/runtime.properties').write_text(props,encoding='utf-8')
    for name in ('LinuxRuntimeSmoke.java','run-linux-runtime.sh'):shutil.copy2(Path(__file__).with_name(name),work/'scripts'/name)
    image='maven:3.9.16-eclipse-temurin-17'
    digest=subprocess.check_output(['docker','image','inspect',image,'--format','{{index .RepoDigests 0}}'],timeout=20).decode().strip()
    report={'date':now.isoformat(timespec='seconds'),'status':'INCOMPLETE','vendor_calls':0,'image_digest':digest,
        'jar_sha256':delivery['jar_sha256'],'artifact':delivery['delivery_artifact_path'],
        'scope':'Linux runtime of the shareable JAR on same Docker host; not remote CI', 'private_logs':(work/'out').relative_to(ROOT).as_posix()}
    output=ROOT/'07_测试与质量/06_阶段测试报告'/(run_id+'_Linux交付包启动验收.json')
    command=['docker','run','--rm','--name','smartticket-runtime-'+run_id.lower(),'--network','smartticket-stage4_default','-e','TZ=Asia/Shanghai']
    for path,target,readonly in [(jar,'/artifact/app.jar',True),(work/'config','/config',True),(work/'scripts','/scripts',True),(work/'out','/out',False)]:
        command+=['--mount',f'type=bind,source={path},target={target}'+(',readonly' if readonly else '')]
    command += [image,'sh','/scripts/run-linux-runtime.sh']
    try:
        process=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=160)
        report['container_exit_code']=process.returncode
        if process.returncode:report['status']='FAILED';raise RuntimeError('Linux runtime verification failed; inspect ignored local logs')
        report['checks']=json.loads((work/'out/smoke.json').read_text());assert report['checks']['status']=='PASS'
        report['status']='PASS'
    except Exception as error:report['error_type']=type(error).__name__;raise
    finally:output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True))

if __name__=='__main__':run()
