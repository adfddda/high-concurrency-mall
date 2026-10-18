#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键演示脚本：把整条交易链路跑一遍，每一步都打印关键返回值。

用法：
    python scripts/demo.py
    BASE_URL=http://localhost:8081 python scripts/demo.py   # 走网关

设计原则：
  · 只用标准库（urllib），不依赖 requests —— 演示脚本不应该再引入安装步骤；
  · 必选步骤失败就退出（说明环境没起来），可选步骤失败只告警（例如活动已开始，抽签没法预约）；
  · 每一步都打印"为什么看这个返回值"，让演示是**可讲解的**，而不只是一串请求。
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.getenv("BASE_URL", "http://localhost:8080").rstrip("/")
USERNAME = os.getenv("DEMO_USER", "lxt")
PASSWORD = os.getenv("DEMO_PASSWORD", "123456")

SKU_ID = int(os.getenv("DEMO_SKU_ID", "3001"))
PRODUCT_ID = int(os.getenv("DEMO_PRODUCT_ID", "1001"))
COUPON_TEMPLATE = int(os.getenv("DEMO_COUPON_TEMPLATE", "4001"))
LOTTERY_ACTIVITY = int(os.getenv("DEMO_LOTTERY_ACTIVITY", "2006"))

OK, WARN = "\033[32m✔\033[0m", "\033[33m!\033[0m"


class DemoError(RuntimeError):
    pass


def http(method, path, token=None, body=None, params=None, headers=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        raise DemoError(f"HTTP {e.code}: {raw}") from e
    except urllib.error.URLError as e:
        raise DemoError(f"无法连接 {url}：{e.reason}") from e
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw}


def call(method, path, token=None, body=None, params=None, headers=None):
    payload = http(method, path, token, body, params, headers)
    if payload.get("code") != 0:
        raise DemoError(f"{path} -> code={payload.get('code')} {payload.get('message')}")
    return payload.get("data")


def login(username):
    """登录取 token（演示里需要两个不同用户来演风控）。"""
    data = call("POST", "/api/auth/login", body={"username": username, "password": PASSWORD})
    return data["token"]


def step(title):
    print(f"\n\033[36m=== {title} ===\033[0m")


def optional(title, fn):
    """可选步骤：失败只告警，不打断演示。"""
    step(title + "（可选）")
    try:
        fn()
    except DemoError as e:
        print(f"{WARN} 跳过：{e}")


def main():
    step("0. 健康检查")
    health = http("GET", "/actuator/health")
    print(f"{OK} 服务状态：{health.get('status')}")
    if health.get("status") != "UP":
        print(f"{WARN} 有组件不健康，继续演示（部分步骤可能失败）")

    step("1. 登录（管理员 = lxt，见 mall.admin.usernames）")
    token = call("POST", "/api/auth/login", body={"username": USERNAME, "password": PASSWORD})["token"]
    print(f"{OK} 拿到 JWT：{token[:24]}…")

    step("2. 商品：列表 + 详情（注意 source 字段）")
    products = call("GET", "/api/product/list", params={"pageNum": 1, "pageSize": 3})["records"]
    for p in products:
        print(f"   · [{p['id']}] {p['title']} ￥{p['price']}")
    detail_first = call("GET", f"/api/product/detail/{PRODUCT_ID}")
    detail_second = call("GET", f"/api/product/detail/{PRODUCT_ID}")
    print(f"{OK} 第一次 source={detail_first['source']}，第二次 source={detail_second['source']}"
          f"（L1 本地 / L2 Redis / DB；第二次应当命中 L1）")

    step("3. 搜索（ES 优先，不可用自动降级数据库）")
    hits = call("GET", "/api/product/search", params={"keyword": "Xiaomi", "pageNum": 1, "pageSize": 3})
    print(f"{OK} 命中 {hits['total']} 条：{[r['title'] for r in hits['records']][:2]}")

    step("4. SKU 与购物车")
    skus = call("GET", "/api/trade/skus", params={"productId": PRODUCT_ID})
    print(f"{OK} 商品有 {len(skus)} 个规格，取 {skus[0]['skuCode']} ￥{skus[0]['price']}")
    call("POST", "/api/trade/cart", token, params={"skuId": SKU_ID, "quantity": 2})
    cart = call("GET", "/api/trade/cart", token)
    print(f"{OK} 购物车 {len(cart)} 项，合计 ￥{sum(float(i['subtotal']) for i in cart):.2f}"
          f"（价格是实时的，不是加购时快照）")

    step("5. 领券（限量发放，条件更新防超发）")
    coupon = call("POST", f"/api/trade/coupons/{COUPON_TEMPLATE}/claim", token)
    print(f"{OK} 领到 {coupon['couponNo']}（满 {coupon['thresholdAmount']} 减 {coupon['discountAmount']}）")

    step("6. 普通下单：同步事务扣 SKU 库存 + 核销券")
    order = call("POST", "/api/trade/orders", token,
                 body={"skuId": SKU_ID, "quantity": 2, "couponId": coupon["id"]})
    print(f"{OK} 订单 {order['orderNo']} 应付 ￥{order['payAmount']}（已减 ￥{coupon['discountAmount']}）")

    step("7. 支付：创建支付单 → 模拟渠道回调（幂等）")
    payment = call("POST", f"/api/trade/orders/{order['orderNo']}/pay", token)
    print(f"{OK} 支付单 {payment['paymentNo']} 金额 ￥{payment['amount']}")
    # 回调是渠道调用的，不需要用户 token
    call("POST", "/api/trade/payment/callback", None,
         params={"paymentNo": payment["paymentNo"], "channelTradeNo": "DEMO-" + payment["paymentNo"],
                 "amount": payment["amount"]})
    pay_cb = http("POST", "/api/trade/payment/callback", None,
                  params={"paymentNo": payment["paymentNo"], "channelTradeNo": "DEMO-RETRY",
                          "amount": payment["amount"]})
    print(f"{OK} 重复回调返回 code={pay_cb.get('code')}（幂等，不会重复记账）")

    step("8. 履约：发货 → 确认收货（状态机 PAID→SHIPPED→FINISHED）")
    call("POST", f"/api/admin/orders/{order['orderNo']}/ship", token)
    call("POST", f"/api/order/{order['orderNo']}/confirm", token)
    final = call("GET", f"/api/order/{order['orderNo']}", token)
    print(f"{OK} 订单最终状态：{final['statusDesc']}")

    step("9. 第二单：支付后退款，券自动退回")
    order2 = call("POST", "/api/trade/orders", token, body={"skuId": SKU_ID, "quantity": 1})
    pay2 = call("POST", f"/api/trade/orders/{order2['orderNo']}/pay", token)
    call("POST", "/api/trade/payment/callback", None,
         params={"paymentNo": pay2["paymentNo"], "channelTradeNo": "DEMO2-" + pay2["paymentNo"],
                 "amount": pay2["amount"]})
    refund = call("POST", f"/api/trade/orders/{order2['orderNo']}/refund", token,
                  params={"reason": "演示退款"})
    print(f"{OK} 退款单 {refund['refundNo']} ￥{refund['amount']}，订单已置为已退款")

    def lottery():
        step("10. 预约抽签：预约 → 开奖 → 资格码 → 凭码下单")
        call("POST", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/reservation", token)
        print(f"{OK} 预约成功（幂等，重复预约不会多一条）")

        winners = call("POST", f"/api/admin/seckill/{LOTTERY_ACTIVITY}/draw", token, params={"ratio": 1.0})
        print(f"{OK} 开奖：本次中签 {winners} 人"
              + ("（该活动此前已开奖，重复开奖不会产生第二批）" if winners == 0 else ""))

        mine = call("GET", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/reservation", token)
        if not mine or mine.get("status") != 1:
            print(f"{WARN} 当前状态：{mine.get('statusDesc') if mine else '未预约'}，跳过下单")
            return
        print(f"{OK} 我的结果：{mine['statusDesc']}，资格码={mine['qualifyCode']}")

        call("POST", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/preheat", token)
        call("POST", f"/api/admin/seckill/{LOTTERY_ACTIVITY}/open", token)

        payload = http("POST", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/order", token,
                       params={"qualifyCode": mine["qualifyCode"]})
        if payload.get("code") == 20009:
            print(f"{WARN} 该资格码已被使用过（重复运行演示的正常现象），跳过下单")
            print("   想完整看到这一步：清空 mall_trade 库后重启应用，或用一个新的抽签活动")
            return
        if payload.get("code") != 0:
            raise DemoError(f"凭码下单失败 code={payload.get('code')} {payload.get('message')}")
        resp = payload["data"]
        print(f"{OK} 凭码下单受理，订单号 {resp['orderNo']}")

        # 秒杀是异步落库：接口只返回"已受理"，结果要靠轮询（前端就是这么做的）
        result = None
        for _ in range(12):
            poll = http("GET", f"/api/seckill/result/{resp['orderNo']}", token)
            if poll.get("code") == 0:
                result = poll.get("data")
                break
            time.sleep(0.5)
        if result:
            print(f"{OK} 轮询结果：{result.get('statusDesc')}（MQ 消费 + 落库完成）")
        else:
            print(f"{WARN} 仍在排队，可稍后查询 /api/seckill/result/{resp['orderNo']}")

    optional("10. 预约抽签", lottery)

    def risk():
        step("11. 设备风控：同一设备换个账号再抢会被拦")
        call("POST", "/api/seckill/activities/2001/preheat", token)
        device = f"demo-device-{int(time.time())}"

        first = http("POST", "/api/seckill/activities/2001/order", token,
                     headers={"X-Device-Id": device})
        if first.get("code") != 0:
            print(f"{WARN} 首个请求返回 code={first.get('code')}（{first.get('message')}）——"
                  f"该账号可能已抢过此活动，跳过风控演示")
            return
        print(f"{OK} 账号 A 用设备 {device} 抢到，设备名额已占用")

        token_b = login("malluser1")
        second = http("POST", "/api/seckill/activities/2001/order", token_b,
                      headers={"X-Device-Id": device})
        code = second.get("code")
        if code == 20012:
            print(f"{OK} 账号 B 用同一设备再抢 → code={code} 被拦截")
            print("   这就是限流挡不住的那类黄牛：每个账号都合法，只能靠设备维度收敛")
        else:
            print(f"{WARN} 未被拦截 code={code}（设备名额可能已被之前的演示占用）")

        hits = call("GET", "/api/admin/seckill/2001/risk-hits", token)
        print(f"{OK} 拦截统计：{hits or '暂无'}")

    optional("11. 设备风控", risk)

    step("12. 库存对账：Redis 与数据库是否一致")
    reports = call("GET", "/api/admin/seckill/reconcile", token)
    for r in reports[:5]:
        print(f"   · 活动 {r['activityId']} drift={r['drift']} inflight={r['inflight']} note={r['note']}")
    bad = [r for r in reports if not r["consistent"]]
    print(f"{OK if not bad else WARN} 共 {len(reports)} 个活动，不一致 {len(bad)} 个")

    print("\n\033[32m演示结束。\033[0m 打开 http://localhost:3000 看 Grafana 看板，"
          "http://localhost:16686 看链路追踪。")


if __name__ == "__main__":
    try:
        main()
    except DemoError as e:
        print(f"\n\033[31m✘ 演示中断：{e}\033[0m", file=sys.stderr)
        print("请确认应用已启动（java -jar target/high-concurrency-mall.jar --spring.profiles.active=local）",
              file=sys.stderr)
        sys.exit(1)
