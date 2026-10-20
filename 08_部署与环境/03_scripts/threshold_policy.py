"""Versioned generation admission policy; scores are not answer correctness."""

def active_threshold():
    """Read the adopted policy and verify its immutable calibration/decision record."""
    from pathlib import Path
    import json, hashlib
    root=Path(__file__).resolve().parents[2]
    path=root/'07_测试与质量/06_阶段测试报告/RAG当前门槛策略.json'
    if not path.exists(): return .55
    policy=json.loads(path.read_text(encoding='utf-8'))
    report_path=(root/policy['report']).resolve()
    if not report_path.is_relative_to(root.resolve()): raise ValueError('Report outside project')
    raw=report_path.read_bytes();report=json.loads(raw)
    if hashlib.sha256(raw).hexdigest()!=policy['report_sha256']: raise ValueError('Calibration report changed')
    threshold=policy['selected_threshold']
    if report['status'] not in ('PASS_LOCAL_GATE_CALIBRATION_ONLY', 'CONFIGURED_RECALL_FIRST_GENERATION_PENDING') or threshold!=report['selected_threshold'] or not .0<threshold<1:
        raise ValueError('Invalid active threshold policy')
    return threshold
def select_threshold(rows):
    positives = [r for r in rows if r['expect'] == 'hit']
    negatives = [r for r in rows if r['expect'] == 'miss']
    maximum_negative = max(r['confidence'] for r in negatives)
    trials = [{'threshold': value,
        'positive_allowed': sum(r['confidence'] >= value for r in positives),
        'negative_allowed': sum(r['confidence'] >= value for r in negatives),
        'negative_margin': value - maximum_negative}
        for value in (.55, .60, .65, .66, .70, .75)]
    eligible = [t for t in trials if t['negative_allowed'] == 0 and t['negative_margin'] >= .02]
    if not eligible:
        return min(1.0, round(maximum_negative + .02, 4)), trials
    best = max(eligible, key=lambda t: (t['positive_allowed'], t['threshold'] == .66, t['threshold']))
    return best['threshold'], trials
