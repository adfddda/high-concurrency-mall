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


def http(method, path, token=None, body=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
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


def call(method, path, token=None, body=None, params=None):
    payload = http(method, path, token, body, params)
    if payload.get("code") != 0:
        raise DemoError(f"{path} -> code={payload.get('code')} {payload.get('message')}")
    return payload.get("data")


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
    detail = call("GET", f"/api/product/detail/{PRODUCT_ID}")
    print(f"{OK} 详情来源 source={detail['source']}（L1 本地 / L2 Redis / DB）")

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
        print(f"{OK} 预约成功")
        winners = call("POST", f"/api/admin/seckill/{LOTTERY_ACTIVITY}/draw", token, params={"ratio": 1.0})
        print(f"{OK} 开奖完成，中签 {winners} 人")
        mine = call("GET", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/reservation", token)
        print(f"{OK} 我的结果：{mine['statusDesc']}，资格码={mine['qualifyCode']}")

        call("POST", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/preheat", token)
        call("POST", f"/api/admin/seckill/{LOTTERY_ACTIVITY}/open", token)
        resp = call("POST", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/order", token,
                    params={"qualifyCode": mine["qualifyCode"]})
        print(f"{OK} 凭码下单受理，订单号 {resp['orderNo']}（排队中，前端可轮询结果）")

    optional("10. 预约抽签", lottery)

    def risk():
        step("11. 风控观测（设备维度）")
        hits = call("GET", "/api/admin/seckill/2001/risk-hits", token)
        print(f"{OK} 活动 2001 的拦截统计：{hits or '暂无拦截'}")
        print("   规则：同一设备在同一活动内只能中一次；拦截记录会落在 t_seckill_risk_hit，")
        print("   可用 `POST /api/seckill/activities/2001/order` 带上 `X-Device-Id` 头自行验证。")

    optional("11. 风控观测", risk)

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
