# 14 · 运维手册（Runbook）

> 这份文档回答的是**"凌晨三点被告警叫醒时该做什么"**，不是"系统是怎么设计的"。
> 设计看 [01-架构](./01-architecture.md)，排查思路看 [03-面试题库](./03-interview-qa.md) 的场景题。
>
> 原则：**每一条都给出"看哪个指标 → 怎么确认 → 先做什么 → 怎么回滚"**。
> 没有处置步骤的告警等于没有告警。

---

## 1. 上线前检查清单

- [ ] `mall.demo.seed-enabled=false`（否则等于开了个已知密码的后门）
- [ ] `JWT_SECRET` 通过环境变量注入，长度 ≥ 32
- [ ] `mall.admin.usernames` 换成真实运维账号
- [ ] `springdoc.api-docs.enabled=false`、`management.endpoints.web.exposure` 收敛到内网
- [ ] `mall.reconcile.auto-repair=false`（**先只告警跑一周**，见 §4.3）
- [ ] 库存对账任务是单实例执行（多实例会重复修，虽然 `SET` 幂等，但会互相覆盖）
- [ ] `SECKILL_BUCKETS` 只有在上 Redis Cluster 时才 > 1
- [ ] 数据库 `CHECK` 约束真正生效（MySQL 8.0.16+）

---

## 2. 关键指标与告警阈值

| 指标 | 含义 | 建议阈值 | 说明 |
|---|---|---|---|
| `mall.seckill.result{reason=sold_out}` 占比 | 秒杀失败主因 | 突增属正常（活动开始） | 突增且活动未开始 → 查预热 |
| `mall.seckill.result{reason=not_prepared}` | 活动未预热 | **> 0 持续 1 分钟即告警** | 几乎总是配置/预热失败 |
| `mall.seckill.lua` P99 | Redis 侧耗时 | **> 10ms** | 单分片饱和 / 慢命令 |
| `mall.cache.access{level=db}` 速率 | 缓存是否挡住 DB | 商品详情 **> 1/s** | 正常应接近 0 |
| `mall.cache.access{cache=product,level=l1,result=hit}` | L1 命中率 | **< 95%** | TTL 太短或容量不足 |
| `mall.rate.limit.degraded` | 限流组件降级次数 | **> 0** | 说明 Redis 出问题，限流已失效 |
| `mall.mq.event{action=dlq}` | 进死信速率 | **> 0 持续** | 消费持续失败 |
| `mall.reconcile.drift` | 库存漂移 | **≠ 0** | 见 §4.3 |
| `hikaricp_connections_pending` | 等待连接的线程 | **> 0 持续 30s** | 连接池打满，RT 即将抬升 |
| `http.server.requests` 5xx 比例 | 接口错误 | **> 1%** | — |
| 网关 `X-RateLimit-Remaining` 长期为 0 | 真实被限流 | 按活动判断 | 正常用户被限流要调阈值 |

---

## 3. 应急开关（改完是否需要重启）

| 开关 | 作用 | 生效方式 |
|---|---|---|
| `SECKILL_BUCKETS` | 库存分桶数 | **需重启** |
| `GATEWAY_RATE_LIMIT` / `GATEWAY_RATE_WINDOW` | 网关限流阈值 | **需重启网关** |
| `mall.rate-limit.rules.*` | 业务限流规则 | **需重启**（未接配置中心） |
| `RECONCILE_ENABLED` / `auto-repair` | 是否对账/自动修 | **需重启** |
| `SEARCH_ENABLED` | 搜索是否走 ES | **需重启** |
| `TRACING_SAMPLE_RATE` | 采样率 | 需重启（未接动态配置） |

> 需要动态调整的开关应该接配置中心（Nacos/Apollo）。**当前实现是静态配置，
> 这是明确的已知短板**——所以阈值要留足余量，不要依赖线上调参。

---

## 4. 常见故障处置

### 4.1 秒杀接口大量 429

**看**：`mall.rate.limit.rejected{api=seckill,dimension=?}`。

| dimension | 判断 |
|---|---|
| `USER` | 单个用户刷接口，属正常防护 |
| `IP` | 大量用户共用出口 IP（公司 NAT、校园网）→ **误伤** |

**处置**：确认是误伤后，临时上调 `mall.rate-limit.rules.seckill.limit` 并重启；
长期方案是把 IP 维度阈值按时间段放宽（大促期间），并推动客户端带设备号走风控而不是限流。

### 4.2 秒杀大量返回 `20006 活动正在准备中`

**含义**：Redis 里没有活动的库存 key（未预热或 key 过期）。

**立即处置**：
```bash
curl -X POST "$BASE/api/seckill/activities/{id}/preheat" -H "Authorization: Bearer $TOKEN"
curl "$BASE/api/admin/seckill/{id}/redis-state" -H "Authorization: Bearer $TOKEN"
```
**根因排查**：预热任务是否在跑（`SeckillPreheatJob`）、活动 `end_time` 是否已过
（预热 TTL = 剩余时长 + 1h）、Redis 是否发生过重启/主从切换（key 丢失）。

### 4.3 库存对账发现漂移（`drift ≠ 0`）

**第一步：不要立刻开自动修复。** 先确认 drift 的方向：

| drift | 含义 | 可能原因 |
|---|---|---|
| `> 0`（Redis 比应得多） | 少卖了 | 回补多执行了一次，或 DB 扣减失败但 Redis 扣了 |
| `< 0`（Redis 比应得少） | **潜在超卖风险** | 补偿漏做、进程崩溃、有人直接改 Redis |

**第二步：对单个活动做 dry-run**（`repair=false`，只看不改）：
```bash
curl "$BASE/api/admin/seckill/{id}/reconcile?repair=false" -H "Authorization: Bearer $TOKEN"
```
**第三步：确认后在低峰期修复**（`repair=true`）。

**纪律**：修复前把三元组（`redisStock / inflight / dbAvailable`）记进工单。
对账修复是"直接改生产数据"级别的操作，**没有快照就没有事后追溯**。

> ⚠️ 已知局限：修复时**不会回收**在途泄漏对应的用户购买资格
> （`bought` 集合不动）。这些用户会失去本次活动的购买资格，需要人工处理。

### 4.4 订单卡在"待支付"超过 `expire_time`

**看**：`OrderTimeoutFallbackJob` 是否在执行（日志 `订单已取消并归还数据库库存`）。

**手工兜底**：
```bash
curl -X POST "$BASE/api/order/{orderNo}/cancel" -H "Authorization: Bearer $TOKEN"
```
**注意**：如果订单已支付，这个接口会返回 `canceled=false`（状态机拒绝），这是正确的。

### 4.5 MQ 堆积 / `mall.mq.pending` 持续 > 0

`pending` 是"已发送但未收到 Confirm"的消息数。**持续 > 0 = broker 或网络有问题**。

```bash
curl "$BASE/api/admin/mq/pending" -H "Authorization: Bearer $TOKEN"
```
**处置顺序**：看 broker 健康 → 看队列深度（`rabbitmqctl list_queues`）→
如果只是确认丢失，`PendingMessageRetryJob` 会在 5 秒后触发补偿，无需人工；
如果是 broker 挂了，秒杀入口会快速失败（**不会降级直查 DB**），等 broker 恢复即可。

### 4.6 缓存命中率骤降 / DB QPS 飙升

```bash
curl "$BASE/api/admin/cache/product/stats" -H "Authorization: Bearer $TOKEN"
```
| 现象 | 判断 | 处置 |
|---|---|---|
| L1 命中率低、L2 正常 | L1 TTL 太短 / 容量不足 | 调 `l1-ttl`、`l1-max-size` |
| L2 命中率也低 | Redis 内存满（`noeviction` 会拒绝写）、key 被逐出 | 看 `INFO memory`、`evicted_keys` |
| `db` 计数高 | 缓存没挡住，回源 | 先加限流保护 DB，再定位 |

**紧急止血**：开启商品详情限流（`product-detail` 规则），避免 DB 被打穿。

### 4.7 Search 不可用

`SEARCH_ENABLED=true` 但 ES 挂掉时，搜索**自动降级为数据库 LIKE**，接口不会报错，
但会在日志出现 `ES 查询失败，将降级到数据库`。

**判断依据**：日志 WARN + 接口 P99 上升。**不需要紧急处理**（降级已生效），
但要修 ES；恢复后**重启应用或调用一次商品写入**触发索引重建（当前没有自动重建入口）。

## 5. 值班速查

```bash
BASE=http://127.0.0.1:8080
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"lxt","password":"123456"}' | jq -r .data.token)

# 健康
curl -s $BASE/actuator/health | jq '.status, .components | keys'

# 秒杀实时状态
curl -s "$BASE/api/admin/seckill/2001/redis-state" -H "Authorization: Bearer $TOKEN" | jq

# 对账（只看不改）
curl -s "$BASE/api/admin/seckill/2001/reconcile?repair=false" -H "Authorization: Bearer $TOKEN" | jq

# 缓存命中
curl -s "$BASE/api/admin/cache/product/stats" -H "Authorization: Bearer $TOKEN" | jq

# MQ 待确认
curl -s "$BASE/api/admin/mq/pending" -H "Authorization: Bearer $TOKEN" | jq

# 风控拦截统计
curl -s "$BASE/api/admin/seckill/2001/risk-hits" -H "Authorization: Bearer $TOKEN" | jq
```

---

## 6. 数据修复纪律

1. **先备份**：`mysqldump` 相关行，或至少 `SELECT ... INTO OUTFILE` 留存现场；
2. **先 dry-run**：对账接口都支持"只看不改"；
3. **单实例执行**：批量修数据的脚本不要在多副本上同时跑；
4. **留审计**：修复动作必须能在日志里按 `activityId` / `orderNo` 搜到；
5. **能回滚**：修之前想清楚"如果修错了，怎么退回去"。修 Redis 比修 DB 容易回滚——
   这也是"数据库是权威"带来的额外好处。
