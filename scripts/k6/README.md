# k6 压测

两个脚本分别对应两条读/写路径：

| 脚本 | 场景 | 关注点 |
|---|---|---|
| `browse.js` | 商品详情（多级缓存） | `source` 字段分布：L1 应占绝大多数，DB 应接近 0 |
| `seckill.js` | 秒杀下单 | 受理率、P95 延迟、Redis Lua 耗时 |

## 前置

```bash
# 1. 起中间件
docker compose up -d

# 2. 以 benchmark profile 启动应用（关闭限流，否则测的是限流器）
java -jar target/high-concurrency-mall.jar --spring.profiles.active=benchmark

# 3. 预热活动（秒杀脚本需要）
curl -X POST "http://localhost:8080/api/seckill/activities/2001/preheat" \
     -H "Authorization: Bearer $TOKEN"
```

## 运行

```bash
# 缓存链路：400 并发跑 30 秒
k6 run -e VUS=400 -e DURATION=30s scripts/k6/browse.js

# 秒杀：200 个账号、200 VU
k6 run -e VUS=200 -e USERS=200 -e ACTIVITY_ID=2001 scripts/k6/seckill.js

# 输出到文件，便于对比两轮改动
k6 run --summary-export=result.json scripts/k6/seckill.js
```

## 阈值即门禁

脚本里定义了 threshold（如 `p(95) < 800ms`）。**不达标时 k6 以非 0 退出码结束**，
所以可以直接放进 CI 当性能回归门禁——这是 k6 相对手写压测脚本最大的价值。

## 和 Java 压测客户端的分工

- `scripts/BenchClient.java`：单机极限吞吐，虚拟线程客户端，结果最干净；
- k6：分布式压测 + 阈值门禁 + 标准化报告。

> ⚠️ 报 QPS 之前先确认瓶颈不在压测客户端。
> 用 `curl localhost:8080/actuator/metrics/http.server.requests` 看服务端真实延迟：
> `TOTAL_TIME / COUNT` 远小于客户端观测延迟，说明客户端才是瓶颈。
