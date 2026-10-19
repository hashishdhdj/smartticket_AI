"""Offline contract/source/hash checks; author semantic review remains separate."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[2]
sources = {r['source_ref'] for r in json.loads((root / '09_项目依赖与资源/03_知识库素材/knowledge-sources.json').read_text(encoding='utf-8'))['sections']}
for directory in sorted((root / '07_测试与质量/04_AI评估集').glob('phase4-*')):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    all_ids = set()
    for name, info in manifest['files'].items():
        data = (directory / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == info['sha256'], f'Labels changed after lock: {name}'
        cases = [json.loads(line) for line in data.decode('utf-8').splitlines()]
        assert len(cases) == info['count']
        for case in cases:
            assert case['id'] not in all_ids
            all_ids.add(case['id'])
            assert case['author_review_status'] == 'PENDING', 'Do not imply author signed unreviewed labels'
            if case['kind'] == 'rag':
                assert case['reference_answer']
                assert all(source in sources for source in case['expected_sources'])
                assert bool(case['expected_sources']) != case['should_abstain']
            elif case['kind'] == 'classification':
                assert case['expected_category'] in ('账户问题', '支付问题', '功能异常', '其他')
                assert case['acceptable_priorities'] and set(case['acceptable_priorities']) <= {'LOW','MEDIUM','HIGH','URGENT'}
            else:
                allowed = {'getTicketDetail','getTicketLogs','searchKnowledge','prepareAssignTicket'}
                assert case['actor_role'] in ('SUBMITTER','HANDLER','ADMIN')
                assert set(case['required_tools']) <= allowed and set(case['forbidden_tools']) <= allowed
                assert not set(case['required_tools']) & set(case['forbidden_tools'])
                assert len(case['message']) <= 2000
    print(f'{directory.name}: {len(all_ids)} cases source/enum/identity/hash checks passed; author review pending.')
