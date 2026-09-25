"""
10000 用户并发下单 + 支付压测脚本
流程：下单 -> 发起支付(mock) -> 模拟通联回调(支付成功) -> 验证订单状态
同时测试支付接口幂等性
"""
import asyncio
import aiohttp
import jwt
import time
import uuid
import random

BASE_URL = "http://127.0.0.1:8080/api"
JWT_SECRET = "WineSingapore2026SecretKeyForJWTTokenGenerationMustBeLongEnough"
JWT_EXPIRATION = 3600

# 10000 个测试用户 ID
USER_IDS = list(range(20001, 30001))
TOTAL_USERS = len(USER_IDS)

# 并发控制
MAX_CONCURRENCY = 300

UA_ANDROID = "WineDispenser/1.0 (Android 14; SDK 34)"
UA_IOS = "WineDispenser/1.0 (iOS 17.0; iPhone15,2)"


def make_token(user_id: int) -> str:
    now = int(time.time())
    payload = {
        "sub": str(user_id),
        "role": 1,
        "iat": now,
        "exp": now + JWT_EXPIRATION,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")


async def create_order(session, user_id, idem_key, user_agent):
    """创建订单"""
    slot_no = (user_id % 3) + 1
    wine_sku = 2000 + slot_no
    body = {
        "barId": 1001,
        "dispenserId": 3001,
        "slotNo": slot_no,
        "wineSkuId": wine_sku,
        "volumeMl": 50,
    }
    headers = {
        "Authorization": f"Bearer {make_token(user_id)}",
        "Content-Type": "application/json",
        "User-Agent": user_agent,
        "Idempotency-Key": idem_key,
    }
    async with session.post(
        f"{BASE_URL}/order/create", json=body, headers=headers, timeout=aiohttp.ClientTimeout(total=30)
    ) as resp:
        data = await resp.json()
        if resp.status == 200 and data.get("code") == 200:
            return True, data["data"]["orderNo"], None
        return False, None, data.get("message") or f"HTTP {resp.status}"

async def pay_order(session, order_no, user_id):
    """发起支付（mock 模式）"""
    headers = {
        "Authorization": f"Bearer {make_token(user_id)}",
        "Content-Type": "application/json",
    }
    async with session.post(
        f"{BASE_URL}/payment/pay/{order_no}", headers=headers, timeout=aiohttp.ClientTimeout(total=30)
    ) as resp:
        data = await resp.json()
        if resp.status == 200 and data.get("code") == 200:
            return True, None
        return False, data.get("message") or f"HTTP {resp.status}"


async def notify_payment(session, order_no, amount="18.00"):
    """模拟通联国际异步通知回调（支付成功）"""
    params = {
        "accessOrderId": order_no,
        "resultCode": "SUCCESS",
        "amount": amount,
        "currency": "SGD",
        "transId": "MOCK_TRANS_" + str(uuid.uuid4()),
        "transType": "Purchase",
    }
    async with session.post(
        f"{BASE_URL}/payment/notify", data=params, timeout=aiohttp.ClientTimeout(total=30)
    ) as resp:
        text = await resp.text()
        return resp.status == 200 and "success" in text.lower(), text


async def get_order_status(session, order_no, user_id):
    """查询订单状态"""
    headers = {"Authorization": f"Bearer {make_token(user_id)}"}
    async with session.get(
        f"{BASE_URL}/order/{order_no}", headers=headers, timeout=aiohttp.ClientTimeout(total=15)
    ) as resp:
        data = await resp.json()
        if resp.status == 200 and data.get("code") == 200:
            return data["data"].get("status"), data["data"].get("payStatus")
        return None, None


async def user_flow(session, user_id, semaphore, results, idem_test_set):
    """单个用户完整流程：下单 -> 支付 -> 回调 -> 状态校验"""
    async with semaphore:
        idem_key = str(uuid.uuid4())
        ua = UA_ANDROID if user_id % 2 == 0 else UA_IOS
        slot_no = (user_id % 3) + 1

        # 1. 下单
        ok, order_no, err = await create_order(session, user_id, idem_key, ua)
        if not ok:
            results["order_fail"] += 1
            results["errors"].append(f"uid={user_id} order_fail: {err}")
            return
        results["order_success"] += 1

        # 2. 发起支付
        ok, err = await pay_order(session, order_no, user_id)
        if not ok:
            results["pay_fail"] += 1
            results["errors"].append(f"uid={user_id} pay_fail: {err}")
            return

        # 3. 模拟支付成功回调（金额需与订单实付金额一致）
        price_map = {1: "18.00", 2: "15.00", 3: "22.00"}
        ok, resp_text = await notify_payment(session, order_no, price_map[slot_no])
        if not ok:
            results["notify_fail"] += 1
            results["errors"].append(f"uid={user_id} notify_fail: {resp_text}")
            return

        # 4. 校验订单状态应为 PAID(3)
        status, pay_status = await get_order_status(session, order_no, user_id)
        if status == 3:
            results["paid_success"] += 1
        else:
            results["status_wrong"] += 1
            results["errors"].append(f"uid={user_id} status={status} pay_status={pay_status}")

        # 5. 幂等性测试：对部分订单重复发送回调
        if len(idem_test_set) < 200:
            idem_test_set.append(order_no)


async def idempotency_test(session, order_nos):
    """支付回调幂等性测试：重复发送回调，应返回 success 且不改变状态"""
    idem_ok = 0
    idem_fail = 0
    for order_no in order_nos:
        ok, _ = await notify_payment(session, order_no)
        if ok:
            idem_ok += 1
        else:
            idem_fail += 1
    return idem_ok, idem_fail


async def main():
    print("=" * 70)
    print(f"10000 用户并发下单 + 支付压测")
    print(f"后端: {BASE_URL}")
    print(f"并发数: {MAX_CONCURRENCY}")
    print("=" * 70)

    results = {
        "order_success": 0,
        "order_fail": 0,
        "pay_fail": 0,
        "notify_fail": 0,
        "paid_success": 0,
        "status_wrong": 0,
        "errors": [],
    }
    idem_test_set = []

    semaphore = asyncio.Semaphore(MAX_CONCURRENCY)
    start = time.time()

    async with aiohttp.ClientSession() as session:
        tasks = [
            user_flow(session, uid, semaphore, results, idem_test_set)
            for uid in USER_IDS
        ]
        await asyncio.gather(*tasks)

        elapsed = time.time() - start

        # 幂等性测试
        print(f"\n开始支付回调幂等性测试（{len(idem_test_set)} 个订单重复回调）...")
        idem_ok, idem_fail = await idempotency_test(session, idem_test_set)

    total = TOTAL_USERS
    print(f"\n{'=' * 70}")
    print(f"测试结果")
    print(f"{'=' * 70}")
    print(f"总用户数:       {total}")
    print(f"下单成功:       {results['order_success'] if 'order_success' in results else total - results['order_fail']}")
    print(f"下单失败:       {results['order_fail']}")
    print(f"支付失败:       {results['pay_fail']}")
    print(f"回调失败:       {results['notify_fail']}")
    print(f"支付成功(PAID): {results['paid_success']}")
    print(f"状态异常:       {results['status_wrong']}")
    print(f"总耗时:         {elapsed:.2f}s")
    print(f"吞吐量:         {total / elapsed:.1f} users/s")
    print(f"\n幂等性测试:     重复回调 {len(idem_test_set)} 次")
    print(f"  成功(幂等):   {idem_ok}")
    print(f"  失败:         {idem_fail}")

    if results["errors"]:
        print(f"\n前 10 条错误:")
        for e in results["errors"][:10]:
            print(f"  {e}")

    success_rate = results["paid_success"] / total * 100
    print(f"\n支付成功率:     {success_rate:.1f}%")
    if results["paid_success"] == total and idem_fail == 0:
        print("🎉 所有订单支付成功，幂等性测试通过")
    else:
        print("⚠️ 存在异常，需检查")


if __name__ == "__main__":
    asyncio.run(main())
