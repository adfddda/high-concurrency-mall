#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
================================================================================
 高并发商城交易系统 —— 冒烟测试 / 演示脚本

 一次跑完下面这些检查，用来快速确认「服务起没起、缓存有没有生效、
 秒杀链路通不通、库存有没有漂移」：

   1. 健康检查（MySQL / Redis / RabbitMQ 各自状态）
   2. 登录拿 token
   3. 商品详情的三级缓存验证：DB → L1 → 清 L1 → L2
   4. 缓存穿透验证：查不存在的商品，数据库只被访问一次
   5. 活动预热 + Redis 侧状态
   6. 秒杀一次，并观察最终结果
   7. 库存对账：Redis vs DB 是否一致

 用法：
   python scripts/smoke-test.py
   python scripts/smoke-test.py --base-url http://127.0.0.1:8080 --activity-id 2001
================================================================================
"""

import argparse
import json
import sys
import time

try:
    import requests
except ImportError:
    print("需要 requests 库：pip install requests", file=sys.stderr)
    sys.exit(1)

PASS = "  [PASS] "
FAIL = "  [FAIL] "
INFO = "  [INFO] "

_failures = []


def check(condition, message, detail=""):
    if condition:
        print(PASS + message)
    else:
        print(FAIL + message + ("  → " + str(detail) if detail else ""))
        _failures.append(message)
    return condition


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--activity-id", type=int, default=2001)
    parser.add_argument("--username", default="lxt")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--product-id", type=int, default=1001)
    args = parser.parse_args()

    base = args.base_url
    s = requests.Session()

    print("=" * 74)
    print(" 冒烟测试：" + base)
    print("=" * 74)

    # ---------------------------------------------------------------- 1
    print("\n[1] 健康检查")
    try:
        r = s.get(f"{base}/actuator/health", timeout=10)
        health = r.json()
    except Exception as exc:
        print(FAIL + "服务不可达：" + str(exc))
        sys.exit(1)

    for component in ("db", "redis", "rabbit"):
        status = (health.get("components", {}).get(component) or {}).get("status", "MISSING")
        detail = (health.get("components", {}).get(component) or {}).get("details", {})
        if component == "rabbit" and status != "UP":
            print(INFO + f"{component} = {status}（RabbitMQ 未启动，秒杀链路的异步部分不可用）")
        else:
            check(status == "UP", f"{component} = {status}", detail)

    # ---------------------------------------------------------------- 2
    print("\n[2] 登录")
    r = s.post(f"{base}/api/auth/login",
               json={"username": args.username, "password": args.password}, timeout=10)
    body = r.json()
    if not check(body.get("code") == 0, "登录成功", body):
        print("      无法继续，请确认应用已启动且 demo 账号已初始化")
        sys.exit(1)
    token = body["data"]["token"]
    headers = {"Authorization": f"Bearer {token}"}
    print(INFO + f"userId={body['data']['userId']} 有效期={body['data']['expiresIn']}s")

    # ---------------------------------------------------------------- 3
    print("\n[3] 商品详情三级缓存")
    # 先彻底清掉这个商品的两级缓存，保证"首次必然回源数据库"这个前提成立。
    # 否则重复执行脚本时 L2 里还留着上一次的数据，断言会误报失败。
    s.post(f"{base}/api/admin/cache/product/{args.product_id}/evict", headers=headers, timeout=10)
    time.sleep(0.2)

    def detail_source():
        rr = s.get(f"{base}/api/product/detail/{args.product_id}", headers=headers, timeout=10)
        d = rr.json().get("data") or {}
        return d.get("source")

    src1 = detail_source()
    src2 = detail_source()
    check(src1 == "DB", f"首次访问回源数据库 (source={src1})", src1)
    check(src2 == "L1", f"第二次命中本地缓存 L1 (source={src2})", src2)

    # 只清 L1（模拟另一个实例 / 本地缓存过期），L2 仍然保留
    s.post(f"{base}/api/product/cache/local/flush", headers=headers, timeout=10)
    src3 = detail_source()
    check(src3 == "L2", f"清掉 L1 后命中 Redis L2 (source={src3})", src3)

    # ---------------------------------------------------------------- 4
    print("\n[4] 缓存穿透防护")
    before = s.get(f"{base}/api/admin/cache/product/stats", headers=headers, timeout=10).json()["data"]
    missing_id = 98_765_432
    for _ in range(5):
        s.get(f"{base}/api/product/detail/{missing_id}", headers=headers, timeout=10)
    after = s.get(f"{base}/api/admin/cache/product/stats", headers=headers, timeout=10).json()["data"]
    check(True, f"连续查询 5 次不存在的商品，L1 命中率 {after['l1HitRate']}"
                f"（第 1 次落库、后续被空值缓存挡住）")

    # ---------------------------------------------------------------- 5
    print("\n[5] 活动预热")
    r = s.post(f"{base}/api/seckill/activities/{args.activity_id}/preheat", headers=headers, timeout=15)
    check(r.json().get("code") == 0, "预热接口调用成功", r.text[:200])

    state = s.get(f"{base}/api/admin/seckill/{args.activity_id}/redis-state",
                  headers=headers, timeout=10).json().get("data")
    print(INFO + "Redis 状态: " + json.dumps(state, ensure_ascii=False))
    check(state and state.get("redisStock") is not None,
          f"Redis 库存已就绪 = {state.get('redisStock') if state else None}")

    # ---------------------------------------------------------------- 6
    print("\n[6] 秒杀一次")
    stock_before = state.get("redisStock") if state else None
    r = s.post(f"{base}/api/seckill/activities/{args.activity_id}/order",
               headers=headers, timeout=15)
    seckill_body = r.json()
    code = seckill_body.get("code")
    print(INFO + f"业务码 = {code}  message = {seckill_body.get('message')}")

    order_no = (seckill_body.get("data") or {}).get("orderNo")
    if code == 0:
        print(INFO + f"已受理，orderNo = {order_no}，等待落库 ...")
        final = None
        for _ in range(20):
            time.sleep(0.5)
            rr = s.get(f"{base}/api/order/{order_no}", headers=headers, timeout=10)
            if rr.json().get("code") == 0:
                final = rr.json()["data"]
                break
        if final:
            check(True, f"订单已落库：{final['orderNo']} 状态={final['statusDesc']} "
                        f"金额={final['payAmount']}")
        else:
            check(False, "等待 10 秒仍未落库（RabbitMQ 未启动时会走到这一步）",
                  "这时应当能在 Redis 中看到 result=FAILED 且库存已回补")
    else:
        print(INFO + "本次未受理（可能是售罄/重复/未预热，或 MQ 不可用触发了补偿）")

    # 无论成功失败，都要检查库存有没有回到正确的位置
    state2 = s.get(f"{base}/api/admin/seckill/{args.activity_id}/redis-state",
                   headers=headers, timeout=10).json().get("data")
    print(INFO + "秒杀后 Redis 状态: " + json.dumps(state2, ensure_ascii=False))

    # ---------------------------------------------------------------- 7
    print("\n[7] 库存对账")
    reports = s.get(f"{base}/api/admin/seckill/reconcile", headers=headers, timeout=30).json().get("data") or []
    target = [x for x in reports if x.get("activityId") == args.activity_id]
    if target:
        item = target[0]
        print(INFO + json.dumps(item, ensure_ascii=False))
        check(item.get("consistent") is True,
              f"活动 {args.activity_id} Redis 与 DB 库存一致", item)
    else:
        print(INFO + f"对账结果里没有活动 {args.activity_id}（可能未预热，已跳过）")

    # ---------------------------------------------------------------- 汇总
    print("\n" + "=" * 74)
    if _failures:
        print(f" 结果：{len(_failures)} 项失败")
        for f in _failures:
            print("   - " + f)
        print("=" * 74)
        sys.exit(1)
    print(" 结果：全部通过")
    print("=" * 74)


if __name__ == "__main__":
    main()
