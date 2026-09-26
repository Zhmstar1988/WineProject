import subprocess, random, string, os

r = subprocess.run(['C:/tools/mysql/current/bin/mysql.exe','-uroot','-proot','winedb','-N','-e',
    'SELECT id FROM sys_user WHERE role=1'], capture_output=True, text=True)
existing = set()
for line in r.stdout.strip().split('\n'):
    if line.strip(): existing.add(int(line.strip()))

to_create = [i for i in range(10001, 110001) if i not in existing]
print(f'需要创建 {len(to_create)} 个用户')

batch_size = 500
created = 0
sql_file = r'g:\WorkSpace\WineProject\insert_users.sql'
for i in range(0, len(to_create), batch_size):
    batch = to_create[i:i+batch_size]
    values = []
    for uid in batch:
        phone = '+65' + str(random.randint(80000000, 99999999))
        nick = ''.join(random.choices(string.ascii_letters, k=6))
        values.append(f"({uid},'{phone}',1,'{nick}',1,NOW(),NOW())")
    sql = "INSERT IGNORE INTO sys_user(id,phone,role,nickname,status,create_time,update_time) VALUES " + ",".join(values) + ";\n"
    with open(sql_file, 'w', encoding='utf-8') as f:
        f.write(sql)
    r = subprocess.run(['C:/tools/mysql/current/bin/mysql.exe','-uroot','-proot','winedb'],
                       input=open(sql_file, encoding='utf-8').read(),
                       capture_output=True, text=True)
    created += len(batch)
    if created % 10000 == 0:
        print(f'  已创建 {created}/{len(to_create)}')

os.remove(sql_file)
r = subprocess.run(['C:/tools/mysql/current/bin/mysql.exe','-uroot','-proot','winedb','-N','-e',
    'SELECT COUNT(*) FROM sys_user WHERE role=1'], capture_output=True, text=True)
print(f'C端用户总数: {r.stdout.strip()}')
