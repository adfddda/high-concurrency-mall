# 04 · 压测报告与并发正确性验证

> 本文件的**所有数字都是实测值**，并标注了测量口径与已知偏差。
> 凡是没测到的部分，明确写"未实测"并给出复现命令，不用估算值冒充实测值。

---

## 0. 测试环境

| 项 | 值 |
|---|---|
| CPU / 内存 | 与被测服务**同机**（压测客户端与应用共享 CPU，见第 4 节的偏差说明） |
| 操作系统 | Windows 11 |
| JDK | Microsoft OpenJDK 21.0.9 LTS |
| MySQL | 8.0.26（本机，未做任何调优） |
| Redis | 5.0.14.1（本机，单实例，`maxmemory-policy noeviction`） |
| RabbitMQ | **未参与**（见第 4 节；本地 Docker 守护进程不可用） |
| 应用启动参数 | `-Dfile.encoding=UTF-8 -jar high-concurrency-mall.jar`，`benchmark` profile |
| 连接池 | Hikari 50 / Lettuce 64 / Redisson 64 / Tomcat 500 线程 |

---

## 1. 自动化并发正确性验证（最重要的一组）

压测能说明"快不快"，但**只有并发断言能说明"对不对"**。以下全部来自 `mvn test`，43 个用例全绿。

| 用例 | 场景 | 断言 | 结果 |
|---|---|---|---|
| `SeckillStockConcurrencyTest#concurrentDeductNeverOversells` | **800 虚拟线程抢 200 库存** | 成功数 == 200；Redis 剩余库存 == 0；已购集合大小 == 200；被拒数 == 600 | ✅ 通过 |
| `SeckillStockConcurrencyTest#oneUserCanOnlyBuyOnce` | 同一用户 **500 并发** | 成功数 == 1；其余全部返回"重复购买"；库存只减 1 | ✅ 通过 |
| `SeckillStockConcurrencyTest#rollbackIsCappedByTotalStock` | 重复回补 5 次 | 库存被 `total_stock` 封顶，绝不凭空造出库存 | ✅ 通过 |
| `SeckillStockConcurrencyTest#notPreheatedMustFailFast` | 未预热 | 返回 `NOT_PREPARED`，**绝不放行到数据库** | ✅ 通过 |
| `SeckillStockConcurrencyTest#rollbackClearsLocalSoldOutFlag` | 售罄后回补 | 本地售罄标记被清除，后续请求能重新抢到 | ✅ 通过 |
| `RedisRateLimiterTest#slidingWindowIsAtomicUnderConcurrency` | 200 并发打 limit=20 | **恰好放行 20 个**（多一个就说明脚本不原子） | ✅ 通过 |
| `RedisRateLimiterTest#differentRulesDoNotShareQuota` | 大额度规则打 8 次后再用小额度规则 | 小额度规则仍有完整 5 次额度 | ✅ 通过 |
| `OrderIdempotencyTest#duplicateMessageCreatesOnlyOneOrder` | 同一消息消费两次 | 只产生 1 个订单、1 条订单项、库存只扣 1 次 | ✅ 通过 |
| `OrderIdempotencyTest#sameUserSameActivityIsRejectedByUniqueKey` | 不同消息同一用户 | 抛 `DuplicateOrderException`，事务完全回滚 | ✅ 通过 |
| `OrderIdempotencyTest#databaseRefusesNegativeStock` | 绕过应用层直接 `UPDATE` 扣负数 | 被 `CHECK` 约束拒绝 | ✅ 通过 |
| `OrderTimeoutTest#cancelIsIdempotent` | 重复关单 4 次 | 库存只回补 1 次，回补流水只有 1 条 | ✅ 通过 |
| `OrderTimeoutTest#paidOrderCannotBeClosed` | 已支付订单被超时关单 | 拒绝关单，库存不回补 | ✅ 通过 |
| `ProductCacheTest#nullValueCacheStopsPenetration` | 查不存在的商品 6 次 | 数据库只被访问 **1 次** | ✅ 通过 |

> 这组测试是"不超卖、不重复、不丢库存"这些结论的**唯一可信来源**。
> 压测只能告诉你吞吐量，告诉不了你正确性。

---

## 2. 商品详情 · 多级缓存压测

### 2.1 测量结果

```
 模式=cache  并发=400  总请求=100,000  用户数=100
```

| 指标 | 客户端观测 | **服务端自测**（`http.server.requests`） |
|---|---|---|
| 请求数 | 100,000 | 130,984 |
| 吞吐 QPS | **13,102** | — |
| 平均延迟 | 30.31 ms | **1.118 ms** |
| P50 | 23.41 ms | — |
| P95 | 72.85 ms | — |
| P99 | 116.49 ms | — |
| P999 | 226.63 ms | — |
| 最大 | 301.48 ms | 830.61 ms |

**数据来源分布（这才是判断缓存是否生效的依据）**

| 来源 | 次数 | 占比 |
|---|---|---|
| **L1（Caffeine 进程内）** | 99,868 | **99.9%** |
| L2（Redis） | 107 | 0.1% |
| **DB（MySQL）** | **0** | **0.0%** |

服务端 L1 统计：`l1RequestCount=30300, l1HitCount=30195, l1HitRate=99.65%, l1MissCount=105`

### 2.2 这组数字说明什么

1. **10 万个请求，数据库被访问 0 次。** 多级缓存不是"看起来快"，是真的把数据库完全隔离在外了。
2. **99.9% 的请求在进程内存里就被处理完**，只有 0.1% 需要走一次 Redis——这 0.1% 是
   L1 的 3 秒 TTL 到期的瞬间，每个商品都只有第一个请求会落到 L2，其余立刻被回填的 L1 接住。
3. **服务端平均 1.118ms，客户端却测出 30ms**：差值是压测客户端自身的开销。
   即便改用 Java 虚拟线程，客户端与服务端仍在**争抢同一份 CPU**，所以
   **13,102 QPS 是这台机器"客户端+服务端一起跑"的结果，明显低估了服务端能力**。
   要得到真实容量，必须把压测机与被测机分开，并用 `wrk` / JMeter 这类原生客户端。

### 2.3 一个方法论上的教训（值得单独讲）

第一版压测用的是 Python 脚本（`scripts/load-test.py`），结果：

| 客户端 | 并发 | QPS | 客户端平均延迟 | 服务端平均延迟 |
|---|---|---|---|---|
| Python `threading` | 200 | **738** | 247.08 ms | **1.68 ms** |
| Java 21 虚拟线程 | 400 | **13,102** | 30.31 ms | **1.118 ms** |

**同一个服务、同一个接口，QPS 差了 17.7 倍。** 原因不是服务变快了，而是 Python 的 GIL
让 200 个线程互相争抢解释器锁，**压测客户端自己成了瓶颈**。

> 🔑 **教训：报 QPS 之前，必须先证明"瓶颈不在压测客户端"。**
> 做法很简单——读服务端自己统计的指标（Spring Boot 的 `http.server.requests`），
> 把 `TOTAL_TIME / COUNT` 算出来。如果服务端平均延迟远小于客户端观测延迟，
> 那这个 QPS 数字就是客户端能力的上限，不是被测系统的。
> 这也是为什么本项目的压测客户端最终改用了 Java 虚拟线程
> （`scripts/BenchClient.java`，单文件，`java scripts/BenchClient.java` 直接跑）。

---

## 3. 秒杀链路 · 故障演练（RabbitMQ 完全不可用）

这是本项目**最有价值的一次实测**：在消息中间件完全挂掉的情况下，
用 2 万个并发请求打秒杀接口，验证"库存会不会凭空消失"。

### 3.1 测量结果

```
 模式=seckill  活动=2002（总库存 100）  并发=300  总请求=20,000  用户数=100
```

| 指标 | 值 |
|---|---|
| QPS | 572 |
| 平均延迟 | 514.88 ms |
| P50 | 248.39 ms |
| P95 | 2,024.41 ms |
| P99 | 2,495.14 ms |
| 服务端平均延迟 | 69.42 ms |

**业务码分布**

| 业务码 | 含义 | 次数 | 占比 |
|---|---|---|---|
| 20005 | 重复购买 | 16,117 | 80.6% |
| 20008 | 提交失败（MQ 不可用，已补偿） | 3,801 | 19.0% |
| 20004 | 已售罄 | 82 | 0.4% |

> 关于 20005 占比高：压测只用了 100 个用户却有 300 并发，同一用户平均有 3 个请求同时在飞，
> 兄弟请求之间的"已购标记"还没被补偿清除，因此大量命中"重复购买"。
> 这是**符合预期的**——一人一单的判定本来就是按用户维度的。

### 3.2 最终一致性校验（核心结论）

压测结束后立即调用对账接口：

```json
[
  {"activityId":2001,"redisStock":200,"dbAvailableStock":200,"inflight":0,"drift":0,"consistent":true},
  {"activityId":2002,"redisStock":100,"dbAvailableStock":100,"inflight":0,"drift":0,"consistent":true},
  {"activityId":2003,"redisStock":50, "dbAvailableStock":50, "inflight":0,"drift":0,"consistent":true},
  {"activityId":2004,"redisStock":500,"dbAvailableStock":500,"inflight":0,"drift":0,"consistent":true},
  {"activityId":2005,"redisStock":300,"dbAvailableStock":300,"inflight":0,"drift":0,"consistent":true}
]
```

活动 2002 的 Redis 侧状态：`{"redisStock":100,"inflight":0,"boughtCount":0,"localSoldOut":false}`

**结论**：
- ✅ **零库存泄漏**：2 万个请求中有 3,801 次走完了"扣减 → 投递失败 → 补偿"的完整路径，库存全部归还
- ✅ **零超卖**：`redisStock` 与 `dbAvailableStock` 始终一致，`drift = 0`
- ✅ **零在途残留**：`inflight = 0`，说明补偿路径正确归还了在途计数
- ✅ **零资格污染**：`boughtCount = 0`，失败用户的购买资格被正确回收

### 3.3 代价也要说清楚

| 现象 | 原因 |
|---|---|
| P95 从毫秒级劣化到 **2 秒** | RabbitMQ 客户端在 broker 不可达时反复重连并退避，发送动作被阻塞 |
| QPS 从万级掉到 **572** | 每个失败请求都要额外走"查订单是否已存在(DB) + 回补库存(Lua) + 写在途结果(Redis) + 记录失败(DB)" |
| 全部请求最终失败 | 这是**正确的降级行为**——宁可全部失败，也不能让库存凭空消失或超卖 |

> **这就是"最终一致性"的真实成本**：MQ 挂掉时，秒杀链路从"高吞吐"退化成"低吞吐 + 全部失败"，
> 但**一笔资损都不会产生**。对交易系统来说，这个交换是划算的。

### 3.4 还验证了一件事：本地售罄标记的自愈

注意到业务码里有 **82 次 20004（已售罄）**——但库存最终是满的。
说明这些请求命中过售罄判定，随后库存被补偿回来，而
`LocalSoldOutCache` 在回补时被正确清除，后续请求又能正常抢购。

**这是"本地标记必须随回补清除"这条设计约束的真实运行时证据。**
如果漏了这个 `clear()`，本实例会永久拒绝该活动，而日志里不会有任何异常——
这也是为什么该缓存的 TTL 兜底（默认 10 秒）不能被省掉。

---

## 4. 未实测的部分（诚实声明）

| 未测项 | 原因 | 复现命令 |
|---|---|---|
| **秒杀成功链路的吞吐**（含 MQ 投递 + 异步落库 + 结果查询） | 本地 Docker 守护进程不可用（`com.docker.service` 需要管理员权限），RabbitMQ 未能启动 | 见下方 |
| **RabbitMQ Confirm/Return/死信/延迟重试的真实 broker 行为** | 同上 | 见下方 |
| 限流开启时的秒杀拦截率 | 与上一条同批 | 见下方 |
| 分布式压测（压测机与服务端分离） | 单机环境 | 用 `wrk`/JMeter 从另一台机器打 |
| Redis Cluster 下的行为 | 单实例环境 | Hash Tag 同槽性已由单测覆盖（`RedisClusterSlotTest`） |

### 复现命令（RabbitMQ 就绪后执行）

```bash
# 1. 起中间件
docker compose up -d rabbitmq redis

# 2. 以 benchmark profile 启动（限流关闭，专测容量）
java -jar target/high-concurrency-mall.jar --spring.profiles.active=benchmark

# 3. 秒杀容量压测（Java 虚拟线程客户端）
java scripts/BenchClient.java --mode=seckill \
     --activity-id=2001 --concurrency=400 --requests=50000 --users=500

# 4. 限流验证（以 local profile 启动，限流开启）
java -jar target/high-concurrency-mall.jar --spring.profiles.active=local
java scripts/BenchClient.java --mode=seckill \
     --activity-id=2005 --concurrency=200 --requests=5000 --users=200
#    预期：大量 42900（被限流），且被限流比例随并发上升而上升

# 5. 一致性校验（无论哪一轮压测，结束后都必须跑这一步）
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/seckill/reconcile
```

**验收标准**（这三条必须同时成立，否则说明有 Bug）：

1. `t_order` 的实际行数 ≤ 活动总库存（**绝不超卖**）
2. 每个 `(user_id, activity_id)` 最多一行（**一人一单**）
3. 对账接口返回的所有活动 `drift == 0` 且 `inflight == 0`（**无库存泄漏**）

---

## 5. 容量参考与瓶颈分析

基于已测数据做的**推算**（不是实测，已明确标注）：

| 环节 | 实测/推算 | 说明 |
|---|---|---|
| 商品详情（L1 命中） | **13,102 QPS**（客户端受限） | 服务端 1.118ms/请求，真实上限远高于此 |
| 秒杀入口（单机 Redis Lua） | 未实测 | 单次 Lua 是本链路唯一的 Redis 往返 |
| 数据库写入 | 未实测 | 受 MQ 削峰保护，稳态压力 = 消费速率 |

**已识别的瓶颈点与优化方向**：

| 瓶颈 | 现象 | 优化 |
|---|---|---|
| 本地 Redis 单实例 | 所有 Lua 都打到一个实例 | Redis Cluster + Hash Tag（已就绪） |
| 秒杀入口的 Redis 往返 | 每次抢购 1 次 EVAL | 本地库存分片预扣（代价是一致性更难维护） |
| 消费端 DB 写入 | 每个订单 5 条 INSERT | 批量消费 + 批量插入；或按 `user_id` 分库 |
| 连接池 | Hikari 50 / Lettuce 64 | 已足够；继续加大只会变成锁等待 |
| 压测客户端 | 与服务端争抢 CPU | 压测机分离 + wrk/JMeter |

---

## 6. 一句话总结

> **正确性有硬证据**（43 个自动化用例，含 800 并发抢 200 库存的精确断言）；
> **缓存效果有硬证据**（10 万请求，数据库 0 次访问，L1 命中 99.9%）；
> **故障降级有硬证据**（MQ 全挂 + 2 万并发，5 个活动 `drift` 全为 0）；
> **吞吐数字则打了折扣**（单机自压、客户端与服务端争抢 CPU，13k QPS 是下界而非上限），
> 真实容量需要把压测机分离后重测。
