import socket

def redis_cmd(cmd):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(('localhost', 6379))
    s.send((cmd + '\r\n').encode())
    resp = s.recv(1048576).decode()
    s.close()
    return resp

# 采样 key 类型
print("=== KEY 采样 ===")
keys_resp = redis_cmd('SCAN 0 COUNT 100')
print(keys_resp[:2000])

print("\n=== 按前缀统计 ===")
# 用 SCAN 迭代统计前缀
prefixes = {}
cursor = 0
while True:
    resp = redis_cmd(f'SCAN {cursor} COUNT 1000')
    lines = resp.strip().split('\n')
    cursor = int(lines[1].strip())
    keys_line = lines[3] if len(lines) > 3 else ''
    # 解析 key 列表
    parts = resp.split('\n')
    # 简化：统计前5个字符
    for i in range(4, len(parts), 2):
        if i < len(parts):
            key = parts[i].strip()
            if key:
                prefix = key[:15]
                prefixes[prefix] = prefixes.get(prefix, 0) + 1
    if cursor == 0:
        break

for p, c in sorted(prefixes.items(), key=lambda x: -x[1])[:20]:
    print(f"  {p}: {c}")
