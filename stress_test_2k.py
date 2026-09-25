"""
葡萄酒按杯系统 - 高并发压力测试脚本
流程：下单 -> 支付 -> 支付回调 -> 出酒 -> 出酒回调
覆盖 50ml / 150ml 两种杯量
"""
import json
import time
import random
import threading
import statistics
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

import jwt
import urllib3

# ============ 配置 ============
API_BASE = "http://127.0.0.1:8080/api"
JWT_SECRET = "WineSingaporeDevSecretKeyForJWTTokenGenerationMustBeLong"
JWT_ALGO = "HS256"

TOTAL_ORDERS = 2000       # 总订单数（10万级别）
CONCURRENCY = 200           # 并发线程数
USER_IDS_FILE = r"g:\WorkSpace\WineProject\stress_user_ids.txt"
REPORT_FILE = r"g:\WorkSpace\WineProject\stress_report.json"

# 瓶位配置：(bar_id, dispenser_id, slot_no, wine_sku_id)
SLOTS = [
    (1001, 3001, 1, 2001),
    (1001, 3001, 2, 2002),
    (1001, 3001, 3, 2003),
    (1002, 3002, 1, 2001),
    (1002, 3002, 2, 2002),
]
VOLUMES = [50, 150]  # 杯量
MAX_RETRY = 2          # 瓶位繁忙最大重试次数（共3次尝试）
RETRY_BASE_MS = 300    # 重试基础延迟(指数退避: 300ms, 600ms)
RETRY_JITTER_MS = 150  # 随机抖动，避免重试雪崩

# ============ 初始化 ============
http = urllib3.PoolManager(
    num_pools=20,
    maxsize=50,
    block=True,
    timeout=urllib3.Timeout(connect=10, read=30),
    retries=False,
)

# 预生成 token
with open(USER_IDS_FILE, encoding="utf-8-sig") as f:
    USER_IDS = [int(line.strip()) for line in f if line.strip()]

print(f"已加载 {len(USER_IDS)} 个用户ID")

# 线程安全计数器
stats_lock = threading.Lock()
stats = {
    "total": 0,
    "success": 0,
    "failed": 0,
    "phase_fail": Counter(),      # 各阶段失败数
    "phase_latency": defaultdict(list),  # 各阶段耗时(ms)
    "volume_count": Counter(),    # 杯量分布
    "errors": Counter(),          # 错误类型
}


def make_token(user_id):
    payload = {
        "sub": str(user_id),
        "role": 1,
        "iat": int(time.time()),
        "exp": int(time.time()) + 3600,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGO)


def api_post(path, body=None, token=None, form=False, idem_key=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idem_key:
        headers["Idempotency-Key"] = idem_key
    if form:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        data = body
    else:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body) if body else None
    resp = http.request("POST", API_BASE + path, body=data, headers=headers)
    return resp.status, resp.data.decode("utf-8", errors="replace")


def process_order(order_idx):
    """处理一笔完整订单流程"""
    user_id = random.choice(USER_IDS)
    token = make_token(user_id)
    slot = random.choice(SLOTS)
    volume = random.choice(VOLUMES)
    bar_id, dispenser_id, slot_no, wine_sku_id = slot

    order_no = None
    paid_amount = None
    phases_ok = []

    # Phase 1: 下单（带瓶位繁忙指数退避重试）
    t0 = time.time()
    create_body = {
        "barId": bar_id,
        "dispenserId": dispenser_id,
        "slotNo": slot_no,
        "wineSkuId": wine_sku_id,
        "volumeMl": volume,
    }
    for attempt in range(MAX_RETRY + 1):
        idem_key = f"stress-{order_idx}-{attempt}-{int(time.time()*1000)}"
        try:
            status, text = api_post("/order/create", create_body, token, idem_key=idem_key)
        except Exception as e:
            with stats_lock:
                stats["phase_fail"]["create_ex"] += 1
                stats["errors"][f"EX:{type(e).__name__}:{str(e)[:120]}"] += 1
            return False

        if status == 200:
            data = json.loads(text)
            if data.get("code") == 200 and data.get("data") is not None:
                order_no = data["data"].get("orderNo")
                paid_amount = data["data"].get("paidAmount")
                break
            # 业务错误：瓶位繁忙则重试，其他错误直接失败
            msg = str(data.get("message", ""))
            if "繁忙" in msg and attempt < MAX_RETRY:
                wait_ms = RETRY_BASE_MS * (2 ** attempt) + random.randint(0, RETRY_JITTER_MS)
                time.sleep(wait_ms / 1000.0)
                continue
            with stats_lock:
                stats["phase_fail"]["create_biz"] += 1
                stats["errors"][f"BIZ:{data.get('code')}:{msg[:100]}"] += 1
            return False
        else:
            with stats_lock:
                stats["phase_fail"]["create"] += 1
                stats["errors"][f"HTTP_{status}:{text[:80]}"] += 1
            return False

    dt = int((time.time() - t0) * 1000)
    with stats_lock:
        stats["phase_latency"]["create"].append(dt)
    if not order_no:
        with stats_lock:
            stats["phase_fail"]["create_no_order"] += 1
        return False

    # Phase 2: 支付
    t0 = time.time()
    try:
        status, text = api_post(f"/payment/pay/{order_no}", token=token)
        dt = int((time.time() - t0) * 1000)
        with stats_lock:
            stats["phase_latency"]["pay"].append(dt)
        if status != 200:
            with stats_lock:
                stats["phase_fail"]["pay"] += 1
                stats["errors"][f"PAY_{status}:{text[:80]}"] += 1
            return False
        phases_ok.append("pay")
    except Exception as e:
        with stats_lock:
            stats["phase_fail"]["pay_ex"] += 1
            stats["errors"][f"PAY_EX:{type(e).__name__}"] += 1
        return False

    # Phase 3: 支付回调（通联异步通知）
    t0 = time.time()
    try:
        amt = f"{float(paid_amount):.2f}" if paid_amount else "18.00"
        notify_body = f"accessOrderId={order_no}&resultCode=SUCCESS&amount={amt}&currency=SGD"
        status, text = api_post("/payment/notify", notify_body, form=True)
        dt = int((time.time() - t0) * 1000)
        with stats_lock:
            stats["phase_latency"]["notify"].append(dt)
        if status != 200 or text.strip() != "success":
            with stats_lock:
                stats["phase_fail"]["notify"] += 1
                stats["errors"][f"NOTIFY_{status}:{text[:80]}"] += 1
            return False
        phases_ok.append("notify")
    except Exception as e:
        with stats_lock:
            stats["phase_fail"]["notify_ex"] += 1
            stats["errors"][f"NOTIFY_EX:{type(e).__name__}"] += 1
        return False

    # Phase 4: 出酒
    t0 = time.time()
    try:
        status, text = api_post(f"/dispense/start/{order_no}", token=token)
        dt = int((time.time() - t0) * 1000)
        with stats_lock:
            stats["phase_latency"]["dispense_start"].append(dt)
        if status != 200:
            with stats_lock:
                stats["phase_fail"]["dispense_start"] += 1
                stats["errors"][f"DISP_{status}:{text[:80]}"] += 1
            return False
        phases_ok.append("dispense_start")
    except Exception as e:
        with stats_lock:
            stats["phase_fail"]["dispense_start_ex"] += 1
            stats["errors"][f"DISP_EX:{type(e).__name__}"] += 1
        return False

    # Phase 5: 出酒回调
    t0 = time.time()
    try:
        cb_body = {"order_id": order_no, "status": "SUCCESS", "actual_ml": volume}
        status, text = api_post("/dispense/callback", cb_body)
        dt = int((time.time() - t0) * 1000)
        with stats_lock:
            stats["phase_latency"]["dispense_callback"].append(dt)
        if status != 200:
            with stats_lock:
                stats["phase_fail"]["dispense_callback"] += 1
                stats["errors"][f"CB_{status}:{text[:80]}"] += 1
            return False
        phases_ok.append("dispense_callback")
    except Exception as e:
        with stats_lock:
            stats["phase_fail"]["dispense_callback_ex"] += 1
            stats["errors"][f"CB_EX:{type(e).__name__}"] += 1
        return False

    with stats_lock:
        stats["volume_count"][volume] += 1
    return True


def main():
    print(f"开始压测: 总订单={TOTAL_ORDERS}, 并发={CONCURRENCY}")
    print(f"瓶位数={len(SLOTS)}, 杯量={VOLUMES}")
    start = time.time()

    completed = 0
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = [pool.submit(process_order, i) for i in range(TOTAL_ORDERS)]
        for fut in as_completed(futures):
            completed += 1
            ok = fut.result()
            with stats_lock:
                stats["total"] += 1
                if ok:
                    stats["success"] += 1
                else:
                    stats["failed"] += 1
            if completed % 5000 == 0:
                elapsed = time.time() - start
                rate = completed / elapsed
                print(f"  进度 {completed}/{TOTAL_ORDERS} ({completed*100//TOTAL_ORDERS}%) "
                      f"成功={stats['success']} 失败={stats['failed']} "
                      f"速率={rate:.0f} req/s 已用时={elapsed:.0f}s")

    elapsed = time.time() - start

    # 生成报告
    report = {
        "summary": {
            "total_orders": stats["total"],
            "success": stats["success"],
            "failed": stats["failed"],
            "success_rate": round(stats["success"] / stats["total"] * 100, 2) if stats["total"] else 0,
            "total_time_sec": round(elapsed, 2),
            "throughput_rps": round(stats["total"] / elapsed, 2) if elapsed else 0,
            "concurrency": CONCURRENCY,
            "volume_distribution": dict(stats["volume_count"]),
        },
        "phase_failures": dict(stats["phase_fail"]),
        "phase_latency_ms": {},
        "top_errors": stats["errors"].most_common(20),
    }
    for phase, lats in stats["phase_latency"].items():
        if lats:
            report["phase_latency_ms"][phase] = {
                "count": len(lats),
                "avg": round(statistics.mean(lats), 2),
                "p50": round(statistics.median(lats), 2),
                "p95": round(sorted(lats)[int(len(lats) * 0.95)], 2) if len(lats) >= 20 else round(max(lats), 2),
                "p99": round(sorted(lats)[int(len(lats) * 0.99)], 2) if len(lats) >= 100 else round(max(lats), 2),
                "max": max(lats),
            }

    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 60)
    print("压测完成")
    print(f"  总订单: {stats['total']}")
    print(f"  成功: {stats['success']} ({report['summary']['success_rate']}%)")
    print(f"  失败: {stats['failed']}")
    print(f"  总耗时: {elapsed:.1f}s")
    print(f"  吞吐: {report['summary']['throughput_rps']} req/s")
    print(f"  报告: {REPORT_FILE}")
    if stats["phase_fail"]:
        print("  阶段失败分布:")
        for k, v in stats["phase_fail"].most_common():
            print(f"    {k}: {v}")


if __name__ == "__main__":
    main()
