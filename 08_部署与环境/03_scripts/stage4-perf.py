"""Small local CRUD baseline with actual raw timings; all model and async paths disabled."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import json
import math
import runpy
import time

root = Path(__file__).resolve().parents[2]
runtime = runpy.run_path(str(Path(__file__).with_name('stage4-fault-smoke.py')))
report = {'date': '2026-10-14', 'scope': 'LOCAL_WARM_CACHE_DETAIL_ONLY', 'vendor_calls': 0,
          'async_enabled': False, 'production_capacity_claim': False, 'samples': [], 'groups': []}
try:
    runtime['start'](['--smartticket.phase3.record-tasks=false', '--smartticket.phase3.async-enabled=false'],
                     root / '.tmp/phase4-clean/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar')
    ticket = runtime['create']('performance')
    for _ in range(10): runtime['request'](f'/api/tickets/{ticket}')

    def measure(index):
        begin = time.perf_counter()
        row = {'index': index}
        try:
            value = runtime['request'](f'/api/tickets/{ticket}')
            row['success'] = value['id'] == ticket
        except Exception as failure:
            row.update(success=False, error_type=type(failure).__name__)
        row['elapsed_ms'] = (time.perf_counter() - begin) * 1000
        return row

    for concurrency in (1, 5):
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            rows = list(pool.map(measure, range(100)))
        good = sorted(r['elapsed_ms'] for r in rows if r['success'])
        report['groups'].append({'concurrency': concurrency, 'total': len(rows), 'successes': len(good),
            'error_rate': 1 - len(good) / len(rows), 'p50_ms': good[math.ceil(.50 * len(good)) - 1] if good else None,
            'p95_ms': good[math.ceil(.95 * len(good)) - 1] if good else None})
        report['samples'].extend(dict(concurrency=concurrency, **r) for r in rows)
    report['ticketId'] = ticket
    report['environment_note'] = 'Same Windows host, existing Docker Desktop resource settings, single synthetic ticket with warm cache; DB version lookup retained.'
finally:
    runtime['stop']()
    output = root / '07_测试与质量/06_阶段测试报告/20261014_本地详情性能基线.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report['groups']))
