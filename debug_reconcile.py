import jwt, time, json, urllib3, subprocess
http = urllib3.PoolManager(timeout=urllib3.Timeout(connect=10, read=30))
SECRET = "WineSingaporeDevSecretKeyForJWTTokenGenerationMustBeLong"
H = {"Authorization":"Bearer "+jwt.encode({"sub":"9002","role":2,"iat":int(time.time()),"exp":int(time.time())+3600}, SECRET, algorithm="HS256")}

# 酒吧对账
r = http.request("GET", "http://127.0.0.1:8080/api/admin/orders?barId=1001", headers=H)
d = json.loads(r.data)
data = d.get("data") or []
print(f"酒吧1001: code={d['code']} 返回订单数={len(data)}")
if data:
    bars = set(o.get("barId") for o in data)
    print(f"  包含的barId: {bars}")

# 酒商对账
H2 = {"Authorization":"Bearer "+jwt.encode({"sub":"9004","role":6,"iat":int(time.time()),"exp":int(time.time())+3600}, SECRET, algorithm="HS256")}
r = http.request("GET", "http://127.0.0.1:8080/api/admin/ext/supplier-orders", headers=H2)
d = json.loads(r.data)
print(f"酒商: code={d['code']} msg={d.get('message')} 返回订单数={len(d.get('data') or [])}")

# DB查询
r = subprocess.run(["C:/tools/mysql/current/bin/mysql.exe","-uroot","-proot","winedb","-N","-e",
    "SELECT bar_id, COUNT(*) FROM order_main GROUP BY bar_id"], capture_output=True, text=True)
print("DB订单分布:", r.stdout.strip())
r = subprocess.run(["C:/tools/mysql/current/bin/mysql.exe","-uroot","-proot","winedb","-N","-e",
    "SELECT supplier_id FROM wine_sku WHERE id=2001"], capture_output=True, text=True)
print("酒款2001的supplier_id:", r.stdout.strip())
r = subprocess.run(["C:/tools/mysql/current/bin/mysql.exe","-uroot","-proot","winedb","-N","-e",
    "SELECT supplier_id FROM sys_user WHERE id=9004"], capture_output=True, text=True)
print("用户9004的supplier_id:", r.stdout.strip())
