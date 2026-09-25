import json
r = json.load(open('stress_report.json', encoding='utf-8'))
print('=== 阶段失败分布 ===')
for k, v in r['phase_failures'].items():
    print(f'  {k}: {v}')
print()
print('=== TOP 10 错误 ===')
for err, cnt in r['top_errors'][:10]:
    print(f'  [{cnt}] {err}')
print()
print('=== 各阶段延迟(ms) ===')
for phase, lat in r['phase_latency_ms'].items():
    print(f'  {phase}: avg={lat["avg"]} p50={lat["p50"]} p95={lat["p95"]} p99={lat["p99"]} max={lat["max"]} count={lat["count"]}')
print()
print('=== 汇总 ===')
s = r['summary']
for k, v in s.items():
    print(f'  {k}: {v}')
