#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
================================================================================
 高并发商城交易系统 —— 秒杀压测脚本

 用法：
   # 场景一：容量压测（应用需以 benchmark profile 启动，限流关闭）
   python scripts/load-test.py --scenario capacity \
       --activity-id 2001 --users 500 --concurrency 400 --requests 20000

   # 场景二：限流验证（应用以 local profile 启动，限流开启）
   python scripts/load-test.py --scenario ratelimit \
       --activity-id 2005 --users 200 --concurrency 100 --requests 5000

 设计说明（这几条决定了压测结果是否有意义）：
   1. 压测机与应用在同一台机器上时，压测客户端本身就是瓶颈之一。
      脚本会先做一轮小规模预热，避免把 JIT 预热与连接建立的开销算进结果。
   2. 「一人一单」要求每个请求用不同的用户，否则结果会被
      "重复购买"短路，测出来的根本不是扣减链路的能力。
   3. 限流是 IP + 用户双维度。单机压测时所有请求来自同一个 IP，
      IP 维度会先于用户维度触发——所以容量压测必须关掉限流，
      否则你测的是限流器的能力，不是系统的能力。
   4. 被拒绝的请求（售罄/重复/限流）同样消耗网络与解析成本，
      统计 QPS 时要把它们算进去，同时单独列出各业务码的分布。
================================================================================
"""

import argparse
import json
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

try:
    import requests
except ImportError:
    print("需要 requests 库：pip install requests", file=sys.stderr)
    sys.exit(1)

# 业务码（与 com.lxt.mall.common.api.ResultCode 保持一致）
CODE_SUCCESS = 0
CODE_RATE_LIMITED = 42900
CODE_SOLD_OUT = 20004
CODE_REPEAT = 20005
CODE_NOT_STARTED = 20002
CODE_ENDED = 20003
CODE_NOT_PREPARED = 20006
CODE_SUBMIT_FAILED = 20008

CODE_LABELS = {
    CODE_SUCCESS: "受理成功 (排队中)",
    CODE_RATE_LIMITED: "被限流",
    CODE_SOLD_OUT: "已售罄",
    CODE_REPEAT: "重复购买",
    CODE_NOT_STARTED: "活动未开始",
    CODE_ENDED: "活动已结束",
    CODE_NOT_PREPARED: "活动未预热",
    CODE_SUBMIT_FAILED: "提交失败",
}

# 复用连接：单机压测时每个请求新建 TCP 连接会把端口耗尽，
# 而且大量时间花在握手而不是业务逻辑上。
_local = threading.local()


def session() -> requests.Session:
    if not hasattr(_local, "s"):
        s = requests.Session()
        adapter = requests.adapters.HTTPAdapter(
            pool_connections=64, pool_maxsize=64, max_retries=0)
        s.mount("http://", adapter)
        s.mount("https://", adapter)
        _local.s = s
    return _local.s


@dataclass
class Stats:
    """压测结果统计。"""
    latencies_us: List[int] = field(default_factory=list)
    code_counts: Dict[int, int] = field(default_factory=dict)
    http_errors: int = 0
    accepted_order_nos: List[str] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def record(self, latency_us: int, code: Optional[int]):
        with self._lock:
            self.latencies_us.append(latency_us)
            if code is None:
                self.http_errors += 1
            else:
                self.code_counts[code] = self.code_counts.get(code, 0) + 1

    def record_accepted(self, order_no: str):
        with self._lock:
            self.accepted_order_nos.append(order_no)

    def percentile(self, p: float) -> float:
        if not self.latencies_us:
            return 0.0
        ordered = sorted(self.latencies_us)
        idx = min(len(ordered) - 1, int(len(ordered) * p / 100.0))
        return ordered[idx] / 1000.0


def login_all(base_url: str, users: int, password: str, workers: int) -> List[str]:
    """批量登录，拿到 token 池。

    登录接口有令牌桶限流（默认 10 容量 / 0.5 每秒）。
    如果压测时把它打开，200 个用户要花将近 7 分钟才能登完——
    所以容量压测请在 benchmark profile 下进行（限流整体关闭）。
    这里仍然做了退避重试，保证在限流开启时也能跑完，只是慢。
    """
    print(f"[1/4] 批量登录 {users} 个用户 ...")
    usernames = ["lxt"] + [f"malluser{i}" for i in range(1, users)]

    tokens: List[str] = []
    lock = threading.Lock()
    start = time.time()

    def do_login(username: str) -> Optional[str]:
        s = session()
        for attempt in range(6):
            try:
                r = s.post(f"{base_url}/api/auth/login",
                           json={"username": username, "password": password},
                           timeout=10)
                body = r.json()
                if body.get("code") == CODE_SUCCESS:
                    return body["data"]["token"]
                if body.get("code") == CODE_RATE_LIMITED:
                    time.sleep(1.0 + attempt)
                    continue
                return None
            except Exception:
                time.sleep(0.5)
        return None

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(do_login, u): u for u in usernames}
        for future in as_completed(futures):
            token = future.result()
            if token:
                with lock:
                    tokens.append(token)

    elapsed = time.time() - start
    print(f"      登录成功 {len(tokens)}/{len(usernames)} 个，耗时 {elapsed:.1f}s")
    if not tokens:
        print("      [错误] 一个都没登上，检查应用是否启动、demo 账号是否已初始化", file=sys.stderr)
        sys.exit(2)
    return tokens


def seckill_once(base_url: str, activity_id: int, token: str) -> Tuple[int, Optional[int], Optional[str]]:
    """发起一次秒杀，返回 (耗时微秒, 业务码, 订单号)。"""
    s = session()
    started = time.perf_counter()
    try:
        r = s.post(f"{base_url}/api/seckill/activities/{activity_id}/order",
                   headers={"Authorization": f"Bearer {token}"},
                   timeout=10)
        elapsed_us = int((time.perf_counter() - started) * 1_000_000)
        body = r.json()
        code = body.get("code")
        order_no = (body.get("data") or {}).get("orderNo") if code == CODE_SUCCESS else None
        return elapsed_us, code, order_no
    except Exception:
        return int((time.perf_counter() - started) * 1_000_000), None, None


def warmup(base_url: str, activity_id: int, tokens: List[str], rounds: int = 200):
    """预热：把 JIT、连接池、Redis 连接、Tomcat 线程都跑热。

    不做预热的话，前几百个请求会明显偏慢，把 P99 拉得很难看，
    而那个数字反映的是"冷启动"而不是"稳态能力"。
    """
    print(f"[2/4] 预热 {rounds} 次请求 ...")
    with ThreadPoolExecutor(max_workers=32) as pool:
        futures = [
            pool.submit(seckill_once, base_url, activity_id, tokens[i % len(tokens)])
            for i in range(rounds)
        ]
        for f in as_completed(futures):
            f.result()
    print("      预热完成")


def run_load(base_url: str, activity_id: int, tokens: List[str],
             concurrency: int, requests_count: int) -> Tuple[Stats, float]:
    print(f"[3/4] 发起 {requests_count} 个请求，并发 {concurrency} ...")
    stats = Stats()
    stop_flag = threading.Event()

    # 用「固定并发 + 循环取 token」而不是「每个请求一个线程」：
    # 线程数一旦超过 CPU 核数几十倍，上下文切换就会成为主要成本，
    # 测出来的是调度器的能力而不是服务端的能力。
    counter_lock = threading.Lock()
    issued = [0]

    def worker():
        while not stop_flag.is_set():
            with counter_lock:
                if issued[0] >= requests_count:
                    return
                idx = issued[0]
                issued[0] += 1
            token = tokens[idx % len(tokens)]
            latency, code, order_no = seckill_once(base_url, activity_id, token)
            stats.record(latency, code)
            if order_no:
                stats.record_accepted(order_no)

    started = time.time()
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - started
    stop_flag.set()

    return stats, elapsed


def verify_results(base_url: str, token: str, order_nos: List[str],
                   activity_id: int, wait_seconds: int = 15) -> Dict[str, object]:
    """轮询已受理订单的最终结果，验证 MQ → 落库这条链路是否真的通了。"""
    print(f"[4/4] 等待 {wait_seconds}s 后校验订单落库情况（抽样最多 200 单）...")
    time.sleep(wait_seconds)

    sample = order_nos[:200]
    success = failed = queueing = 0
    for order_no in sample:
        try:
            r = session().get(f"{base_url}/api/order/{order_no}",
                              headers={"Authorization": f"Bearer {token}"}, timeout=10)
            body = r.json()
            if body.get("code") == 0:
                success += 1
            elif body.get("code") == 20007:
                queueing += 1
            else:
                failed += 1
        except Exception:
            failed += 1

    report = {"sampled": len(sample), "created": success, "queueing": queueing, "failed": failed}

    # 库存对账：这是判断"到底有没有超卖"的唯一权威依据
    try:
        r = session().get(f"{base_url}/api/admin/seckill/reconcile",
                          headers={"Authorization": f"Bearer {token}"}, timeout=30)
        reports = r.json().get("data") or []
        report["reconcile"] = [x for x in reports if x.get("activityId") == activity_id]
    except Exception as exc:
        report["reconcile_error"] = str(exc)

    try:
        r = session().get(f"{base_url}/api/admin/seckill/{activity_id}/redis-state",
                          headers={"Authorization": f"Bearer {token}"}, timeout=10)
        report["redis_state"] = r.json().get("data")
    except Exception as exc:
        report["redis_state_error"] = str(exc)

    return report


def print_report(stats: Stats, elapsed: float, requests_count: int, result: Dict[str, object]):
    print()
    print("=" * 78)
    print(" 压测结果")
    print("=" * 78)
    total = len(stats.latencies_us)
    qps = total / elapsed if elapsed > 0 else 0
    print(f"  总请求数        : {total}")
    print(f"  总耗时          : {elapsed:.2f} s")
    print(f"  吞吐 (QPS)      : {qps:,.0f}")
    print(f"  平均延迟        : {statistics.mean(stats.latencies_us) / 1000:.2f} ms" if total else "")
    print(f"  P50 / P95 / P99 : {stats.percentile(50):.2f} / "
          f"{stats.percentile(95):.2f} / {stats.percentile(99):.2f} ms")
    print(f"  最大延迟        : {max(stats.latencies_us) / 1000:.2f} ms" if total else "")
    print(f"  HTTP 层异常     : {stats.http_errors}")
    print()
    print("  业务码分布：")
    for code, count in sorted(stats.code_counts.items(), key=lambda kv: -kv[1]):
        label = CODE_LABELS.get(code, f"未知码 {code}")
        print(f"    {code:>6}  {label:<20} {count:>8}  ({count * 100.0 / total:5.1f}%)")
    print()
    print("  订单与一致性校验：")
    print(f"    抽样订单数      : {result.get('sampled')}")
    print(f"    已落库          : {result.get('created')}")
    print(f"    仍在排队        : {result.get('queueing')}")
    print(f"    查不到          : {result.get('failed')}")
    if result.get("redis_state"):
        print(f"    Redis 状态      : {json.dumps(result['redis_state'], ensure_ascii=False)}")
    for item in (result.get("reconcile") or []):
        drift = item.get("drift")
        flag = "一致" if item.get("consistent") else f"漂移 {drift}"
        print(f"    库存对账        : redis={item.get('redisStock')} db={item.get('dbAvailableStock')} "
              f"inflight={item.get('inflight')} → {flag}")
    if result.get("reconcile_error"):
        print(f"    对账调用失败    : {result['reconcile_error']}")
    print("=" * 78)


def detail_once(base_url: str, product_id: int, token: str) -> Tuple[int, Optional[int], Optional[str]]:
    """发起一次商品详情查询，返回 (耗时微秒, 业务码, 数据来源 L1/L2/DB)。"""
    s = session()
    started = time.perf_counter()
    try:
        r = s.get(f"{base_url}/api/product/detail/{product_id}",
                  headers={"Authorization": f"Bearer {token}"}, timeout=10)
        elapsed_us = int((time.perf_counter() - started) * 1_000_000)
        body = r.json()
        code = body.get("code")
        source = (body.get("data") or {}).get("source")
        return elapsed_us, code, source
    except Exception:
        return int((time.perf_counter() - started) * 1_000_000), None, None


def run_cache_load(base_url: str, product_ids: List[int], tokens: List[str],
                   concurrency: int, requests_count: int) -> Tuple[Stats, float, Dict[str, int]]:
    """商品详情多级缓存压测。

    <p>这一组数字回答的问题是：**多级缓存到底把数据库挡住了多少**。
    单看 QPS 是不够的——必须同时给出 L1/L2/DB 的来源分布，
    否则无法区分"缓存生效"和"数据库本身够快"。
    """
    print(f"[3/4] 发起 {requests_count} 个商品详情请求，并发 {concurrency} ...")
    stats = Stats()
    sources: Dict[str, int] = {}
    source_lock = threading.Lock()

    counter_lock = threading.Lock()
    issued = [0]

    def worker():
        while True:
            with counter_lock:
                if issued[0] >= requests_count:
                    return
                idx = issued[0]
                issued[0] += 1
            product_id = product_ids[idx % len(product_ids)]
            token = tokens[idx % len(tokens)]
            latency, code, source = detail_once(base_url, product_id, token)
            stats.record(latency, code)
            if source:
                with source_lock:
                    sources[source] = sources.get(source, 0) + 1

    started = time.time()
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - started
    return stats, elapsed, sources


def print_cache_report(stats: Stats, elapsed: float, sources: Dict[str, int],
                       base_url: str, token: str):
    total = len(stats.latencies_us)
    qps = total / elapsed if elapsed > 0 else 0
    print()
    print("=" * 78)
    print(" 商品详情 · 多级缓存压测结果")
    print("=" * 78)
    print(f"  总请求数        : {total}")
    print(f"  总耗时          : {elapsed:.2f} s")
    print(f"  吞吐 (QPS)      : {qps:,.0f}")
    print(f"  平均延迟        : {statistics.mean(stats.latencies_us) / 1000:.2f} ms" if total else "")
    print(f"  P50 / P95 / P99 : {stats.percentile(50):.3f} / "
          f"{stats.percentile(95):.3f} / {stats.percentile(99):.3f} ms")
    print(f"  最大延迟        : {max(stats.latencies_us) / 1000:.2f} ms" if total else "")
    print(f"  HTTP 层异常     : {stats.http_errors}")
    print()
    print("  数据来源分布（这是判断缓存是否真的生效的唯一依据）：")
    source_total = sum(sources.values()) or 1
    for name in ("L1", "L2", "DB"):
        count = sources.get(name, 0)
        print(f"    {name:<4} {count:>8}  ({count * 100.0 / source_total:5.1f}%)")
    print()
    try:
        stats_body = session().get(f"{base_url}/api/admin/cache/product/stats",
                                   headers={"Authorization": f"Bearer {token}"},
                                   timeout=10).json().get("data")
        if stats_body:
            print("  服务端 L1 统计：")
            for key, value in stats_body.items():
                print(f"    {key:<20} {value}")
    except Exception as exc:
        print(f"  [警告] 读取缓存统计失败：{exc}")
    print("=" * 78)


def main():
    parser = argparse.ArgumentParser(description="高并发商城交易系统 —— 压测")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--activity-id", type=int, default=2001)
    parser.add_argument("--product-ids", default="1001,1002,1003,1004,1005",
                        help="cache 场景下轮换查询的商品 ID")
    parser.add_argument("--users", type=int, default=500, help="参与压测的不同用户数")
    parser.add_argument("--concurrency", type=int, default=300)
    parser.add_argument("--requests", type=int, default=20000)
    parser.add_argument("--password", default="123456")
    parser.add_argument("--login-workers", type=int, default=32)
    parser.add_argument("--scenario", choices=["capacity", "ratelimit", "cache"], default="capacity")
    parser.add_argument("--warmup", type=int, default=200)
    parser.add_argument("--no-verify", action="store_true")
    parser.add_argument("--verify-wait", type=int, default=15)
    args = parser.parse_args()

    print("=" * 78)
    print(f" 场景        : {args.scenario}")
    print(f" 目标        : {args.base_url}"
          + (f"  活动 {args.activity_id}" if args.scenario != "cache" else ""))
    print(f" 并发 / 请求 : {args.concurrency} / {args.requests}")
    print(f" 用户数      : {args.users}")
    if args.scenario == "ratelimit":
        print(" 提示        : 本场景用于验证限流是否生效，被限流的比例应当显著 > 0")
    elif args.scenario == "capacity":
        print(" 提示        : 本场景用于测容量，应用必须以 benchmark profile 启动（限流关闭）")
    else:
        print(" 提示        : 本场景测商品详情的多级缓存，重点看【数据来源分布】而非单纯的 QPS")
    print("=" * 78)

    tokens = login_all(args.base_url, args.users, args.password, args.login_workers)

    if args.scenario == "cache":
        product_ids = [int(x) for x in args.product_ids.split(",") if x.strip()]
        # 先把缓存清干净，让来源分布反映真实链路而不是上一轮的残留
        for pid in product_ids:
            try:
                session().post(f"{args.base_url}/api/admin/cache/product/{pid}/evict",
                               headers={"Authorization": f"Bearer {tokens[0]}"}, timeout=10)
            except Exception:
                pass
        if args.warmup > 0:
            print(f"[2/4] 预热 {args.warmup} 次请求 ...")
            with ThreadPoolExecutor(max_workers=32) as pool:
                futures = [pool.submit(detail_once, args.base_url,
                                       product_ids[i % len(product_ids)], tokens[i % len(tokens)])
                           for i in range(args.warmup)]
                for f in as_completed(futures):
                    f.result()
            print("      预热完成")
        stats, elapsed, sources = run_cache_load(args.base_url, product_ids, tokens,
                                                 args.concurrency, args.requests)
        print_cache_report(stats, elapsed, sources, args.base_url, tokens[0])
        return

    if args.warmup > 0:
        warmup(args.base_url, args.activity_id, tokens, args.warmup)

    stats, elapsed = run_load(args.base_url, args.activity_id, tokens,
                              args.concurrency, args.requests)

    result: Dict[str, object] = {}
    if not args.no_verify and stats.accepted_order_nos:
        result = verify_results(args.base_url, tokens[0], stats.accepted_order_nos,
                                args.activity_id, args.verify_wait)

    print_report(stats, elapsed, args.requests, result)


if __name__ == "__main__":
    main()
