#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键演示脚本：把「商品浏览 → 秒杀」这条链路完整跑一遍。

用法：
    python scripts/demo.py
    BASE_URL=http://localhost:8081 python scripts/demo.py   # 走网关

设计原则：
  · 只用标准库（urllib），不依赖 requests —— 演示脚本不应该再引入安装步骤；
  · 必选步骤失败就退出（说明环境没起来），可选步骤失败只告警（例如重复运行）；
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

PRODUCT_ID = int(os.getenv("DEMO_PRODUCT_ID", "1001"))
DIRECT_ACTIVITY = int(os.getenv("DEMO_DIRECT_ACTIVITY", "2001"))
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
    return call("POST", "/api/auth/login",
                body={"username": username, "password": PASSWORD})["token"]


def step(title):
    print(f"\n\033[36m=== {title} ===\033[0m")


def optional(title, fn):
    """可选步骤：失败只告警，不打断演示。"""
    step(title + "（可选）")
    try:
        fn()
    except DemoError as e:
        print(f"{WARN} 跳过：{e}")


def poll_result(token, order_no, times=12):
    """秒杀是异步落库：接口只返回"已受理"，结果要靠轮询。"""
    for _ in range(times):
        payload = http("GET", f"/api/seckill/result/{order_no}", token)
        if payload.get("code") == 0:
            return payload.get("data")
        time.sleep(0.5)
    return None


def main():
    step("0. 健康检查")
    health = http("GET", "/actuator/health")
    print(f"{OK} 服务状态：{health.get('status')}")

    step("1. 登录（管理员 = lxt，见 mall.admin.usernames）")
    token = login(USERNAME)
    print(f"{OK} 拿到 JWT：{token[:24]}…")

    step("2. 商品：列表 + 详情（看 source 字段）")
    products = call("GET", "/api/product/list", params={"pageNum": 1, "pageSize": 3})["records"]
    for p in products:
        print(f"   · [{p['id']}] {p['title']} ￥{p['price']}")
    first = call("GET", f"/api/product/detail/{PRODUCT_ID}")
    second = call("GET", f"/api/product/detail/{PRODUCT_ID}")
    print(f"{OK} 第一次 source={first['source']}，第二次 source={second['source']}"
          f"（L1 本地 / L2 Redis / DB；第二次应当命中 L1）")

    step("3. 搜索（ES 优先，不可用自动降级数据库）")
    hits = call("GET", "/api/product/search", params={"keyword": "Xiaomi", "pageNum": 1, "pageSize": 3})
    print(f"{OK} 命中 {hits['total']} 条：{[r['title'] for r in hits['records']][:2]}")

    def direct_seckill():
        step(f"4. 直接抢购（活动 {DIRECT_ACTIVITY}）：预热 → 下单 → 轮询结果")
        call("POST", f"/api/seckill/activities/{DIRECT_ACTIVITY}/preheat", token)
        device = f"demo-device-{int(time.time())}"

        payload = http("POST", f"/api/seckill/activities/{DIRECT_ACTIVITY}/order", token,
                       headers={"X-Device-Id": device})
        code = payload.get("code")
        if code == 20005:
            print(f"{WARN} 该账号已抢过这个活动（code=20005）——想重跑请清库或换账号")
            return
        if code != 0:
            raise DemoError(f"抢购失败 code={code} {payload.get('message')}")

        order_no = payload["data"]["orderNo"]
        print(f"{OK} 已受理 orderNo={order_no}（接口不等落库，立即返回）")

        result = poll_result(token, order_no)
        if result:
            print(f"{OK} 轮询结果：{result.get('statusDesc')}（MQ 消费 + 落库完成）")
        else:
            print(f"{WARN} 仍在排队，可稍后查询 /api/seckill/result/{order_no}")

        step("5. 设备风控：同一台设备换个账号再抢")
        token_b = login("malluser1")
        second_payload = http("POST", f"/api/seckill/activities/{DIRECT_ACTIVITY}/order", token_b,
                              headers={"X-Device-Id": device})
        code_b = second_payload.get("code")
        if code_b == 20012:
            print(f"{OK} 账号 B 用同一设备再抢 → code={code_b} 被拦截")
            print("   限流挡不住的那类黄牛：每个账号都合法，只能靠设备维度收敛")
        else:
            print(f"{WARN} 未被拦截 code={code_b}（设备名额可能已被之前的演示占用）")

        hits_summary = call("GET", f"/api/admin/seckill/{DIRECT_ACTIVITY}/risk-hits", token)
        print(f"{OK} 拦截统计：{hits_summary or '暂无'}")

    optional("4. 直接抢购 + 设备风控", direct_seckill)

    def lottery():
        step(f"6. 预约抽签（活动 {LOTTERY_ACTIVITY}）：预约 → 开奖 → 凭码下单")
        call("POST", f"/api/seckill/activities/{LOTTERY_ACTIVITY}/reservation", token)
        print(f"{OK} 预约成功（幂等，重复预约不会多一条）")

        winners = call("POST", f"/api/admin/seckill/{LOTTERY_ACTIVITY}/draw", token,
                       params={"ratio": 1.0})
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
            print(f"{WARN} 该资格码已被使用过（重复运行演示的正常现象）")
            print("   想完整看到这一步：清空 mall_trade 库后重启应用")
            return
        if payload.get("code") != 0:
            raise DemoError(f"凭码下单失败 code={payload.get('code')} {payload.get('message')}")

        order_no = payload["data"]["orderNo"]
        print(f"{OK} 凭码下单受理，orderNo={order_no}")
        result = poll_result(token, order_no)
        print(f"{OK} 轮询结果：{result.get('statusDesc')}" if result else f"{WARN} 仍在排队")

    optional("6. 预约抽签", lottery)

    step("7. 库存对账：Redis 与数据库是否一致")
    reports = call("GET", "/api/admin/seckill/reconcile", token)
    for r in reports[:6]:
        print(f"   · 活动 {r['activityId']} drift={r['drift']} inflight={r['inflight']} note={r['note']}")
    bad = [r for r in reports if not r["consistent"]]
    print(f"{OK if not bad else WARN} 共 {len(reports)} 个活动，不一致 {len(bad)} 个")

    step("8. 缓存效果")
    stats = call("GET", "/api/admin/cache/product/stats", token)
    print(f"{OK} L1 命中 {stats['l1HitRate']}（请求 {stats['l1RequestCount']} 次）")

    print("\n\033[32m演示结束。\033[0m 打开 http://localhost:3000 看 Grafana 看板，"
          "http://localhost:16686 看链路追踪。")


if __name__ == "__main__":
    try:
        main()
    except DemoError as e:
        print(f"\n\033[31m✘ 演示中断：{e}\033[0m", file=sys.stderr)
        print("请确认应用已启动（java -jar target/high-concurrency-mall.jar --spring.profiles.active=docker）",
              file=sys.stderr)
        sys.exit(1)
