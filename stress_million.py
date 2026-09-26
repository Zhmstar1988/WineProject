"""
葡萄酒按杯系统 - 百万级订单压测 + 全流程对账脚本
流程：下单 -> 支付 -> 支付回调 -> 出酒 -> 出酒回调
对账：支付-出酒-库存一致性 + 酒吧对账 + 酒商对账
"""
import json
import time
import random
import threading
import statistics
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

import jwt
import urllib3

# ============ 配置 ============
API_BASE = "http://127.0.0.1:8080/api"
JWT_SECRET = "WineSingaporeDevSecretKeyForJWTTokenGenerationMustBeLong"
JWT_ALGO = "HS256"

TOTAL_ORDERS = int(sys.argv[1]) if len(sys.argv) > 1 else 1000000  # 总订单数
CONCURRENCY = 300                                                  # 并发线程数
REPORT_FILE = r"g:\WorkSpace\WineProject\stress_report_million.json"

# 瓶位配置：(bar_id, dispenser_id, slot_no, wine_sku_id)
SLOTS = [
    (1001, 3001, 1, 2001),
    (1001, 3001, 2, 2002),
    (1001, 3001, 3, 2003),
    (1002, 3002, 1, 2001),
    (1002, 3002, 2, 2002),
]
VOLUMES = [50, 150]
MAX_RETRY = 2
RETRY_BASE_MS = 300
RETRY_JITTER_MS = 150

# 用户ID范围（10万用户）
USER_ID_START = 10001
USER_ID_END = 110000

# ============ 初始化 ============
http = urllib3.PoolManager(
    num_pools=20, maxsize=50, block=True,
    timeout=urllib3.Timeout(connect=10, read=30), retries=False,
)

stats_lock = threading.Lock()
stats = {
    "total": 0, "success": 0, "failed": 0,
    "phase_latency": {"create": [], "pay": [], "pay_cb": [], "dispense": [], "dispense_cb": []},
    "phase_fail": Counter(), "errors": Counter(),
    "volume_dist": Counter(),
}

def make_token(user_id, role=1):
    return jwt.encode({"sub": str(user_id), "role": role, "iat": int(time.time()),
                       "exp": int(time.time()) + 7200}, JWT_SECRET, algorithm=JWT_ALGO)

def api_post_form(path, fields, token):
    headers = {"Authorization": "Bearer " + token,
               "Content-Type": "application/x-www-form-urlencoded"}
    body = "&".join(f"{k}={v}" for k, v in fields.items())
    r = http.request("POST", API_BASE + path, body=body.encode(), headers=headers)
    return r.status, r.data.decode()

def api_post(path, body, token, idem_key=None):
    headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
    if idem_key: headers["Idempotency-Key"] = idem_key
    r = http.request("POST", API_BASE + path, body=json.dumps(body), headers=headers)
    return r.status, r.data.decode()

def api_get(path, token):
    r = http.request("GET", API_BASE + path, headers={"Authorization": "Bearer " + token})
    return r.status, r.data.decode()

def process_order(order_idx):
    user_id = random.randint(USER_ID_START, USER_ID_END)
    token = make_token(user_id)
    bar_id, dispenser_id, slot_no, wine_sku_id = SLOTS[order_idx % len(SLOTS)]
    volume = random.choice(VOLUMES)

    # Phase 1: 下单（带重试）
    t0 = time.time()
    body = {"barId": bar_id, "dispenserId": dispenser_id, "slotNo": slot_no,
            "wineSkuId": wine_sku_id, "volumeMl": volume}
    order_no = None
    paid_amount = None
    for attempt in range(MAX_RETRY + 1):
        idem_key = f"stress-{order_idx}-{attempt}-{int(time.time()*1000)}"
        try:
            status, text = api_post("/order/create", body, token, idem_key=idem_key)
        except Exception:
            with stats_lock:
                stats["phase_fail"]["create_ex"] += 1
            return False
        if status == 200:
            data = json.loads(text)
            if data.get("code") == 200 and data.get("data") is not None:
                order_no = data["data"].get("orderNo")
                paid_amount = data["data"].get("paidAmount")
                break
            msg = str(data.get("message", ""))
            if "繁忙" in msg and attempt < MAX_RETRY:
                wait_ms = RETRY_BASE_MS * (2 ** attempt) + random.randint(0, RETRY_JITTER_MS)
                time.sleep(wait_ms / 1000.0)
                continue
            with stats_lock:
                stats["phase_fail"]["create_biz"] += 1
                stats["errors"][f"BIZ:{data.get('code')}:{msg[:80]}"] += 1
            return False
        else:
            with stats_lock:
                stats["phase_fail"]["create_http"] += 1
            return False

    with stats_lock:
        stats["phase_latency"]["create"].append(int((time.time() - t0) * 1000))
    if not order_no:
        with stats_lock: stats["phase_fail"]["create_no_order"] += 1
        return False

    # Phase 2: 支付
    t0 = time.time()
    try:
        status, text = api_post(f"/payment/pay/{order_no}", {}, token)
        if status != 200:
            with stats_lock: stats["phase_fail"]["pay_http"] += 1
            return False
        data = json.loads(text)
        if data.get("code") != 200:
            with stats_lock: stats["phase_fail"]["pay_biz"] += 1
            stats["errors"][f"PAY:{data.get('code')}:{str(data.get('message',''))[:80]}"] += 1
            return False
        # 模拟支付回调（form表单，参数 accessOrderId）
        status, text = api_post_form("/payment/notify",
                                     {"accessOrderId": order_no, "resultCode": "SUCCESS"}, token)
        with stats_lock: stats["phase_latency"]["pay"].append(int((time.time() - t0) * 1000))
        if status != 200 and "success" not in text.lower():
            with stats_lock: stats["phase_fail"]["pay_cb_http"] += 1
            return False
    except Exception:
        with stats_lock: stats["phase_fail"]["pay_ex"] += 1
        return False

    # Phase 3: 出酒
    t0 = time.time()
    try:
        status, text = api_post(f"/dispense/start/{order_no}", {}, token)
        if status != 200:
            with stats_lock: stats["phase_fail"]["dispense_http"] += 1
            return False
        data = json.loads(text)
        if data.get("code") != 200:
            with stats_lock: stats["phase_fail"]["dispense_biz"] += 1
            return False
        with stats_lock: stats["phase_latency"]["dispense"].append(int((time.time() - t0) * 1000))
        # 出酒回调（参数名: order_id, status, actual_ml）
        status, text = api_post("/dispense/callback",
                                {"order_id": order_no, "status": "SUCCESS", "actual_ml": volume}, token)
        if status != 200:
            with stats_lock: stats["phase_fail"]["dispense_cb_http"] += 1
            return False
    except Exception:
        with stats_lock: stats["phase_fail"]["dispense_ex"] += 1
        return False

    with stats_lock:
        stats["success"] += 1
        stats["volume_dist"][volume] += 1
    return True

def run_stress():
    print(f"开始压测: {TOTAL_ORDERS} 单, 并发 {CONCURRENCY}")
    start = time.time()
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {pool.submit(process_order, i): i for i in range(TOTAL_ORDERS)}
        done = 0
        for f in as_completed(futures):
            try: f.result()
            except Exception: pass
            done += 1
            if done % 10000 == 0:
                elapsed = time.time() - start
                with stats_lock:
                    succ = stats["success"]
                print(f"  进度 {done}/{TOTAL_ORDERS} ({done*100//TOTAL_ORDERS}%) 成功={succ} 耗时={elapsed:.0f}s")
    total_time = time.time() - start
    stats["total"] = TOTAL_ORDERS
    stats["failed"] = TOTAL_ORDERS - stats["success"]
    stats["total_time"] = total_time
    stats["tps"] = round(TOTAL_ORDERS / total_time, 2)
    print(f"\n压测完成: 成功={stats['success']} 失败={stats['failed']} 耗时={total_time:.0f}s TPS={stats['tps']}")

def pct(lst, p):
    if not lst: return 0
    s = sorted(lst)
    idx = int(len(s) * p / 100)
    return s[min(idx, len(s)-1)]

def check_data_consistency():
    """核对支付-出酒-库存一致性"""
    print("\n========== 数据一致性核对 ==========")
    import subprocess
    def q(sql):
        r = subprocess.run(["C:/tools/mysql/current/bin/mysql.exe", "-uroot", "-proot",
                            "winedb", "-N", "-e", sql], capture_output=True, text=True)
        return r.stdout.strip()

    # 1. 订单状态分布
    order_status = q("SELECT status, COUNT(*) FROM order_main GROUP BY status")
    print(f"订单状态分布:\n{order_status}")

    # 2. 支付状态分布
    pay_status = q("SELECT pay_status, COUNT(*) FROM order_main GROUP BY pay_status")
    print(f"支付状态分布:\n{pay_status}")

    # 3. 履约单状态
    ticket_status = q("SELECT status, COUNT(*) FROM dispense_ticket GROUP BY status")
    print(f"履约单状态分布:\n{ticket_status}")

    # 4. 库存一致性：每瓶位 订单总出酒量 vs (initial - current)
    print("\n库存一致性核对（瓶位）:")
    print(f"{'dispenser':>10} {'slot':>5} {'order_ml':>10} {'consumed_ml':>12} {'diff':>8}")
    slots = q("""SELECT s.dispenser_id, s.slot_no,
        (SELECT COALESCE(SUM(o.volume_ml),0) FROM order_main o WHERE o.dispenser_id=s.dispenser_id AND o.slot_no=s.slot_no) AS order_ml,
        (s.initial_capacity - s.current_capacity) AS consumed_ml,
        ((SELECT COALESCE(SUM(o.volume_ml),0) FROM order_main o WHERE o.dispenser_id=s.dispenser_id AND o.slot_no=s.slot_no) - (s.initial_capacity - s.current_capacity)) AS diff_ml
        FROM dispenser_slot s ORDER BY s.dispenser_id, s.slot_no""")
    print(slots)
    diff_rows = [l for l in slots.split("\n") if l.strip() and not l.startswith("dispenser")]
    total_diff = sum(int(l.split()[4]) for l in diff_rows if len(l.split()) >= 5)
    print(f"总差异: {total_diff} ml {'✅ 无超卖' if total_diff == 0 else '❌ 存在差异!'}")

    # 5. 支付金额核对
    pay_amount = q("SELECT COUNT(*), SUM(paid_amount) FROM order_main WHERE pay_status=2")
    print(f"\n已支付订单: {pay_amount}")

    return total_diff == 0

def bar_reconciliation():
    """酒吧对账：以酒吧管理员身份查看本店订单"""
    print("\n========== 酒吧对账 (role=2) ==========")
    token = make_token(9002, role=2)  # 酒吧管理员
    for bar_id in [1001, 1002]:
        status, text = api_get(f"/admin/orders?barId={bar_id}", token)
        if status == 200:
            data = json.loads(text).get("data") or []
            total_amt = sum(o.get("paidAmount", 0) or 0 for o in data)
            total_vol = sum(o.get("volumeMl", 0) or 0 for o in data)
            print(f"酒吧 {bar_id}: 订单数={len(data)} 总金额=${total_amt:.2f} 总出酒量={total_vol}ml")
        else:
            print(f"酒吧 {bar_id}: 查询失败 status={status}")

def supplier_reconciliation():
    """酒商对账：以酒商身份查看供应酒款的订单"""
    print("\n========== 酒商对账 (role=6) ==========")
    token = make_token(9004, role=6)  # 酒商
    status, text = api_get("/admin/supplier-orders", token)
    if status == 200:
        data = json.loads(text).get("data") or []
        total_amt = sum(o.get("paidAmount", 0) or 0 for o in data)
        total_vol = sum(o.get("volumeMl", 0) or 0 for o in data)
        print(f"酒商 9004: 订单数={len(data)} 总金额=${total_amt:.2f} 总出酒量={total_vol}ml")
        # 按酒款分组
        by_sku = defaultdict(lambda: {"count": 0, "amt": 0, "vol": 0})
        for o in data:
            sku = o.get("wineSkuId")
            by_sku[sku]["count"] += 1
            by_sku[sku]["amt"] += o.get("paidAmount", 0) or 0
            by_sku[sku]["vol"] += o.get("volumeMl", 0) or 0
        for sku, v in sorted(by_sku.items()):
            print(f"  酒款 {sku}: {v['count']}单 ${v['amt']:.2f} {v['vol']}ml")
    else:
        print(f"酒商对账查询失败 status={status}")

def generate_report():
    """生成测试报告"""
    report = {
        "total_orders": TOTAL_ORDERS,
        "concurrency": CONCURRENCY,
        "success": stats["success"],
        "failed": stats["failed"],
        "success_rate": round(stats["success"] / TOTAL_ORDERS * 100, 2),
        "total_time_sec": round(stats["total_time"], 1),
        "tps": stats["tps"],
        "volume_distribution": dict(stats["volume_dist"]),
        "latency": {k: {"p50": pct(v, 50), "p95": pct(v, 95), "p99": pct(v, 99)}
                    for k, v in stats["phase_latency"].items()},
        "phase_failures": dict(stats["phase_fail"]),
        "top_errors": dict(stats["errors"].most_common(10)),
    }
    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n报告已保存: {REPORT_FILE}")
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    run_stress()
    consistent = check_data_consistency()
    bar_reconciliation()
    supplier_reconciliation()
    generate_report()
    print(f"\n{'='*50}")
    print(f"数据一致性: {'✅ 通过' if consistent else '❌ 失败'}")
    print(f"成功率: {stats['success']/TOTAL_ORDERS*100:.2f}%")
    print(f"TPS: {stats['tps']}")
