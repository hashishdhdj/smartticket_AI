"""Validate per-section provenance and produce a reproducible, zero-vendor corpus snapshot."""
from pathlib import Path
import hashlib
import json
import re

root = Path(__file__).resolve().parents[2]
corpus = root / '06_项目源代码/ticket-service/src/main/resources/knowledge'
registry = json.loads((root / '09_项目依赖与资源/03_知识库素材/knowledge-sources.json').read_text(encoding='utf-8'))
records = {item['source_ref']: item for item in registry['sections']}
if len(records) != len(registry['sections']):
    raise ValueError('Duplicate provenance entry')
snapshot = []
for directory in ('faq', 'tickets'):
    for file in sorted((corpus / directory).glob('*.md')):
        text = file.read_text(encoding='utf-8-sig')
        if not text.startswith('# '):
            raise ValueError(f'Missing document title: {file.name}')
        parts = re.split(r'^## (.+)\r?$', text, flags=re.M)
        for index in range(1, len(parts), 2):
            title, body = parts[index].strip(), parts[index + 1].strip()
            ref = f'{file.relative_to(corpus).as_posix()}#{title}'
            if not body or ref not in records:
                raise ValueError(f'Missing content/provenance: {ref}')
            record = records.pop(ref)
            for required in ('nature', 'review_date', 'scope', 'basis', 'review_status'):
                if not record.get(required):
                    raise ValueError(f'Missing {required}: {ref}')
            for basis in record['basis']:
                if not (root / basis).is_file():
                    raise ValueError(f'Missing local basis: {basis}')
            snapshot.append({'source_ref': ref, 'body_sha256': hashlib.sha256(body.encode()).hexdigest(),
                             'characters': len(body), 'nature': record['nature'], 'review_status': record['review_status']})
if records:
    raise ValueError(f'Orphaned registry entries: {list(records)}')
canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
result = {'version': registry['version'], 'section_count': len(snapshot),
          'corpus_sha256': hashlib.sha256(canonical.encode()).hexdigest(), 'sections': snapshot,
          'vector_index_updated': False, 'evaluation_frozen': False}
output = root / '07_测试与质量/06_阶段测试报告/20261014_知识库审查快照.json'
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Provenance complete: {len(snapshot)} sections; corpus hash={result["corpus_sha256"]}. Vector index not updated.')
