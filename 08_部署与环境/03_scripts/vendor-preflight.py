"""Two explicitly budgeted DeepSeek protocol probes, not a project effect evaluation.

Prices checked 2026-10-14 at https://api-docs.deepseek.com/quick_start/pricing/.
No retries. Fixed short inputs, 128 output tokens, at most two requests.
"""
from pathlib import Path
import json
import os
import re
import time
import urllib.request
import urllib.error

root = Path(__file__).resolve().parents[2]
key = os.environ.get('AI_API_KEY', '')
local = root / '06_项目源代码/ticket-service/src/main/resources/application-local.yml'
if not key:
    text = local.read_text(encoding='utf-8-sig')
    match = re.search(r'^      api-key:\s*(.+)$', text, flags=re.M)
    if match:
        key = match.group(1).strip().strip('"\'')
if not key or key == 'not-configured' or key.startswith('${'):
    raise RuntimeError('Real AI_API_KEY unavailable; no paid request sent.')

report = {'date': '2026-10-14', 'kind': 'VENDOR_PROTOCOL_PREFLIGHT', 'project_end_to_end': False,
          'authorized_batch_budget_rmb': 10, 'probe_cost_envelope_rmb': 1,
          'model': 'deepseek-flash', 'prices_source': 'https://api-docs.deepseek.com/quick_start/pricing/',
          'fx_budget_factor': 10, 'fx_note': 'Conservative budgeting factor, not an exchange-rate quote.', 'calls': []}
prompts = [
    {'messages': [{'role': 'system', 'content': '仅输出JSON对象，字段category。合法值：账户问题、支付问题、功能异常、其他。'},
                  {'role': 'user', 'content': '我忘记账号登录密码了。'}]},
    {'messages': [{'role': 'user', 'content': '必须调用getTicketDetail查询1号工单，不要直接回答。'}],
     'tools': [{'type': 'function', 'function': {'name': 'getTicketDetail', 'description': '读取工单详情',
                'parameters': {'type': 'object', 'properties': {'ticketId': {'type': 'integer'}}, 'required': ['ticketId']}}}],
     'tool_choice': {'type': 'function', 'function': {'name': 'getTicketDetail'}}},
]
for index, prompt in enumerate(prompts):
    # Even charging 2,048 input +128 output at peak/no-cache rates stays below this envelope.
    projected = sum(c.get('estimated_rmb_upper', 0) for c in report['calls']) + (2048 * .3 + 128 * 1.2) / 1_000_000 * 10
    if projected > report['probe_cost_envelope_rmb']:
        raise RuntimeError('Probe envelope exceeded; stopped before dispatch')
    body = dict(model=report['model'], max_tokens=128, thinking={'type': 'disabled'}, **prompt)
    req = urllib.request.Request('https://api.deepseek.com/chat/completions', data=json.dumps(body).encode(),
                                 headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    started = time.monotonic()
    row = {'id': index + 1}
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            result = json.loads(response.read())
        row['elapsed_ms'] = round((time.monotonic() - started) * 1000)
        row['usage'] = result.get('usage', {})
        row['message'] = result['choices'][0]['message']
        row['estimated_rmb_upper'] = (row['usage'].get('prompt_tokens', 0) * .3 + row['usage'].get('completion_tokens', 0) * 1.2) / 1_000_000 * 10
        if index == 0:
            row['passed'] = json.loads(row['message']['content']).get('category') == '账户问题'
        else:
            calls = row['message'].get('tool_calls', [])
            row['passed'] = bool(calls and calls[0]['function']['name'] == 'getTicketDetail'
                                 and json.loads(calls[0]['function']['arguments']).get('ticketId') == 1)
    except urllib.error.HTTPError as failure:
        row.update(passed=False, http_status=failure.code)
    except (OSError, ValueError, KeyError) as failure:
        row.update(passed=False, error_type=type(failure).__name__)
    report['calls'].append(row)
    if not row['passed']:
        break
report['status'] = 'PASS' if len(report['calls']) == 2 and all(c['passed'] for c in report['calls']) else 'FAIL_OR_INCOMPLETE'
output = root / '07_测试与质量/06_阶段测试报告/20261014_真实厂商预检.json'
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'status': report['status'], 'requests_dispatched': len(report['calls']),
                  'estimated_rmb_upper': sum(c.get('estimated_rmb_upper', 0) for c in report['calls'])}))
