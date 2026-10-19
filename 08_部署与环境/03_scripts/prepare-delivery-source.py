"""Copy an explicit public source snapshot into a new directory; never copy local credentials."""
from pathlib import Path
import hashlib
import json
import secrets
import shutil
from datetime import datetime, timezone, timedelta

ROOT = Path(__file__).resolve().parents[2]
now = datetime.now(timezone(timedelta(hours=8)))
run_id = now.strftime('%Y%m%d_%H%M%S') + '_' + secrets.token_hex(3)
source = ROOT / '06_项目源代码/ticket-service'
destination = ROOT / f'.tmp/delivery-{now:%Y%m%d-%H%M%S}-{secrets.token_hex(3)}/ticket-service'
destination.mkdir(parents=True, exist_ok=False)
entries = []
paths = [source / 'pom.xml'] + sorted((source / 'src').rglob('*'))
for path in paths:
    if not path.is_file() or path.name in ('application-local.yml', 'phase3-local.properties', '.env'):
        continue
    if path.suffix not in ('.java','.json','.md','.yml','.yaml','.properties','.xml','.txt','.sql','.jsonl'):
        continue
    relative = path.relative_to(source)
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
    entries.append({'path':relative.as_posix(), 'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
snapshot = {'created_at':now.isoformat(), 'scope':'Uncommitted public-source working-tree snapshot',
            'private_configuration_excluded':True, 'files':entries}
snapshot['source_manifest_sha256'] = hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()
public = ROOT / f'07_测试与质量/06_阶段测试报告/{run_id}_交付源码文件清单.json'
assert not public.exists(), 'Preserve existing source manifest'
public.write_text(json.dumps(snapshot,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(ROOT/'.tmp/phase4-delivery-build.json').write_text(json.dumps({'directory':str(destination),
    'run_id':run_id,'source_manifest_path':public.relative_to(ROOT).as_posix(),
    'source_manifest_sha256':snapshot['source_manifest_sha256']},ensure_ascii=False),encoding='utf-8')
print('Public source files copied: '+str(len(entries))+'. Local config, target and credentials excluded.')
