import subprocess, re

# 用 redis-cli 或直接通过 Python socket 连接 Redis
try:
    import redis
    print("redis module:", dir(redis))
except Exception as e:
    print("redis import error:", e)

# 用 socket 直接发命令
import socket
def redis_cmd(cmd):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(('localhost', 6379))
    s.send((cmd + '\r\n').encode())
    resp = s.recv(65536).decode()
    s.close()
    return resp

print("=== Redis INFO clients ===")
print(redis_cmd('INFO clients'))
print("=== Redis INFO memory ===")
mem = redis_cmd('INFO memory')
for line in mem.split('\n'):
    if 'used_memory_human' in line or 'maxmemory' in line:
        print(line.strip())
print("=== Redis 锁数量 ===")
keys = redis_cmd('DBSIZE')
print("DB size:", keys.strip())
locks = redis_cmd('KEYS wine:lock:*')
lock_count = locks.count('wine:lock:')
print("锁数量:", lock_count)
