"""Validate the configured allowlisted embedding API before any index rebuild."""
import json
import math
import urllib.error
import urllib.request
from datetime import datetime
from phase4_eval_runtime import ROOT, BudgetProxy, embedding_provider


def main():
    provider = embedding_provider()
    report = {'date': datetime.now().isoformat(timespec='seconds'),
              'provider': provider['name'], 'model': provider['model'],
              'scope': 'authorized synthetic single-request preflight', 'status': 'FAILED'}
    try:
        with BudgetProxy() as proxy:
            try:
                body = json.dumps({'model': provider['model'],
                                   'input': ['合成工单：支付成功却没有订单'],
                                   'encoding_format': 'float'}).encode('utf-8')
                request = urllib.request.Request(
                    'http://127.0.0.1:19014/embed/v1/embeddings', data=body,
                    headers={'Authorization': 'Bearer ' + proxy.gateway_key,
                             'Content-Type': 'application/json'})
                with urllib.request.urlopen(request, timeout=35) as response:
                    data = json.load(response)
                rows = data['data']
                if len(rows) != 1 or len(rows[0]['embedding']) != 1024:
                    raise ValueError('Expected one 1024-dimensional vector')
                if not all(isinstance(v, (int, float)) and not isinstance(v, bool)
                           and math.isfinite(v) for v in rows[0]['embedding']):
                    raise ValueError('Embedding contains invalid numeric values')
                report.update(status='PASS', dimensions=1024, usage=data.get('usage'))
            except urllib.error.HTTPError as failure:
                report['http_status'] = failure.code
                # Only sanitized metadata from the metered ledger; never response bodies.
                call = proxy.ledger['calls'][-1] if proxy.ledger['calls'] else {}
                for key in ('vendor_error_code', 'vendor_error_detail', 'charged_upper_rmb'):
                    if key in call:
                        report[key] = call[key]
            except (ValueError, KeyError, TypeError, OSError) as failure:
                report['error_type'] = type(failure).__name__
    except RuntimeError as failure:
        report['error_type'] = type(failure).__name__
    directory = ROOT / '07_测试与质量/06_阶段测试报告'
    stamp = datetime.now().strftime('%Y%m%d_%H%M%S_%f')
    path = directory / f'{stamp}_嵌入预检_{provider["name"]}.json'
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
