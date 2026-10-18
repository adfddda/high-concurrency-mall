<!-- 本文档是「高并发商城交易系统」的项目配套文档，与 src/ 下代码一一对应；文档中的类名、配置项、数字均取自项目实际源码与 application.yml，请勿在未同步代码的情况下单独修改。 -->

# 字节跳动后端面试题库 · 高并发商城交易系统

> 适用岗位：后端开发（Java）/ 交易、营销、基础架构方向
> 配套代码：`high-concurrency-mall`（Spring Boot 3 + MySQL 8 + Redis 7 + RabbitMQ 3.13 + Redisson）
> 题量：**110 道**，每道题都绑定本项目真实实现，附「追问」与「面试官意图」

---

## 一、如何使用这份文档

### 1.1 三种用法

| 场景 | 用法 | 建议时间 |
| --- | --- | --- |
| **面试前 1 天突击** | 只读每题加粗的「结论句」和「追问」，先保证不被问倒 | 2 小时 |
| **面试前 1 周系统准备** | 按模块顺序过，每题自己先答一遍再对答案，重点补「取舍」部分 | 每天 1 个模块 |
| **面试后被复盘** | 搜模块关键词，定位答错的点，回到源码确认细节 | 按需 |

### 1.2 一条铁律：不要背答案，要背「取舍」

面试官判断一个候选人是不是真的做过项目，看的不是"你用了什么"，而是：

1. **你知道另一个方案是什么吗？**
2. **你为什么没选它？**
3. **选了它你会付出什么代价？**
4. **你怎么知道你的选择是对的？**

本项目的每一个设计点都至少对应一个被放弃的备选方案。文档里所有的"为什么不选 XX"都是真实权衡，不是事后编的——因为备选方案往往更简单，如果真的更优，项目里就应该用它。

### 1.3 数字必须记准

面试中"大概几秒"和"3 秒"给面试官的观感完全不同。下面这张表是本项目所有关键数字，建议背下来：

| 配置项 | 值 | 含义 / 为什么是这个值 |
| --- | --- | --- |
| `mall.seckill.order-expire` | **30s** | 订单支付超时，同时是延迟队列 TTL |
| `mall.seckill.activity-local-ttl` | **5s** | 活动快照 L1 TTL，多实例不一致窗口上界 |
| `mall.seckill.local-soldout-ttl` | **10s** | 本地售罄标记 TTL，反向不一致的自愈上界 |
| `mall.seckill.bought-set-ttl` | **24h** | 已购集合 TTL，必须覆盖活动全周期 |
| `mall.cache.product.l1-ttl` | **3s** | 商品详情 L1 TTL，多实例不一致窗口上界 |
| `mall.cache.product.l2-logical-expire` | **3m** | L2 逻辑过期，触发异步重建 |
| `mall.cache.product.l2-physical-ttl` | **30m** | L2 物理 TTL，必须远大于逻辑过期 |
| `mall.cache.product.l2-ttl-jitter-ratio` | **0.2** | 雪崩防护，TTL 抖动 ±20% |
| `mall.cache.product.null-value-ttl` | **60s** | 空值缓存 TTL |
| `mall.cache.product.rebuild-lock-wait` | **200ms** | 重建锁等待 |
| `mall.cache.product.rebuild-lock-lease` | **10s** | 重建锁租约 |
| `mall.cache.product.rebuild-pool-size` | **4**（max 8） | 异步重建线程池 |
| `mall.cache.product.rebuild-queue-capacity` | **2048** | 异步重建队列 |
| `mall.cache.double-delete-delay` | **500ms** | 延迟双删间隔 |
| `mall.cache.bloom-expected-insertions` | **100000** | 布隆过滤器预期元素数 |
| `mall.cache.bloom-fpp` | **0.01** | 布隆过滤器误判率 |
| `mall.rate-limit.rules.seckill` | **5 / 1s**，滑动窗口 | 秒杀限流 |
| `mall.rate-limit.rules.product-detail` | **50 / 1s**，滑动窗口 | 商品详情限流 |
| `mall.rate-limit.rules.login` | **capacity 10, rate 0.5/s**，令牌桶 | 登录限流（应对突发） |
| `mall.mq.max-retry` | **3** | 消费失败重试次数 |
| `mall.mq.retry-delay` | **5s** | 重试队列 TTL（延迟重试间隔） |
| `mall.mq.confirm-timeout` | **5s** | Confirm 未到达的判定窗口 |
| `mall.reconcile.cron` | **0 */5 * * * ?** | 每 5 分钟对账一次 |
| `mall.reconcile.drift-tolerance` | **0** | 漂移容忍度（不容忍） |
| 在途泄漏判定 | **连续 3 次采样** | `STALE_INFLIGHT_THRESHOLD = 3` |
| `server.tomcat.threads.max` | **500** | Tomcat 工作线程上限 |
| `server.tomcat.accept-count` | **2000** | 等待队列 |
| `server.tomcat.max-connections` | **20000** | 最大连接数 |
| `spring.datasource.hikari.maximum-pool-size` | **50** | DB 连接池 |
| `lettuce.pool.max-active` | **64** | Redis 连接池 |
| `mall.redisson.pool-size` | **64** | Redisson 连接池 |
| `spring.rabbitmq.listener.simple.prefetch` | **50** | 预取数量 |
| `spring.rabbitmq.listener.simple.concurrency` | **4 ~ 16** | 消费者并发区间 |
| `spring.rabbitmq.requested-heartbeat` | **30s** | MQ 心跳 |
| `mall.jwt.access-token-ttl` | **2h** | access token 有效期 |
| `mall.jwt.clock-skew` | **30s** | 多实例时钟偏移容忍 |
| BCrypt cost | **10** | 单次哈希 ≈ 50~100ms |
| 补偿守卫 TTL | **24h** | `seckill:compensate:guard:{messageId}` |
| 秒杀结果缓存 TTL | **30min** | 结果轮询窗口 |
| 订单超时兜底扫描 | **60s** 一次，单批 **200** 条 | `BATCH_SIZE = 200` |
| Confirm 超时补偿任务 | **2s** 一次 | 必须显著小于 5s 的 confirmTimeout |
| 代码验证 | **800 并发抢 200 库存** | `SeckillStockConcurrencyTest` |

### 1.4 环境与组件版本（可能被问）

MySQL 8.0（`--max_connections=1000`，`innodb_flush_log_at_trx_commit=2`）、Redis 7.2（`--appendonly yes --maxmemory 512mb --maxmemory-policy noeviction`）、RabbitMQ 3.13-management、JVM 用 ZGC（`-XX:MaxRAMPercentage=75 -XX:+UseZGC`）。**Redis 的 `noeviction` 是有意选的**：宁可写失败也不能让库存 key 被淘汰（被淘汰 = 库存直接错乱）。

---

## 二、面试时的答题框架

### 2.1 三层结构：结论先行 → 取舍 → 实现

任何一道设计题都按这个顺序答：

```
第 1 句（结论）：我用 Lua 脚本把「校验 + 扣减 + 去重 + 计数」压成一次原子操作。
第 2~4 句（取舍）：备选是分布式锁和数据库乐观锁。锁方案每次抢购要加锁解锁两次
                  网络往返，还要处理锁超时、续期、误删；乐观锁方案要在秒杀瞬间
                  把几万 QPS 打到数据库，直接打穿。
第 5 句起（实现）：具体脚本是这个样子……（可以口述伪代码）
```

**不要从"我用了什么技术"开头。** 面试官想先知道你判断过、然后再知道你怎么做的。

### 2.2 STAR 的改造版：S-T-A-R-D

标准 STAR（Situation-Task-Action-Result）在技术面试里缺一环，补一个 **D（Decision，决策与代价）**：

| 环节 | 本项目示例（秒杀链路） |
| --- | --- |
| **S** 背景 | 秒杀活动 200 库存，瞬时几万 QPS 打同一个接口 |
| **T** 目标 | 不超卖、不超总库存、接口 RT 与数据库负载解耦 |
| **A** 动作 | Redis Lua 原子扣减 + MQ 异步落库 + 对账收敛 |
| **R** 结果 | 800 并发抢 200 库存测试中成功恰好 200、剩余恰好 0；接口无同步 DB 操作 |
| **D** 决策 | 放弃分布式锁（吞吐低一个数量级）、放弃同步落库（RT 受 DB 影响）、放弃本地消息表（热路径多一次 DB 写）；代价是引入了最终一致和补偿复杂度 |

### 2.3 五条实战技巧

1. **主动说代价。** "这个方案的问题是……"比等面试官挑出来强得多。真诚的自我批评是加分项。
2. **主动说自己踩的坑。** 例如 `useAffectedRows=true` 那个坑、本地售罄标记忘记清零导致"明明有货却抢不到"。踩过坑的人才记得住细节。
3. **主动给验证方式。** "我怎么证明它是对的：并发测试断言 + 对账任务 drift 恒为 0 + 监控指标。"这是区分"做过"和"抄过"的分水岭。
4. **不确定的数字不要编。** 说"我记得是几秒级，具体配置项我回去确认"远好过编一个错数字被抓。
5. **不要过度设计。** 面试官问"普通下单也这么做吗"，正确答案是"不，普通下单老老实实同步落库，秒杀这套架构只在瞬时并发极高且可接受最终一致的场景才划算"。

---

## 三、秒杀与库存（15 题）

### Q1. 为什么用 Lua 脚本做库存扣减，而不是 Java 里 redisTemplate 调多次？

**结论：** 「查库存 → 判重 → 扣减 → 记录用户」四步如果分多次发送命令，中间必然插入其他请求的指令，Redis 单线程保证的是"单条命令原子"，不是"多条命令原子"，会超卖。Lua 脚本在 Redis 内部整体串行执行且不可被打断，四步天然成为原子操作，并且只消耗**一次 RTT**。

本项目 `seckill_deduct.lua` 的四步是：`GET stock` → `SISMEMBER bought` → 判断后 `DECR stock` → `SADD bought` → 可选 `INCR inflight`。

**取舍：**
- **分布式锁方案**：需要加锁/解锁两次网络往返，还有锁超时、锁续期、误删他人锁、锁释放失败等一连串问题，吞吐通常低一个数量级。
- **数据库乐观锁方案**：`UPDATE ... WHERE version = ?`，但几万 QPS 直接打在 MySQL 单行上，行锁排队直接把 DB 打死，秒杀的意义就没了。
- **Java 侧 `MULTI/EXEC`**：`WATCH` 在冲突时要客户端重试，逻辑分支（售罄/重复/未预热）没法用返回码表达，代码会退化成一堆重试循环。

**追问：** Lua 脚本执行期间 Redis 会阻塞其他请求，你怎么保证脚本不会拖慢 Redis？
**答：** 脚本里全是 O(1) 命令（GET/SISMEMBER/DECR/SADD/INCR），没有循环、没有 KEYS 遍历、没有大集合运算，所以执行时间是微秒级。反过来如果脚本里要遍历一个大 Set，确实会阻塞整个 Redis——这是 Lua 方案的真实代价，必须靠"脚本里只做 O(1) 操作"这条纪律来规避。项目里还专门给 `mall.seckill.lua` 打了 Timer，用 P99 指标盯住它。

> 💡 **面试官意图：** 核心考点是"Redis 单线程 ≠ 多命令原子"。答错会暴露你只会用 Redis 而没理解它的原子性边界。

---

### Q2. 三个 key 为什么必须带 Hash Tag？不带会怎样？

**结论：** Redis Cluster 计算槽位时**只取 key 里 `{...}` 大括号内的内容**做 CRC16，再对 16384 取模。三个 key 必须落在同一槽，`EVAL` 才能在集群模式下正常执行；否则 Redis 直接返回 `CROSSSLOT Keys in request don't hash to the same slot`。

本项目 `RedisKeys.activityTag(activityId)` 返回 `{act:2001}`，于是：
```
seckill:stock:{act:2001}
seckill:bought:{act:2001}
seckill:inflight:{act:2001}
```
三个 key 必然同槽。

**取舍：**
- **tag 里加 `act` 前缀而不是裸 `{2001}`**：不同业务域的数字 ID 容易撞车（活动 2001 和商品 2001），加前缀避免"两个本不相关的 key 被强行放进同一个槽"导致数据倾斜。代价是 tag 稍长、可读性略降——完全不值一提的代价。
- **Hash Tag 的副作用**：同一活动的所有 key 强制落在单个节点上，如果设计成"每个用户一个 key 也带同一个 tag"，会造成热点。本项目只有 3 个活动级 key，量很小，不构成问题；如果是"用户维度"的 key 就必须换设计。

**追问：** 单机 Redis 会报 CROSSSLOT 吗？你怎么提前发现这个问题？
**答：** 单机 Redis 永远不报——这正是最危险的地方，重构时有人"顺手"删掉 Hash Tag，单机全绿、一上集群全崩。所以 `SeckillStockService` 里有一个 `@PostConstruct assertSameSlot()` 自检，用 `RedisClusterSlot.sameSlot()` 在**启动阶段**验证三个 key 同槽，不同槽直接 `IllegalStateException` 让应用起不来。启动即失败远好过上线即故障。

> 💡 **面试官意图：** 考的是"有没有真的跑过 Redis Cluster"。只跑过单机的人答不出 CROSSSLOT，更不会想到做启动自检。

---

### Q3. 超卖是怎么被防住的？一共有几层防线？

**结论：** 六层，层层递进，越靠后越可靠：

| 层 | 位置 | 手段 |
| --- | --- | --- |
| 1 | Redis Lua | `if tonumber(stock) <= 0 then return 1 end`，`DECR` 的原子性保证库存不会为负 |
| 2 | MySQL 活动表 | `UPDATE ... WHERE id=? AND available_stock >= #{num}`，`InnoDB` 行锁保证原子 |
| 3 | MySQL 活动表 | `CONSTRAINT ck_activity_stock_non_negative CHECK (available_stock >= 0)` |
| 4 | MySQL 订单表 | `uk_user_activity(user_id, activity_id)` 一人一单 |
| 5 | Redis 回补脚本 | `if current >= totalStock then return current end`，总库存封顶 |
| 6 | MySQL 回补语句 | `WHERE available_stock + #{num} <= total_stock` |

**取舍：** 为什么需要六层？因为前四层各自解决不同问题——Lua 解决"并发扣减"，DB 条件更新解决"Redis 与 DB 漂移"，CHECK 解决"应用层被绕过"，唯一键解决"Redis Set 判定失效"。**这不是过度设计，而是每一层都对应一类真实故障**。但也不要无限叠加：本项目**没有**在业务代码里做"分布式锁 + 库存快照比对"那种冗余校验，因为它的收益与复杂度不成比例。

**追问：** 第 5、6 层的"总库存封顶"为什么放在回补而不是扣减上？
**答：** 扣减侧的封顶是"库存不能为负"（第 1、2 层已覆盖）。回补侧的风险方向完全相反——回补是**加库存**，它唯一的危险是"加多了"，也就是重复回补把库存顶到总库存之上，凭空造出库存。`SeckillStockConcurrencyTest.rollbackIsCappedByTotalStock` 里连续回补 5 次，断言库存仍恰好等于 `totalStock`，就是为了钉死这个性质。

> 💡 **面试官意图：** 想听到"分层防御 + 每层防什么"的思维，而不是罗列技术名词。

---

### Q4. 超卖和少卖，你选哪个？为什么？

**结论：** **宁可少卖，绝不超卖。** 这是交易系统的第一原则。

- **超卖 = 资损。** 库存是 200 卖了 201，多出来的那一单要么赔钱发货、要么强制取消并道歉，是**不可逆的、要花钱的、会变成公关事件的**错误。
- **少卖 = 能力问题。** 库存 200 只卖了 199，剩 1 件可以通过超时关单回补、人工上架、下期活动消化，**可自愈**。

本项目在三个地方显式选择了这个方向：

1. `SeckillCompensationService` 注释里写死："宁可少卖，绝不超卖"。
2. 回补 Lua 脚本**刻意不做幂等判断**，靠总库存封顶把重复回补限制成"库存不增"（少卖）而不是"库存暴增"（超卖）。
3. 补偿前必须先查订单是否存在，`orderExists == true` 时**直接跳过补偿**——因为如果订单已经落库而你还回补库存，结果就是"订单在、库存也还回去了"，等价于超卖。

**追问：** 少卖的那部分库存怎么发现、怎么处理？
**答：** 三条路径：① 超时关单会自动把库存还回去（延迟队列 + 兜底扫描）；② 对账任务每 5 分钟比对 `Redis 库存 vs (DB 剩余 - 在途)`，`drift ≠ 0` 时告警并按 `auto-repair` 修复；③ `t_stock_flow` 表记录每一笔扣减和回补，可以按 `(activity_id, create_time)` 查出任意时刻的库存变化链，人工核对。发现"少卖"之后是业务决策（补货、延期、退款补偿），技术侧只保证**数据能被准确发现**。

> 💡 **面试官意图：** 这是最能区分"写过业务代码"和"抄过开源项目"的一题。答"两个都要避免"是最差的答案。

---

### Q5. 本地售罄标记（`LocalSoldOutCache`）解决什么问题？它的正确性边界在哪？

**结论：** 商品卖光后，绝大多数请求注定失败，但每个失败请求仍然要跑一次 Redis Lua——秒杀场景下这就是几万 QPS 的**无用 Redis 流量**。在 JVM 内存里记一个"这个活动已卖光"的标记，命中就直接拒绝，把整段流量挡在 Redis 之前。

实现是 Caffeine `Cache<Long, Boolean>`，`maximumSize = mall.seckill.local-soldout-max-size = 10000`，`expireAfterWrite = 10s`。

**正确性边界（这是重点）：**

| 性质 | 说明 |
| --- | --- |
| **只是快速否定，不是权威** | 最终判定永远以 Redis Lua 为准 |
| **多实例不同步** | A 实例标记售罄时 B 可能还在放请求——**这没问题**，放过去的会被 Redis 正确拒绝 |
| **反向不一致才是真问题** | 库存被回补后本地标记必须清掉，否则本实例会**一直拒绝用户**，表现为"明明有库存却抢不到" |
| **TTL 是最后的安全网** | 即使某条回补路径漏了 `clear()`，最多 10 秒自愈 |

所以 `SeckillStockService.rollback()` 和 `resetStock()` 里都强制调用 `localSoldOutCache.clear(activityId)`。

**追问：** 为什么是 10 秒而不是 1 秒或 60 秒？
**答：** 这是"反向不一致窗口"和"快速否定收益"的权衡。1 秒的话标记几乎立刻失效，挡不住持续流量；60 秒的话一旦某个回补路径漏了清理，用户会有整整一分钟"看到有货但抢不到"。10 秒是让 `SeckillStockConcurrencyTest.rollbackClearsLocalSoldOutFlag` 这种场景在实际运维中最多难受 10 秒。**用 TTL 兜底而不是靠"记得清"，是这类本地状态设计的通用原则。**

> 💡 **面试官意图：** 考"本地优化状态的正确性边界"。答不出"回补后要清标记"的人，一定没真跑过这个系统。

---

### Q6. 为什么必须提前预热？预热到底预热了什么？

**结论：** 秒杀的特点是**瞬时并发**——活动开始那一秒可能几万个请求同时到达。如果等第一个请求才去建缓存，这一秒内所有请求都会去查数据库建缓存。**缓存不是被"用"坏的，是被"建"坏的。** 提前预热把这份成本挪到流量到来之前。

`SeckillPreheatService.preheat()` 预热三样东西：

| Key | 内容 | TTL |
| --- | --- | --- |
| `seckill:stock:{act:N}` | 剩余库存（Lua `DECR` 的目标） | `ttlFor(activity)` |
| `seckill:activity:N` | 活动 + 商品快照（热路径不碰 DB） | 同上 |
| `seckill:bought:{act:N}` | 已购集合的 TTL（资格判定不能提前失效） | `max(24h, ttl)` |

`ttlFor()`：`remaining = endTime - now + 1h`，若为负或不足 30 分钟则取 1 小时。多留 1 小时缓冲，因为活动结束瞬间可能还有正在处理的请求，缓存提前消失会让这些请求失去保护、一起打到数据库。

**追问：** 为什么预热只写 `available_stock` 而不是 `total_stock`？为什么不清理已购集合？
**答：** 两个问题同一个理由——**重新预热必须幂等且不倒退**。
- 用 `total_stock` 会把已经卖出去的库存"复活"，直接超卖。`available_stock` 才是当前真实剩余量；首次预热时两者本来就相等，所以统一用 `available_stock` 既正确又不需要区分两套逻辑。
- 清空已购集合会让所有买过的用户重新获得资格，他们会在 Lua 里通过、然后在 DB 层撞 `uk_user_activity` 唯一键，触发大量无意义的补偿。活动 ID 是一次性的，也不存在复用旧集合的问题。

> 💡 **面试官意图：** "重入/重放场景下你的初始化逻辑还正确吗"是资深面试官常设的陷阱。

---

### Q7. 秒杀接口为什么不用分布式锁？

**结论：** 因为**临界区根本不需要锁**。Lua 脚本在 Redis 内部原子执行，"检查 + 修改"这个组合已经天然互斥，锁只是把"互斥"这件事又做了一遍，纯属冗余。

用锁的代价是实打实的：
- 加锁、解锁各一次网络往返 → RT 翻两到三倍；
- 需要处理锁超时、看门狗续期、误删他人锁、锁未释放导致的死锁；
- 锁粒度和事务边界会引入新的死锁风险（比如锁 A 活动再锁 B 活动）。

**取舍对比：**

| 方案 | RT | 复杂度 | 吞吐 |
| --- | --- | --- | --- |
| Redis Lua | 1 次 RTT | 低 | 高（单 key 原子操作） |
| Redisson 锁 + Java 判断 | 3 次 RTT（lock/judge/unlock） | 高 | 低一个数量级 |
| DB 乐观锁 | 1 次 DB 往返 + 冲突重试 | 中 | 受 DB 行锁上限约束 |

**追问：** 那本项目哪里用了分布式锁？为什么那里就合理？
**答：** 商品缓存的**互斥重建**（`ProductCacheService.loadWithMutex`）。理由完全不同：那里的临界区是"查数据库 + 写 Redis"**两次跨资源往返**，锁能真正把 N 个并发请求收敛成 1 次 DB 查询。而秒杀的临界区只有一次 `EVAL`，锁收不收敛没有差别。**判断要不要锁的标准不是"有没有并发"，而是"临界区里有没有值得被收敛的慢操作"。**

> 💡 **面试官意图：** 考你有没有"用锁是为了什么"的判断力，而不是记住"高并发就要加锁"。

---

### Q8. 回补库存为什么用 `INCR` 而不是 `SET key (stock+1) KEEPTTL`？

**结论：** 两个原因，第二个更现实。

1. **`KEEPTTL` 是 Redis 6.0 才引入的语法**，在 5.x 上直接报语法错误。很多开发机、老环境跑的正是 5.x。`INCR` 天然保留 key 的 TTL，不需要任何额外语法，兼容性最好。
2. **`INCR` 本身是原子自增**，语义上就比"读出来加一再写回去"更准确——后者在脚本里虽然也是原子的，但读写两次操作无论如何都不如一次自增干净。

**取舍：** 用 `INCR` 的代价是没法在一次操作里同时表达"封顶"。所以脚本里的顺序是：先 `GET` 读当前值 → 判断 `current >= totalStock` → 再 `INCR`。这中间没有其他命令插入（脚本整体原子），所以不会出现"判断通过后值被别人改大"的竞态。

**追问：** 如果活动已经被清理、库存 key 不存在，回补会发生什么？
**答：** 脚本第一行 `local stock = redis.call('GET', stockKey); if not stock then return -1 end` 直接返回 -1，**不做任何变更**。这是刻意的：活动结束或被清理后，库存这个概念已经不存在了，凭空创建一个 `stock = 1` 的 key 毫无意义，还会污染下次活动的预热。调用方拿到 -1 只记日志，不报错。

> 💡 **面试官意图：** `KEEPTTL` 这个细节能直接测出你有没有在真实环境下写过这段代码。

---

### Q9. Lua 里为什么把 `SISMEMBER` 判重放在库存判断**之前**？

**结论：** 对已经抢到过的用户，返回"重复购买"比返回"已售罄"更准确，前端提示也更友好。而且成本更低——库存不足时通常只有少量请求能走到这一步。

代码顺序：
```lua
local stock = redis.call('GET', stockKey)
if not stock then return 3 end              -- 未预热优先，必须最先失败
if redis.call('SISMEMBER', boughtKey, userId) == 1 then return 2 end  -- 再判重
if tonumber(stock) <= 0 then return 1 end   -- 再判库存
redis.call('DECR', stockKey)
redis.call('SADD', boughtKey, userId)
```

**注意顺序里还有一层考虑：** `GET stock` 必须是**第一步**。因为"未预热"是一个比"售罄/重复"更严重的问题（说明配置或运维出了问题），必须优先暴露并且必须快速失败——绝不能因为"当作有库存"就放过去打 DB。

**追问：** 如果反过来先判库存再判重，会有什么实际影响？
**答：** 活动卖完后，一个已经买过的用户再次点击，会得到"已售罄"而不是"您已参与过"。用户会以为是自己手慢，可能反复重试、投诉，而实际上是"你已经买过了"。这是一个纯体验问题、不影响正确性，但**用户体验的细节恰恰是面试官用来判断你有没有真的站在用户角度想过问题**。

> 💡 **面试官意图：** 代码里 if 的顺序往往是设计意图最直接的体现，这题考的是"你有没有想过为什么要这样排"。

---

### Q10. 秒杀接口的返回码为什么要设计成 0/1/2/3，而不是布尔值？

**结论：** 因为**调用端需要对不同情况做不同处理**，布尔值会把这个信息丢掉。

| 返回 | 含义 | Java 侧处理 |
| --- | --- | --- |
| `0` | 扣减成功 | 正常投递 MQ |
| `1` | 库存不足（售罄） | `markSoldOut()` + 返回 `SECKILL_SOLD_OUT` |
| `2` | 重复购买（一人一单） | 返回 `SECKILL_REPEAT`，**不标记售罄** |
| `3` | 未预热 / 预热数据已过期 | 打 ERROR 日志 + 返回 `SECKILL_NOT_PREPARED`，**绝不降级查库** |

关键差异在 1 和 3 之间：`1` 是正常业务结果，只需要标记本地售罄；`3` 是**故障信号**，需要 `log.error("秒杀活动未预热，请检查预热任务 activityId={}")` 告警，并且**必须快速失败**——如果把它当成"有库存"放过去，请求会直接打到数据库，预热就失去意义了，而且此时无法保证不超卖。

**追问：** 为什么"未预热"不能降级查数据库？
**答：** 因为降级查库之后你**没有地方做原子扣减**了。走 DB 就得用 `UPDATE ... WHERE available_stock >= 1`，那 Redis 预热这一层就完全白做了，几万 QPS 会直接砸到 MySQL。更糟的是会造成语义混乱：这次走 DB 扣了、下次 Redis 恢复了又走 Redis，两套库存来源同时生效，必然漂移。**在无法保证正确性的情况下，快速失败比"尽力服务"更负责。**

> 💡 **面试官意图：** 考"错误码设计"和"故障时该失败还是该降级"的判断。所有降级都需要前提条件，无脑降级是灾难。

---

### Q11. `SeckillStockService` 为什么要把所有秒杀 Redis 操作收敛到一个类里？

**结论：** 让「Hash Tag 一致性」这件事只需要在**一个地方**保证。

该类注释写得很直白："所有涉及秒杀库存的命令都从这里发出，好处是 Hash Tag 一致性这件事只需要在一个地方保证。类初始化时还会做一次同槽自检，把配置错误暴露在启动阶段而不是运行时。"

如果散落在多个 Service 里，任何一处新增 key 而忘了加 tag，就是一上集群就崩的定时炸弹。收敛之后：
- `seckillKeys(activityId)` 是唯一的 key 组装点，顺序与 Lua 的 `KEYS[1..3]` 严格对应；
- `@PostConstruct assertSameSlot()` 只写一次就覆盖全部路径；
- `rollback()` 里"回补后必须清本地售罄标记"这条纪律不会被漏掉。

**追问：** 这种"收敛到一个出口"的模式你还在项目里用在哪？
**答：** `RedisKeys`（所有 key 只能从这里生成，禁止业务代码手写字符串拼接）、`ReliableMessageSender`（所有 MQ 发送的唯一出口，统一挂 messageId / 持久化 / contentType 属性）、`SeckillCompensationService`（全链路唯一的补偿入口，三层防重只实现一次）、`MallMetrics`（埋点统一走它，计数器句柄用 `ConcurrentHashMap` 缓存避免重复拼接标签）。

> 💡 **面试官意图：** 看你的代码组织意识。这类"防止未来某个人写错"的设计，是工程师和码农的分界线。

---

### Q12. `SeckillOrderMessage` 里为什么要带 `totalStock`？

**结论：** 因为回补脚本需要它做**总库存封顶**，而消费端未必能方便地拿到活动的最新总量。

回补 Lua 的 `ARGV[2]` 就是 `totalStock`：
```lua
local current = tonumber(stock)
if current >= totalStock then return current end   -- 到顶了，什么都不做
local newStock = redis.call('INCR', stockKey)
```

`SeckillCompensationService` 里还有 `msg.getTotalStock() == null ? Integer.MAX_VALUE : msg.getTotalStock()` 的兜底——拿不到总量时传 `Integer.MAX_VALUE` 等于**关掉封顶**，这是有意为之的降级：宁可让封顶失效（由 DB 的 `available_stock + num <= total_stock` 兜底）也不要因为参数缺失直接拒绝补偿（那样库存会永久占用）。

**追问：** 把 `totalStock` 放在消息里会不会有"消息里的总量已经过时"的问题？
**答：** 会，而且这是真实存在的。如果活动进行中运营手工把 `total_stock` 调大了，消息里的旧值会让封顶过早生效（少卖一点）；调小了则消息里的旧值偏大，封顶会宽松一些（仍由 DB 层的条件更新兜住）。**关键判断是：这个失真只会导致"少卖"，不会导致"超卖"**，所以可以接受。如果要更精确，应该让消费端实时查一次活动表拿 `total_stock`——但那就多了一次 DB 查询，在热路径上是实打实的成本。这里选择了"接受一点少卖风险换取一次 DB 查询"。

> 💡 **面试官意图：** 消息体设计是常被忽略的点。能说清"消息里每个字段为什么在这"的人不多。

---

### Q13. 你怎么证明它真的不会超卖？（关键：用测试说话）

**结论：** 靠 `SeckillStockConcurrencyTest` 的并发断言，而不是"我相信 Lua 是原子的"。

```java
@Test
@DisplayName("800 并发抢 200 库存：成功数恰好 200，Redis 库存归零，绝不超卖")
void concurrentDeductNeverOversells() throws Exception {
    int stock = 200;
    int concurrency = 800;
    // ... 800 个虚拟线程，各自不同 userId，CountDownLatch 同时放行
    assertThat(success.get()).isEqualTo(stock);          // 核心断言 1：成功数 == 库存数
    assertThat(stockService.getStock(ACTIVITY_ID)).isZero(); // 核心断言 2：剩余恰好 0
    assertThat(soldOut.get()).isEqualTo(concurrency - stock); // 断言 3：拒绝数 == 请求数 - 库存
    assertThat(stockService.boughtCount(ACTIVITY_ID)).isEqualTo(stock); // 断言 4：集合大小 == 成功数
}
```

**为什么用 Java 21 虚拟线程而不是固定线程池？** 固定大小线程池只能做到"800 个任务分批排队执行"，并发度受池大小限制，**压不出真正的竞态**。虚拟线程创建成本极低，可以真正做到 800 个请求同时发出。

`oneUserCanOnlyBuyOnce()` 用同一 userId 打 500 次并发，断言 `success == 1`、`repeat == 499`、库存只被扣掉 1 件。

**追问：** 这四个断言分别能发现什么 bug？
**答：**
- 断言 1（成功数 == 库存）→ 抓**超卖**。这是最重要的一条。
- 断言 2（剩余为 0）→ 抓**少卖**。如果因为异常提前退出导致库存没扣完，这条会红。
- 断言 3（拒绝数 == 请求数 - 库存）→ 抓**重复计数**，比如某条路径既算成功又算拒绝。
- 断言 4（集合大小 == 成功数）→ 抓**一人一单记录丢失**，比如 `SADD` 被漏掉或 key 不同槽。

另外 `notPreheatedMustFailFast()` 断言未预热时返回 `NOT_PREPARED`（而不是放行），这条防的是"预热失效后流量穿透到 DB"。

> 💡 **面试官意图：** 面试里说"用 Lua 保证原子性不会超卖"是**没有说服力的**——任何人都可以这么说。有说服力的是"我写了这个测试，断言是这些，它抓住过这个 bug"。

---

### Q14. 预热任务怎么调度？应用重启后 Redis 里的库存没了怎么办？

**结论：** 三层保障。

1. **定时预热**：`SeckillPreheatJob` 周期调用 `preheatUpcoming(ahead)`，把 `end_time > now AND start_time < now + ahead` 的活动预热，`orderByAsc(start_time)`。单个活动预热失败 `try/catch` 掉，不影响其他活动。
2. **启动预热**：`ProductBloomFilter` 用 `ApplicationRunner` + `@Order(20)` 的启动钩子重建布隆过滤器；预热相关的 key 若在 `SECKILL_PREHEATED_SET` 里也会补预热。
3. **运行时兜底**：如果 Redis 里库存 key 真的丢了（被误删、`FLUSHDB`、主从切换丢数据），Lua 返回 `3`，`SeckillService` 打 ERROR 日志 `"秒杀活动未预热，请检查预热任务 activityId={}"`，用户拿到明确的失败提示而不是被静默放行到 DB。

**取舍：** 为什么不在"返回 3"的时候**自动重新预热一次**？因为自动预热意味着**在流量洪峰中去读 DB 写 Redis**，而此刻 Redis 出问题、DB 大概率也在承压。更大的风险是：如果丢失的原因是主从切换导致数据回退，自动预热会用 DB 的 `available_stock` 覆盖 Redis——**这个覆盖方向本身是对的**（DB 是权威），但多个实例同时触发会形成写入风暴。所以选择"快速失败 + 告警 + 人工确认"，宁可短暂不可用也不让故障扩散。

**追问：** `ttlFor()` 为什么要 `+1h` 且"不足 30 分钟则取 1 小时"？
**答：** 秒杀活动结束时，队列里可能还堆着正在处理的请求（回调、对账、补偿都需要读库存 key）。如果 TTL 精确等于 `endTime`，活动结束瞬间这些请求就会拿到"key 不存在"，被误判为 `NOT_PREPARED` 并打 ERROR 告警——**制造一堆假警报，把真正的故障淹掉**。多留 1 小时是给收尾流量留缓冲；"不足 30 分钟取 1 小时"是防止对一个即将结束的活动设置出几秒的 TTL，那种情况下缓存等于没有。

> 💡 **面试官意图：** 考"初始化/恢复路径"的完整性。只写了 happy path 的服务是上不了生产的。

---

### Q15. 你说 Lua 是原子的，那 Redis 主从切换时会不会丢扣减？

**结论：** 会。这是这个方案的**已知且已接受的代价**，也是我主动会向面试官坦白的点。

Redis 主从复制默认是**异步**的。`EVAL` 在主节点执行成功并返回 `0`（扣减成功），但 `DECR` 还没同步到从节点时主节点宕机，哨兵/集群选主后新主节点上**没有这次扣减**——于是库存多了 1，`bought` 集合里也没有这个用户。结果：同一个用户可以再抢一次，库存比真实多 1，**可能超卖**。

**本项目的三层缓解：**

1. **`bought` 集合的作用在这里体现出来**：即使 Redis 库存回退了，用户的"已购"记录如果在 DB 里落库了，`uk_user_activity` 唯一键会挡住第二次下单（订单号不同但 `(user_id, activity_id)` 相同）。所以真正的超卖防线始终是 **MySQL 唯一索引**，不是 Redis。
2. **对账任务兜底**：每 5 分钟比对 `Redis 库存 vs (DB 剩余 - 在途)`，`drift ≠ 0` 即告警并修复。
3. **配置层面**：`redis-server --appendonly yes`（AOF 开启）+ `maxmemory-policy noeviction`（不会因为内存压力淘汰库存 key）。

**更彻底的方案与代价：**
- `WAIT 1 <timeout>`：强制等待至少 1 个副本确认，但这会让每次扣减多一次 RTT，秒杀场景下不可接受。
- Redis 官方 RedLock：实现复杂、争议大（Kleppmann vs Antirez），而且它解决的是"锁的强一致"，不是"写操作的强一致"。
- 用 `min-replicas-to-write` + `min-replicas-max-lag`：可以降低丢写窗口，代价是主从网络抖动时**写直接失败**——秒杀场景下这等于自己把服务打挂。

**我最终的判断：** 秒杀场景下，"Redis 短暂不可写"比"极小概率丢一次扣减"更不可接受（前者是 100% 失败，后者是万分之一的不一致且能被对账发现）。所以选择接受这个窗口，**并把权威性交给 MySQL**。

**追问：** 那如果 Redis 整体挂了（不是主从切换），秒杀接口会怎样？
**答：** 会 fail-fast。`redis.execute(...)` 抛异常 → `SeckillService` 的调用链抛出 → 全局异常处理器返回 5xx。这是**有意为之**：Redis 挂了就没有原子扣减能力，此时"拒绝服务"比"降级到 DB 直接被打穿"更负责任。详见 Q95（故障演练·Redis 挂了）。

> 💡 **面试官意图：** 这是**最高含金量的追问之一**。能主动、准确地讲出自己方案的失败模式，是 senior 和 junior 的分水岭。答"Lua 是原子的所以不会丢"会被立刻判定为只有理论没有实践。

---

## 四、缓存（16 题）

### Q16. 为什么要做多级缓存？单级不行吗？

**结论：** 单级无法同时满足"低延迟"和"共享/大容量"。

| 层级 | 介质 | 延迟量级 | 容量 | 多实例一致性 |
| --- | --- | --- | --- | --- |
| L1 | Caffeine（进程内） | 纳秒级，零网络 | 受堆限制（`l1-max-size = 10000`） | **无法被其他实例失效** |
| L2 | Redis | 毫秒级，1 次 RTT | 大 | 天然共享 |
| L3 | MySQL | 数毫秒~数十毫秒 | 全量 | 权威 |

只留 Redis：每次商品详情都要一次网络往返，热点商品上这个 RTT 会被放大到 Redis 的连接数和带宽上。
只留 Caffeine：多实例之间数据不一致且无法互相失效；容量受限；重启全丢。

**取舍：** 引入 L1 的**代价就是不一致窗口**，`mall.cache.product.l1-ttl = 3s` 就是"多实例部署下的不一致窗口上界"。收益是 **Redis 的读 QPS 直接降一个数量级**。如果业务不能容忍 3 秒，只有两条路：砍掉 L1，或者引入 Redis Pub/Sub 广播失效消息——后者的代价是"消息丢失 = 永久不一致"，**所以无论如何都必须保留一个 TTL 兜底**。

**追问：** 这个"降一个数量级"是你拍脑袋说的还是有依据？
**答：** 有依据，靠 `mall.cache.access` 这个指标。它在 `ProductCacheService` 的每一层出口处打点：`level = l1 / l2 / bloom / lock / db`，`result = hit / miss / null / reject / wait_success / wait_timeout / double_check_hit`。`loadFromDb()` 里也有一行 `metrics.cacheAccess(CACHE_NAME, "db", "load")`，注释写得很直白："这是判断'缓存到底有没有挡住数据库'的唯一可信依据。测试里也直接断言这个计数器——比'看起来变快了'这种主观判断可靠得多。" 用 L1 hit 数 / 总请求数就能直接算出挡住的比例。

> 💡 **面试官意图：** 考"多级缓存的一致性代价"你有没有认。答"多级缓存更快所以就这么做了"是最差的答案。

---

### Q17. L2 为什么要区分"逻辑过期"和"物理过期"？

**结论：** 因为两者解决的问题不同，混用就会踩坑。

- **物理 TTL（`l2-physical-ttl = 30m`）**：key 真的从 Redis 消失。这是**内存回收**机制。
- **逻辑过期（`l2-logical-expire = 3m`）**：数据还在，只是包装体里的 `expireAt` 字段标记它"陈旧"。这是**重建触发**机制。

`CacheValue.logicallyExpired(clock)` 检查逻辑过期时间；命中逻辑过期时 `ProductCacheService` **直接返回旧值**并 `asyncRebuild(productId)`，用户完全不感知。

**为什么物理 TTL 必须远大于逻辑 TTL（30m >> 3m）？** 因为如果物理 TTL 更短，逻辑过期还没到 key 就被删了——那就退化成"缓存 miss → 所有并发回源"，防击穿的效果完全失效。30m 给了逻辑过期 10 倍的余量，保证"陈旧但可用"这个状态一定存在。

**取舍：**
- 逻辑过期的**代价是数据会短暂陈旧**（最长 3 分钟）。商品详情能容忍。
- 备选是"物理 TTL + 互斥锁"，即本项目对**冷 key** 用的方案。它没有陈旧问题，但热点 key 失效瞬间会有大量请求在锁上排队（虽然只有一个查库，但其他请求都要等）。
- **两者是互补关系，不是二选一**——见 Q24。

**追问：** 逻辑过期返回旧值时，前端会不会看到"库存显示有货但下单失败"？
**答：** 会，但这是**分工明确**的：缓存负责"展示层"的最终一致，交易链路负责"正确性"。用户下单时走的是 `SeckillService` → Redis Lua，**价格和库存的权威判定都不读商品详情缓存**。所以"详情页显示的价格/库存略有延迟，但下单一定以实时为准"是设计目标，不是缺陷。真正不能容忍陈旧的场景（比如价格变更需要立即生效）应该走 `invalidate()` 主动失效，而不是等逻辑过期。

> 💡 **面试官意图：** 逻辑过期是个"知道名词但说不清边界"的高发区。追问"物理 TTL 该设多大"能立刻分辨真假。

---

### Q18. 缓存穿透、击穿、雪崩，本项目分别怎么防？

**结论：** 三个问题三套手段，`ProductCacheService` 的类注释里直接列了一张表：

| 问题 | 手段 | 代码位置 |
| --- | --- | --- |
| **穿透**（查不存在的数据） | 参数校验 + 布隆过滤器 + 空值缓存 | `getDetail` 第 0 / 2.5 / 2.6 步 |
| **击穿**（热点 key 失效瞬间） | 逻辑过期 + 异步重建（热 key）；互斥锁 + 双重检查（冷 key） | `getDetail` 第 2 / 4 步 |
| **雪崩**（大批 key 同时失效） | 物理 TTL 加随机抖动 | `writeCache` |
| **读放大**（DB 压力） | L1 本地缓存挡住绝大部分重复读 | `getDetail` 第 1 步 |

**穿透的三层是"缺一不可"的**（`ProductBloomFilter` 类注释）：
1. **布隆过滤器**：拦截"一定不存在"的 ID，内存极小，但只会误判"存在"、不会误判"不存在"；
2. **空值缓存**：防的是**布隆过滤器放过去的误判流量**，以及商品删除后的重复查询；
3. **参数校验**：`productId <= 0` 直接返回，成本最低、最常被忽略。

**雪崩抖动的实现：**
```java
long base = pc.getL2PhysicalTtl().toMillis();          // 30m
long jitter = (long) (base * pc.getL2TtlJitterRatio()); // 30m * 0.2 = 6m
long ttl = base + (jitter > 0 ? ThreadLocalRandom.current().nextLong(jitter + 1) : 0);
```
失效时刻被散开到 `[30m, 36m]` 区间，而不是全体在 30 分整集体失效。

**追问：** 布隆过滤器不支持删除，商品下架后怎么办？
**答：** 下架商品的 ID 仍然在过滤器里（`mightContain` 返回 true），请求会穿透到第 2 层，被**空值缓存**兜住——这正是为什么两层都要有。如果要支持删除，应换成 Counting Bloom Filter 或 Cuckoo Filter。项目里 `ProductBloomFilter.add()` 在下架/删除时也不会去删（因为删不掉），而是依赖空值缓存 + 短 TTL。**代价是：一个"刚下架的商品被高频查询"会消耗一次 DB 查询 + 一次空值缓存写入。**

> 💡 **面试官意图：** 送分题，但"三层为什么缺一不可"是加分点。只答"布隆过滤器防穿透"就只能拿基础分。

---

### Q19. 布隆过滤器为什么用 Redis 的而不是 Guava 本地的？

**结论：** 本地版本每个实例各存一份，多实例下需要同步，且**重启就丢失**。放在 Redis 里所有实例共享同一份判断，代价只是每次判断多一次网络往返——而这个往返被 L1 本地缓存挡住了，实际影响可忽略。

参数：`bloom-expected-insertions = 100000`，`bloom-fpp = 0.01`。类注释给了容量参考："1 亿个元素、1% 误判率只需约 114MB，用 Redis Bitmap 实现"。按 `m = -n·ln(p) / (ln2)²` 估算，本项目 10 万元素、1% 误判率大约只需要 120 万 bit ≈ **143KB**。

**关键实现细节：`tryInit` 是幂等的。** 代码里显式判断：
```java
boolean initialized = filter.tryInit(expectedInsertions, fpp);
if (initialized) {
    // 只有当次调用真正创建了过滤器时，才从 DB 灌入商品 ID
    ids.forEach(filter::add);
} else {
    log.info("布隆过滤器已存在，复用 Redis 中的既有数据");
}
```
Redis 里已存在则返回 `false`，**不会清空已有数据**。这一点非常重要：多实例同时启动时不能互相把过滤器重置掉，否则会有短暂的"过滤器为空 → 所有请求放行"窗口。

**降级策略：** 初始化失败只打 ERROR **不阻塞启动**（注释："布隆过滤器不可用不应该阻塞启动：它只是一层优化，空值缓存仍然能兜住穿透"）；`mightContain` 查询失败时**返回 true（放行）**；`ready == false` 时也一律返回 true。**所有降级方向都是"放行"而不是"拒绝"**——因为误拒绝 = 用户查不到存在的商品，误放行 = 多一次 DB 查询。

**追问：** 为什么不选 Guava 本地版本 + 定时全量同步？
**答：** 同步有延迟窗口（延迟期间新商品的 ID 不在过滤器里，会被误判为"一定不存在"而**返回 null，这是假阴性！**）。布隆过滤器的核心保证是"不存在的一定能判出来，存在的可能误判"，一旦本地副本落后，这个保证就被破坏了。放 Redis 里虽然多一次 RTT，但**保证是实时且全局一致的**。这是"少一次网络往返"和"不破坏算法核心保证"之间的选择，答案很明确。

> 💡 **面试官意图：** `tryInit` 幂等性 + "降级方向必须都是放行"是两个高级考点，能答出来说明你真的写过。

---

### Q20. 空值缓存的 TTL 为什么是 60 秒？写入真实数据时要注意什么？

**结论：** 60 秒是"防穿透有效"和"新商品可及时被发现"之间的折中。

- **太长**（比如 10 分钟）：商品可能马上就创建出来了，但在这段时间内查询会一直返回 null，用户看到"商品不存在"。
- **太短**（比如 5 秒）：攻击者可以在空窗期反复打，防穿透效果打折。

**写入真实数据时必须删掉空值 key，这是一个容易漏的点。** `writeCache()` 的最后一行：
```java
redis.opsForValue().set(RedisKeys.productCache(productId), json, Duration.ofMillis(ttl));
// 数据已经存在，空值缓存必须清掉，否则后续请求会先命中空值占位直接返回 null
redis.delete(RedisKeys.productNullCache(productId));
```
如果不删，`getDetail` 里第 2.5 步的 `redis.hasKey(productNullCache)` 会先命中，直接 `return null`——**真实数据写进去了但永远读不到**。这是一个"代码逻辑完全正确、缓存里数据也完全正确、但接口就是返回 null"的经典 Bug，非常难查。

**另一个细节：** 空值占位符用固定字符串 `"__NULL__"` 而不是 JSON。`ProductCacheService` 的注释："用固定字符串而不是 JSON，减少无意义的序列化开销"。而且它和真实缓存是**两个独立的 key**（`cache:product:null:{p:N}` vs `cache:product:{p:N}`，共用同一个商品维度 Hash Tag 以保证失效时可以一条 `DEL` 删掉——见 Q93 缺陷 ①），避免误把占位符当数据。L1 里也存 `NULL_SENTINEL`，`unwrap()` 时统一转换。

**追问：** L1 里缓存了空值，那商品被创建之后这个实例要等多久才能发现？
**答：** 最多 `l1-ttl = 3s`。`invalidate()` 会同时清掉 L1 和 L2 的空值 key：
```java
localCache.invalidate(productId);
redis.delete(List.of(productCache(productId), productNullCache(productId)));
```
但如果是**另一个实例**创建的（不会触发本实例的 `invalidate`），就只能等 3 秒 TTL 自然过期。这和 Q16 讲的不一致窗口是同一个上界。

> 💡 **面试官意图：** "写入真实数据必须删空值 key"是实战型问题。没踩过这个坑的人答不出来。

---

### Q21. 冷 key 和热 key 的击穿为什么要用两套方案？

**结论：** 因为两类 key 的失败模式完全不同，一套方案解决不了。

| 场景 | 缓存里有没有数据 | 用什么 | 为什么 |
| --- | --- | --- | --- |
| **热 key 逻辑过期** | 有（陈旧） | 逻辑过期 + 异步重建 | 旧数据可用，直接返回不阻塞，重建在后台做 |
| **冷 key 缓存 miss** | 没有 | 互斥锁 + 双重检查 + 自旋等待 | 没有任何数据可返回，必须有人去查库，但只能一个人去 |

`ProductCacheService` 类注释说得很清楚："『逻辑过期』要求缓存里已经有数据，它解决的是**热 key 过期**；但冷 key（第一次访问、被 LRU 淘汰、Redis 重启后）根本没数据，此时所有并发请求会一起回源，必须靠**互斥锁**把它们收成一次查询。两者是互补关系，不是二选一。"

**关键实现细节：没抢到锁的请求不是去查库，而是自旋等缓存被填好。**

```java
if (!locked) {
    metrics.cacheAccess(CACHE_NAME, "lock", "contended");
    return waitForCacheFilled(productId);
}
```
注释把理由说得非常直白："如果没抢到锁的请求直接查库，那'加锁'就只保护了一个请求，数据库依旧被 N-1 个请求打穿。"

`waitForCacheFilled` 自旋 3 次、每次 `sleep(50)`，总共约 **150ms**。超过就降级直查 DB（"宁可给数据库一点压力，也不能让用户一直卡着"）。

**一个刻意的设计：等待超时后【不回写缓存】。**
```java
// 注意：这里刻意【不】回写缓存。
// 因为当前线程没有持锁，回写会与持锁者的重建产生竞争，
// 有可能用一份较旧的数据覆盖掉刚写好的新数据。
return loadFromDb(productId);
```
这是"用旧数据覆盖新数据"的经典竞态。**没持锁就不要写共享状态**，这条纪律比"顺手回填一下"重要得多。

**追问：** 双重检查（double check）是必须的吗？
**答：** 不是正确性必须，但能省掉大量无用的 DB 查询。从"发现 miss"到"拿到锁"之间，前一个持锁者可能已经回填完成了。没有双重检查，这次就会产生一次**完全多余的数据库查询**。代码里抢到锁后立刻再读一次 Redis，命中就打 `double_check_hit` 指标。在一个热点 key 被高频访问的场景，这个比例并不低。

> 💡 **面试官意图：** "加锁但不让未持锁者等待"是最常见的错误实现。能指出"必须先自旋等缓存填好"说明你理解加锁的目的。

---

### Q22. Cache Aside 的"写"侧为什么是删缓存而不是更新缓存？

**结论：** 更新缓存在并发下会写入不一致的数据，而**删除是幂等的**——删两次和删一次效果一样。

`ProductCacheService.invalidate()` 的注释："更新缓存在并发下会写入不一致的数据（两个写请求的 DB 提交顺序与缓存写入顺序可能相反，最终缓存里留下的是旧值），而删除是幂等的。"

具体场景：
```
线程 A: 更新 DB（值=1）... 更新缓存（值=1）
线程 B:     更新 DB（值=2）...   更新缓存（值=2）
实际执行顺序: A改DB → B改DB → B改缓存(2) → A改缓存(1)
结果: DB 里是 2，缓存里是 1 —— 永久不一致
```
删缓存的话，最终无论谁先谁后，缓存都是空的，下次读会从 DB 加载正确的 2。

**本项目还有一个"延迟双删"的兜底：`mall.cache.double-delete-delay = 500ms`。**

**追问：** 延迟双删解决的是什么问题？为什么是 500ms？
**答：** 解决"读请求回填旧值"的竞态：
```
写线程:  更新DB ──────────────────── 删除缓存
读线程:      读DB(旧值) ── 回填缓存(旧值) ──────► 缓存里留下旧值！
```
第二次延迟删除就是把这个漏网的旧值清掉。500ms 的依据是"一次缓存回源（含 DB 查询 + 序列化 + Redis 写入）的 P99 时间"——必须显著大于它，才能保证读线程的回填动作一定已经完成。**这个值的代价是：在 500ms 内如果有新的写请求，它可能删掉一个刚刚被正确回填的缓存**，造成一次多余的 DB 查询。收益（消灭永久不一致）远大于代价。

**但要说清楚：延迟双删只是兜底，主路径是"写 DB 后立刻删缓存"。** 双删不能解决"读线程在第二次删除之后才开始回填"的极端情况——所以最终一致性仍然依赖 TTL（逻辑过期 3m / 物理 30m）。

> 💡 **面试官意图：** "为什么删而不是更新"几乎必问。追问延迟双删的时间怎么定，能测出你是背的还是推的。

---

### Q23. 缓存和数据库能不能做到强一致？你怎么宣称自己的方案？

**结论：** **做不到强一致**，我在简历和面试里都只说"最终一致"，并且会给出明确的不一致窗口上界。

**为什么做不到：**
1. DB 和 Redis 无法放在同一个事务里。跨资源事务需要 2PC / TCC，代价是可用性和性能的大幅下降——**用 XA 事务保护一个商品详情缓存，是典型的过度设计**。
2. 即使做到"写 DB 和写缓存原子"，还有"读请求回填旧值"这个绕过事务的路径（Q22 的时序）。这不是实现问题，而是 Cache Aside 模式本身的固有限制。

**本项目给出的三个"不一致窗口上界"：**
| 数据类型 | 不一致上界 | 依据 |
| --- | --- | --- |
| L1 本地缓存 | **3 秒** | `l1-ttl = 3s` |
| L2 逻辑过期 | **3 分钟** | `l2-logical-expire = 3m`（陈旧数据可读，异步重建） |
| 主动失效后的延迟双删 | **500ms** | `double-delete-delay = 500ms` |
| L2 物理 TTL | **30 分钟（±20% 抖动）** | `l2-physical-ttl` + `l2-ttl-jitter-ratio = 0.2` |

**"上界"这个词很重要。** 说"最终一致"是模糊的，面试官会追问"多久"。说"3 秒/3 分钟"是可验证、可监控、可以和产品对齐的。

**追问：** 如果产品要求"商品下架必须立刻生效"，你这个方案能满足吗？
**答：** 能满足，但要走主动失效而不是等 TTL：`ProductCacheService.invalidate(productId)` 会同时清 L1 和 L2。**但有个前提**——多实例部署时 `invalidate` 只能清掉**本实例**的 L1。所以要"立刻全实例生效"，必须引入广播机制（Redis Pub/Sub、或者用消息队列推一条失效消息），并且必须配 TTL 兜底（广播消息会丢）。

**还有一个更强的方案**：把"下架"这个状态做成**独立的高优先级判断**，比如在 L1 缓存里额外存一个 `status` 字段，或者在读路径前加一层 Redis 的 `Set` 存"已下架 ID"。**但这是用复杂度换实时性，只有在业务真的不能容忍 3 秒时才值得做。** 本项目的判断是：商品详情能容忍 3 秒，所以只做 TTL。

> 💡 **面试官意图：** "能不能强一致"是检验你有没有被 CAP 洗脑过的题。声称强一致的一律要追问到崩。

---

### Q24. 为什么两种防击穿手段都要有？（重复但必问，值得单独背）

**结论：** 因为它们覆盖的是**互斥的场景**，不是同一场景的两种做法。

`ProductCacheService` 的注释原文："『逻辑过期』要求缓存里已经有数据，它解决的是**热 key 过期**；但冷 key（第一次访问、被 LRU 淘汰、Redis 重启后）根本没数据，此时所有并发请求会一起回源，必须靠**互斥锁**把它们收成一次查询。两者是互补关系，不是二选一。"

| 条件 | 逻辑过期 | 互斥锁 |
| --- | --- | --- |
| 缓存里有陈旧数据 | ✅ 生效 | 用不上（直接返回旧值） |
| 缓存里什么都没有 | ❌ 无从触发 | ✅ 生效 |
| 用户是否阻塞 | 不阻塞 | 未持锁者自旋最多 150ms |
| 是否需要额外组件 | 需要包装体 + 线程池 | 需要 Redisson |

**追问：** 那我能不能只用互斥锁，不要逻辑过期？
**答：** 可以，但你会失去"不阻塞"这个性质。热 key 逻辑过期的场景下，用互斥锁意味着**几千个请求要在锁上排队最多 200ms**（`rebuild-lock-wait = 200ms`），然后大部分自旋等待 150ms。秒杀活动期间商品详情页的 RT 会明显抖动。逻辑过期的价值就是**让用户完全无感**——返回旧值，重建在后台。**代价是数据会陈旧最多 3 分钟。**

> 💡 **面试官意图：** 很多人只会说"我用了逻辑过期防击穿"，被追问"缓存里没数据时呢"就卡住了。

---

### Q25. L1 本地缓存怎么在多实例部署下保持可用？

**结论：** **不保持强一致，而是把不一致窗口做成显式可控的参数。**

`ProductCacheService` 的注释："本地缓存无法被其他实例删除，所以 `mall.cache.product.l1-ttl` 就是**多实例部署下的不一致窗口上界**。本项目设为 3 秒：商品详情能容忍 3 秒延迟，换来的是 Redis 的读 QPS 直接降一个数量级。"

三种可选方案：

| 方案 | 一致性 | 复杂度 | 本项目选择 |
| --- | --- | --- | --- |
| 裸 TTL（3s） | 窗口内不一致 | 最低 | ✅ **选它** |
| Redis Pub/Sub 广播失效 | 秒级（消息可能丢） | 中（需处理重连、消息丢失、频道风暴） | ✗ |
| 广播表 / 消息队列推失效通知 | 秒级（可持久化） | 高（要多一套可靠投递） | ✗ |

**为什么没选 Pub/Sub：** ① Redis 的 Pub/Sub **不持久化**，实例断线重连期间的消息会直接丢失，而丢失的后果是"那个实例永久持有旧数据"——**必须有 TTL 兜底，那 TTL 就不是 3 秒而是更长了，收益打折**；② 广播风暴：一个热商品被频繁更新时，每个实例都要处理海量失效消息；③ 引入这套机制后，"缓存重建"和"缓存失效"两套并发逻辑要互相协调，复杂度显著上升。

**为什么没选广播表：** 需要额外的可靠投递（就变成 MQ 的可靠性问题了），为了一个"3 秒可容忍"的场景引入一整套可靠消息，投入产出比明显不划算。

**但我会说清楚什么时候该选它**：如果商品详情是交易链路的关键数据（比如价格、库存必须实时），那 3 秒不可接受，此时必须上广播机制，并且接受"消息丢失要靠更长 TTL 兜底"这个现实。

**追问：** `invalidateAllLocal()` 是干什么的？
**答：** 提供管理端/运维用的全量清空入口（`localCache.invalidateAll()`）。它的典型用途是**紧急修正**：发现某个实例持有严重过期的数据且 TTL 还没到，人工触发一次全量清空。**但这个接口是危险的双刃剑**——如果被高频调用，L1 就完全失效了，所有流量直击 Redis。所以它必须挂在鉴权后面并且限流（回到 Q74 的讨论）。

> 💡 **面试官意图：** 考"接受不一致"的工程判断力，以及"备选方案的代价"能不能说清楚。

---

### Q26. Caffeine 相比 Guava Cache，你主要用到哪些能力？为什么用 `expireAfterWrite` 而不是 `refreshAfterWrite`？

**结论：** Caffeine 用 **Window TinyLFU** 淘汰算法，命中率显著高于 Guava 的 LRU，而且 `expireAfterWrite` / `refreshAfterWrite` / `recordStats` / 异步加载这些能力更完整。本项目用了三个：

1. `maximumSize(pc.getL1MaxSize())` —— 10000
2. `expireAfterWrite(pc.getL1Ttl().toMillis(), MILLISECONDS)` —— 3 秒
3. `recordStats()` —— 支撑 `localCacheStats()` 暴露 `CacheStats` 给监控端

**为什么用 `expireAfterWrite` 而不是 `refreshAfterWrite`？** 代码注释原话：

> "`expireAfterWrite`：写后固定时间过期。这里不用 `refreshAfterWrite`，因为本地缓存的重建也应该走『异步 + 互斥』的逻辑，而不是 Caffeine 自己的语义。"

深入解释：`refreshAfterWrite` 会触发 Caffeine 自己的 `LoadingCache` 刷新语义（同步或异步加载），**它没有"跨实例互斥"的能力**。如果 10 个实例同时对一个 key 触发刷新，就会产生 10 次 DB 查询。而 L2 的重建是走 Redisson 锁的（只有一个实例能抢到，其他实例等它填 Redis），所以 L1 的重建必须**跟着 L2 走同一套逻辑**，不能被 Caffeine 自己接管。

**取舍：** `expireAfterWrite` 的代价是"到点就失效"——3 秒一到，第一个请求必须回 L2 拿数据（多一次 RTT）。而 `refreshAfterWrite` 可以做到"返回旧值 + 后台刷新"（用户无感）。**我们主动放弃了 Caffeine 的这个能力，换来的是"重建逻辑只有一套"**——这比省一次 RTT 重要得多。

**追问：** `rebuilding` 这个 `ConcurrentHashMap.newKeySet()` 是干什么的？
**答：** 是异步重建任务的**提交前去重**。逻辑过期时每个读请求都会尝试提交一次重建任务，热门商品在过期瞬间可能同时有几千个请求，如果全都提交，线程池队列会被瞬间打满（虽然最终只有一个能拿到 Redisson 锁）。这个 Set 把提交量压到"每个 key 同时最多一个"。
**它的注释特别强调了它的定位**："它是纯优化，即使判断失效也不会出错——真正的互斥仍由 Redisson 锁保证。"这是个很好的设计分寸：**优化层不需要保证正确性，正确性由更可靠的一层负责。**

> 💡 **面试官意图：** "为什么不用 refreshAfterWrite"是极少数人会问但极能分辨水平的问题——它测你知不知道框架能力会和你的分布式逻辑打架。

---

### Q27. 为什么用 `StringRedisTemplate` + JSON，而不是 `RedisTemplate<String,Object>`？

**结论：** 因为 `GenericJackson2JsonRedisSerializer` 和 JDK 序列化都有**真实的长期隐患**。`RedisConfig` 里写了三条理由：

| 序列化器 | 问题 |
| --- | --- |
| `GenericJackson2JsonRedisSerializer` | 往 JSON 里写 `@class` 全限定类名。类改名/挪包 → 历史缓存**全部反序列化失败**；而且"反序列化任意类"是**真实的攻击面** |
| `JdkSerializationRedisSerializer`（默认） | 写出二进制，`redis-cli get` 全是乱码，线上排查基本靠猜 |
| **`StringRedisTemplate` + `JsonUtils`** ✅ | 存了什么一目了然，序列化行为由 `JsonUtils` **一处**决定 |

**这个决策和 MQ 的消息格式决策是同一个思路的两次应用。** `ReliableMessageSender` 的注释讲了同样的问题：`Jackson2JsonMessageConverter` 会写 `__TypeId__ = com.lxt.mall.seckill.message.SeckillOrderMessage`，带来两个隐患：
1. **类名与消息格式被绑定死**：挪包改名后，MQ 里积压几天的旧消息立刻反序列化失败；
2. **反序列化任意类型的攻击面**：消息头里的类型是生产者写的，消费端无脑信任 + 缺白名单 = 远程代码执行入口（Spring AMQP 的历史 CVE 大多相关）。

所以消息体就是一份纯粹的、自描述的 JSON，消费端**显式指定**目标类型：`JsonUtils.fromJson(body, SeckillOrderMessage.class)`。

**取舍：** 自己控制序列化的代价是**失去了多态支持**——不能存"一个接口的任意实现"。但本项目的缓存和消息都是明确的单一类型，不需要多态。**用明确性换稳定性，这笔交易很值。**

**追问：** 反序列化失败时怎么处理？
**答：** `ProductCacheService.parse()` 里 `catch (Exception e)`：**打 WARN 后返回 null，让调用方当作 miss 处理，下次请求会重建**。注释："反序列化失败说明缓存里存的是脏数据（旧版本结构、人工写入等）。直接删掉让下次请求重建，比抛异常导致接口 500 合理得多。"
**注意这里的方向选择：** 缓存脏数据是**局部问题**，不应该升级成**接口 500**。降级的方向永远是"把它当作没有缓存"，而不是"把错误抛给用户"。

> 💡 **面试官意图：** 缓存序列化是所有项目都会做的决策，但很少有人能说清"为什么不用框架默认值"。这题能有效区分"跟着教程做"和"自己判断过"。

---

### Q28. `waitForCacheFilled` 超时后为什么不回写缓存，而是直接查库？

**结论：** 因为**当前线程没有持锁**，回写会与持锁者的重建产生竞争，有可能用一份较旧的数据覆盖掉刚写好的新数据。

代码注释原文：
```java
metrics.cacheAccess(CACHE_NAME, "lock", "wait_timeout");
// 注意：这里刻意【不】回写缓存。
return loadFromDb(productId);
```

**竞态的具体时序：**
```
T0  线程 A 拿到锁，开始查库（读到旧值 v1，因为此刻 DB 还没更新）
T1  线程 B 等待 150ms 超时，也去查库（读到了新值 v2，因为 DB 已更新）
T2  线程 A 回写缓存 v1
T3  线程 B（无锁）回写缓存 v2 ——— 如果 B 也回写，通常是好事
```
但反过来也可能是：
```
T0  线程 A 拿锁查库读到 v2，回写缓存 v2
T1  线程 B 等待超时，读库（此刻 DB 已经被另一个事务改成 v3）
T2  B 回写 v3  ← 这个其实也对
```
真正的问题是**顺序不确定**：如果 B 的查询在 T0 之前就开始了（读到 v1），而它的回写发生在 A 的回写之后，那么缓存里就留下了 v1。

**统一规则：没持锁就不写共享状态。** 违反这条规则的"优化"通常带来的是难以复现的不一致，而不是性能提升。这个规则在项目里出现了两次——`waitForCacheFilled` 不回写，以及 `ProductCacheService` 里所有 `localCache.put()` 都只在读路径或持锁路径中调用。

**追问：** 那这 150ms 的自旋等待，为什么用 `Thread.sleep(50)` 而不是 `Thread.onSpinWait()` 或自旋？
**答：** 因为这里等的是**Redis 的一次网络往返 + 一次 DB 查询**（毫秒到几十毫秒量级），用忙自旋会白白烧 CPU。`Thread.sleep(50)` 让出 CPU，50ms × 3 = 150ms 也大致覆盖了重建的 P99。如果重建是纯内存操作（几十微秒），那自旋才合适。**等待策略要匹配被等待操作的量级**——这是"不要过早优化"的一个具体体现。

> 💡 **面试官意图：** "不持锁就不写共享状态"是一条资深工程师的本能反应。这题能测出来。

---

### Q29. `ProductCacheService` 里 `InterruptedException` 为什么必须恢复中断标志？

**结论：** 因为吞掉 `InterruptedException` 不恢复中断位，会让上层线程池**无法感知停机信号**。代码注释直接说这是"并发编程里最常见的错误之一"。

```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // 恢复中断标志
    log.warn("获取缓存重建锁被中断 productId={}", productId);
    return loadFromDb(productId);
}
```

**为什么严重：** Tomcat 线程池 / `ThreadPoolTaskExecutor` 在优雅停机（`shutdown()` + `awaitTermination`）时，会通过中断来通知线程"该停了"。如果业务代码捕获了 `InterruptedException` 却不恢复标志，线程池就认为这个线程"没有响应中断"，可能导致：
- 停机超时后**强杀**，请求被硬切断；
- 更糟：线程继续保持 cancle 状态不明，`ThreadPoolExecutor` 无法正确回收。

**这个类里有三处**都做了恢复：`loadWithMutex` 的 catch、`waitForCacheFilled` 的 catch、`asyncRebuild` 的 catch。**一致的纪律**才能保证不遗漏。

**取舍：** 恢复中断标志之后又继续执行 `loadFromDb(productId)` 是**有意为之**——中断意味着"应该尽快结束"，而不是"应该立刻失败"。降级查库比抛异常给用户更好，而且这样能保证请求能尽快返回（配合后续的中断检查）。生产环境里还有 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(10)` 保证重建任务不会在停机时被硬切。

**追问：** `asyncRebuild` 里为什么要在 `finally` 里 `rebuilding.remove(productId)`？
**答：** 因为 `rebuilding` 是"正在重建中的商品 ID 集合"，如果异常路径不清理，这个 ID 会被**永久标记为"正在重建"**，后续所有的逻辑过期请求都会在 `rebuilding.add(productId)` 处返回 false 直接放弃——**缓存再也重建不出来，这个商品的数据会一直陈旧到物理 TTL 到期（30 分钟）**。
这里还有一个细节：`catch (RejectedExecutionException e)` 分支里也调用了 `rebuilding.remove(productId)`（因为任务根本没进线程池，`finally` 不会执行）。**每一个"提前失败"的分支都必须自己清理状态**，这也是很容易漏的地方。

> 💡 **面试官意图：** `InterruptedException` 的处理是 Java 面试的经典送分题，但大多数候选人答不出"为什么要恢复标志"和"不恢复会导致什么"。

---

### Q30. 大 key 和热 key 在这个项目里怎么处理？

**结论：** 本项目从**设计阶段**就避开了大 key，而不是上线后治理。

| 潜在大 key | 本项目的做法 |
| --- | --- |
| `seckill:bought:{act:N}` 已购用户 Set | 单活动内元素数 = 活动库存量级（本项目典型几百到几万），且**必须设 TTL**（`setBoughtSetExpire`，`max(24h, 活动剩余时间)`）。注释："已购集合必须设置 TTL：否则活动结束后它会永久占用内存" |
| 商品详情缓存 | `Product.detail` 是 `TEXT`（大字段），`ProductCacheService.loadFromDb()` 里按需裁剪——注意 `ProductDetailVO` 里有 `detail`，但**列表接口不应该读它**（`ProductVO` 是精简版） |
| 秒杀结果缓存 | 一个 orderNo 一个 key（`seckill:result:{orderNo}`），TTL 30 分钟，天然分散 |
| `seckill:preheated` 集合 | 只存已预热的活动 ID，量级极小 |

**几个通用规则（我会在面试里主动说）：**
1. **所有 key 必须有 TTL。** 项目里唯一没显式设 TTL 的场景，都是有明确生命周期管理机制的。`RedisKeys` 类注释的第一条规矺就是"所有 key 只能从这里生成"，这样查大 key 和做容量规划才可行。
2. **Key 命名固定为 `业务域:对象:标识`**，用冒号分层，`redis-cli --scan` 和 Redis Insight 能按层级过滤。
3. **Redis 配置 `maxmemory-policy noeviction`**。这是刻意的：**宁可写失败也不能让库存 key 被淘汰**——一个库存 key 被 LRU 淘汰 = 库存直接错乱。代价是内存打满时写操作会失败（这是"可见的失败"，比"静默的数据错误"好得多）。
4. **Set 类 key 只放 ID，不放对象**。放大对象会让同样的内存装下少 10 倍的元素。

**追问：** 如果 `seckill:bought` 真的膨胀到百万级（比如一个爆款活动），你怎么处理？
**答：** 几个方向，按代价从低到高：
- **控制活动库存总量**：秒杀活动的库存本来就是限量的，百万级已购说明活动设计有问题（比如把普通促销当秒杀做）。
- **拆分 key**：按 `userId % 16` 分片成 `seckill:bought:{act:N}:{shard}`，代价是**一人一单的判定变成 16 次查询**（或者要先算哈希定位第几片，一次查询）——但这样三 key 同槽的 Hash Tag 就要重新设计（`SISMEMBER` 的分片 key 和 `stock` key 不同槽就不能写在一个 Lua 里了）。
- **换成布隆过滤器**：一人一单只需判"是否可能存在"，误判方向是"以为买过 → 拒绝"（少卖，可接受）。但布隆过滤器不支持删除，而资格在补偿时需要回收（`SREM`）——所以需要 Counting Bloom Filter。
- **`SCAN` + 阈值告警**：先监控起来，不要盲目优化。

我会强调最后一点：**"先度量、再优化；而不是先优化、再找理由。"**

> 💡 **面试官意图：** 考你是不是真的想过"如果量级比我预期的大 100 倍会怎样"。也考你会不会无脑说"上 Redis Cluster 就好了"。

---

### Q31. Caffeine 里为什么存 `Object` 而不是 `ProductDetailVO`？

**结论：** 因为要同时容纳**真实数据**和**空值占位符**两种语义。

```java
/** L1：进程内缓存。value 要么是 ProductDetailVO，要么是 NULL_SENTINEL。 */
private final Cache<Long, Object> localCache;
public static final String NULL_SENTINEL = "__NULL__";
```

`unwrap()` 里统一转换：
```java
private ProductDetailVO unwrap(Object cached, String source) {
    if (NULL_SENTINEL.equals(cached)) return null;
    return ((ProductDetailVO) cached).copyWithSource(source);
}
```

**取舍：** 类型安全的代价。备选方案：
1. **用 `Optional<ProductDetailVO>`**：语义清晰，但 `Optional` 有额外的对象分配开销，而且 Caffeine 不支持 `null` value（所以必须用某种占位符）。`Optional` 本身也是一个包装对象——和直接存 `NULL_SENTINEL` 字符串相比，**字符串是更轻量的常量**（还能被 JVM 字符串常量池共享）。
2. **两个独立的 Cache**：一个存数据一个存空标记。要查两次，而且"数据存在还是空标记存在"的判断逻辑会散开。
3. **用 `Cache<Long, ProductDetailVO>` 存 null**：Caffeine 不允许 null value，直接抛 NPE。

**本项目选了最朴素但最省的方案**：一个 `Object` + 一个共享常量。代价是 `unwrap` 处有一次 `instanceof` / `equals` 判断，以及类型不安全的强制转换——但转换点只有一个，风险可控。

**追问：** 空值也在 L1 里缓存，那 L1 TTL 到期后会不会又打 DB？
**答：** 会，但这是**有意的**。空值在 L1 只缓存 3 秒（和其他 L1 数据一样），在 L2 缓存 60 秒。这个差异形成了**两层不同粒度的防护**：
- L1 的 3 秒防的是"同一个实例被同一批不存在 ID 高频打"（比如爬虫循环扫 ID）；
- L2 的 60 秒防的是"所有实例加起来被打"。
即使 L1 到期，请求也只是回 L2 拿空值标记（一次 RTT），不会真的打 DB。

> 💡 **面试官意图：** 细节题。看你是不是真的读过自己写的代码，还是只记得大概。

---

## 五、消息队列与最终一致性（24 题）

### Q32. 你的 MQ 可靠性链路是怎么搭的？从生产到消费完整说一遍。

**结论：** 生产侧四个保障 + 消费侧四个保障，一共八段。

**生产侧（`ReliableMessageSender` + `MessageSendTracker`）：**
```
① convertAndSend 抛异常（连接不可用等）→ 立即补偿，不等 Confirm 超时
② Confirm nack（broker 拒绝）           → pending 里取出上下文 → 补偿
③ Return 回调（无法路由到队列）          → 补偿（注意 basic.return 先于 basic.ack）
④ Confirm 超时（broker 静默失联）        → PendingMessageRetryJob 每 2s 扫描补偿
```
① 的注释说明了为什么不等超时："消息确定没有发出去，立刻补偿，不能等 Confirm 超时——用户等着结果，等 5 秒再失败体验太差。"

④ 是最容易被忽略的一环。`MessageSendTracker` 注释："**broker 静默失联时 Confirm 回调永远不会来**。没有超时兜底，这次抢购就变成'Redis 扣了库存、订单永远不创建'，库存凭空消失。"

**消费侧（`SeckillOrderConsumer`）：**
```
① 正常成功        → basicAck
② DuplicateMessageException（幂等命中）→ basicAck（正常现象，不是错误）
③ DuplicateOrderException（唯一键冲突）→ 回补 Redis 库存 → basicAck
④ NonRetryableMessageException         → 补偿 → basicNack(requeue=false) → 死信
⑤ 其他异常（瞬时故障）                  → republish 到延迟重试队列 → basicAck
⑥ 重试耗尽                             → basicNack(requeue=false) → DLX → 死信
⑦ republish 失败                       → basicNack(requeue=true)（保留消息等 broker 恢复）
⑧ 消息体无法解析                       → basicNack(requeue=false) → 死信
```

配置层面：`publisher-confirm-type: correlated`、`publisher-returns: true`、`template.mandatory: true`、`acknowledge-mode: manual`、`template.retry.enabled: false`、`listener.simple.retry.enabled: false`。

**取舍：没有用本地消息表（Outbox），而是用了内存台账 + 超时补偿 + 对账兜底。** 详见 Q44。

**追问：** 那你为什么不在发送后**同步等待** Confirm（比如 `waitForConfirms(5s)`）？异步回调 + 超时扫描不是更复杂吗？
**答：** 因为秒杀接口的 RT 必须与 broker 无关——**等一次 confirm 就意味着 RT 增加一个网络往返**（`ReliableMessageSender` 注释："投递结果不在这里同步等待……这是有意为之"）。而且 `waitForConfirms` 是**串行等待**，在多线程下会退化成对 channel 的争抢。
**代价必须说清楚：** 用户拿到的是"排队中"而不是结果，需要轮询；超时扫描有最长 7 秒的延迟（`confirmTimeout = 5s` + 扫描间隔 2s）。**这是我们用来换 RT 的东西。**

> 💡 **面试官意图：** 这是整个项目最能体现"可靠性工程能力"的一题。能完整说出这八段，说明你真的设计过而不是配了个 starter。

---

### Q33. Confirm 的 ack 不就代表消息投递成功了吗？为什么还要 Return？

**结论：** **Confirm 的 ack 只代表"broker 收下了"，不代表"路由到了队列"。**

`MessageSendTracker.onConfirm` 的注释："ACK 只代表'broker 收下了'，不代表'路由到了队列'——路由失败由 Return 回调负责。两者必须都处理，可靠性链条才完整。"

**具体失败场景：** routingKey 写错、队列被误删、Binding 丢失。此时 broker 的行为是：
```
broker 收到消息 → 找不到匹配的队列 → 丢弃消息 → 但仍然返回 basic.ack
```
生产者以为发送成功，消息却**静默消失**了。

**解法是 `mandatory=true` + `ReturnCallback`：** 开启 mandatory 后，无法路由的消息会通过 `basic.return` 送回生产者。`RabbitMQConfig` 的注释把这点强调为第 3 个必须讲清楚的设计点。

**最关键的细节：`basic.return` 会先于 `basic.ack` 到达。** 所以回调实现必须能处理这个顺序。

```java
public void onReturn(ReturnedMessage returned) {
    ...
    PendingSend send = pending.getIfPresent(messageId);   // 注意：getIfPresent 不是 remove
    compensate(send, "路由失败(return): " + returned.getReplyText() + ...);
}
```
注意这里是 `getIfPresent`（不是 `remove`）。`compensate` 内部在 `send == null` 时会跳过，所以：
- Return 先到 → 补偿执行 → `SeckillCompensationService` 的 SETNX 守卫**已经写入**；
- Confirm(ack=true) 后到 → `pending.invalidate(messageId)` → **空操作，不会重复补偿**。

**这个"防重复"不是在 `MessageSendTracker` 里做的**，而是收敛在 `SeckillCompensationService`（Q46 的三层防重）。`MessageSendTracker` 注释明确说了："两条路径都可能触发补偿，真正的防重逻辑统一收敛在 `SeckillCompensationService`。"

**追问：** 为什么用 `RabbitTemplateCustomizer` 而不是自己 `new RabbitTemplate`？
**答：** 自动装配的 `RabbitTemplate` 已经绑好了连接工厂、消息转换器、重试策略、observation 等一大堆东西，自己 new 会把这些**全部丢掉**。`Customizer` 是官方提供的、唯一"正确"的扩展点。这是一个"不要和框架对着干"的典型例子——很多人在这个位置选择 `@Bean` 覆盖掉自动装配的 `RabbitTemplate`，然后莫名其妙地失去了一些能力。

> 💡 **面试官意图：** "Confirm 不等于路由成功"是 MQ 可靠性的核心考点。知道 `basic.return` 先于 `basic.ack` 的属于加分项。

---

### Q34. 为什么用手动 ACK？为什么 ACK 一定要放在 `try` 的最后一个语句？

**结论：** 手动 ACK 是为了表达**至少五种不同的结果语义**；ACK 的位置是为了保证**所有可能失败的动作都发生在 ACK 之前**。

`SeckillOrderConsumer` 的注释列了这张表：

| 结果 | 动作 | 理由 |
| --- | --- | --- |
| 幂等命中 | ACK | 这是正常情况，不该重试 |
| 订单唯一键冲突 | 补偿后 ACK | 重试一万次结果一样 |
| 业务不可重试失败 | 补偿后 `nack(requeue=false)` 进死信 | 需要人看 |
| 瞬时故障（DB 抖动） | 投递到延迟重试队列后 ACK | 给它恢复时间 |
| 重试耗尽 | `nack(requeue=false)` 进死信 | 需要人看 |

"自动 ACK（`acknowledge-mode=auto`）下，Spring 在监听方法**正常返回**时就确认消息……自动 ACK 表达不了这么细的语义。"

**关于 ACK 的位置，注释更直接：**
> "如果先 ACK 再写结果缓存，缓存写失败时消息已经确认，重投不会发生，用户永远查不到结果。原则是：**所有可能失败的动作都必须发生在 ACK 之前**。反过来，ACK 之后也不能再做任何业务动作——那一瞬间消息已经不可恢复了。"

代码严格遵循：
```java
try {
    payload = parse(message);
    orderCreationService.createFromSeckill(payload);   // ← 业务动作（含写结果缓存）
    channel.basicAck(deliveryTag, false);              // ← 最后一句
    metrics.mqEvent("seckill", "consume_success");      // ← 埋点，失败也无所谓
}
```
注意 `metrics.mqEvent` 放在 ACK 之后——**因为埋点失败不应该导致消息重投**。这是一个"什么动作重要、什么动作可以失败"的精细判断。

**追问：** `default-requeue-rejected: false` 的作用是什么？
**答：** 它决定"监听方法抛异常时是否重新入队"。设为 `false` 意味着**默认不重投**。这是配合手动 ACK 的设计：项目里所有需要重投的场景都显式调 `basicNack(requeue=true)`（只在 republish 失败时用），其他情况一律 `requeue=false`。
**为什么默认不重投？** 因为 `requeue=true` 会把消息立刻投回队首，如果失败原因是下游瞬时故障（DB 连接池满了），立刻重投等于**零间隔自旋**，只会让下游更没机会恢复，还会把日志刷爆（`RabbitMQConfig` 的第 1 个设计点）。

> 💡 **面试官意图：** "ACK 位置"代表对"消息什么时候变得不可恢复"的理解。答"放最后比较安全"是废话，要说出具体后果。

---

### Q35. 幂等是怎么做的？有几层？

**结论：** 四层，从"准入闸门"到"最终兜底"。

| 层 | 位置 | 机制 | 作用 |
| --- | --- | --- | --- |
| 1 | Redis Lua | `SISMEMBER boughtKey userId` | **准入闸门**：挡住同一用户的重复请求 |
| 2 | `t_mq_message` | `uk_message_id` | **消费幂等**：同一消息重复投递时第二条 INSERT 撞唯一键 |
| 3 | `t_order` | `uk_user_activity` | **业务幂等**：一人一单（Redis Set 判定失效时的最后防线） |
| 4 | `t_order` | `uk_order_no` | **订单号幂等** |
| 5 | `t_stock_flow` | `uk_order_change(order_no, change_type)` | **防重复回补**：同一订单的"扣减""回补"各只能落一次 |

`OrderCreationService` 里第 1 步就是幂等闸门，而且注释解释了一个极隐蔽的坑：
> "先查一次是为了给出明确的异常语义；真正的并发正确性由 `t_mq_message.uk_message_id` 保证（两个消费者同时查到'不存在'是必然的）。"

**"先查再插"为什么不够？** 两个消费者同时执行 `alreadyConsumed()` 都会得到 `false`，然后都去 INSERT。**唯一索引是唯一能在并发下兜住的方案。**

**更关键的是事务边界**：`OrderCreationService.createFromSeckill` 整个方法是一个 `@Transactional`，包含五件事：① 消费记录 ② DB 库存扣减 ③ 订单 ④ 订单项 ⑤ 库存流水。注释：
> "尤其第 1 条和第 2/3 条不能分开：如果先写消费记录、再单独开事务写订单，订单事务回滚后消费记录还在，消息重投时会被误判为'已消费'而直接 ACK，**消息被永久丢弃且没有任何报错**——这是可靠消息系统里最隐蔽的一类 Bug。"

**追问：** `DuplicateMessageException` 和 `DuplicateOrderException` 为什么要分成两个异常？
**答：** 因为它们对应**完全不同的处理**：
- `DuplicateMessageException`：这条消息之前成功处理过（`uk_message_id` 命中）→ 直接 ACK，**不补偿**（库存早就扣对了）。
- `DuplicateOrderException`：消费记录没冲突但订单唯一键冲突（`uk_user_activity` / `uk_order_no` 命中）→ **必须补偿 Redis 库存再 ACK**，因为"这一条消息的扣减是多余的"。

代码里 `OrderCreationService` 用 `DuplicateKeyException` 的捕获位置来区分二者，而且注释强调这是**必须告警的一致性缺口**："意味着 Redis 侧的一人一单判定被绕过"。**异常语义的精细划分，是让消费端能做正确决策的前提**——笼统地 `catch (Exception)` 就只能无脑重试。

> 💡 **面试官意图：** 幂等是必问项。答"用 Redis 存 messageId"只能拿基础分；说出"唯一索引 + 事务边界"才是及格线；能区分两种 Duplicate 异常是加分。

---

### Q36. 死信队列怎么配置的？为什么用 `nack(requeue=false)` 而不是自己 publish 到 DLQ？

**结论：** 队列级配置 DLX，`nack(requeue=false)` 触发 broker 内部路由。

```java
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", properties.getMq().getDlxExchange());
args.put("x-dead-letter-routing-key", properties.getMq().getSeckillDlqRoutingKey());
return QueueBuilder.durable(properties.getMq().getSeckillQueue()).withArguments(args).build();
```

**为什么用 broker 的死信而不是应用自己 publish：**
```java
// 重试耗尽：nack(requeue=false) 会触发队列上配置的 DLX，
// 由 broker 把消息路由到死信队列——比应用自己 publish 到 DLQ 更可靠，
// 因为它是 broker 内部完成的，不受应用崩溃影响。
channel.basicNack(deliveryTag, false, false);
```
如果应用自己 publish，中间有一个窗口：**nack 已经发出（消息从主队列删除），但 publish 还没完成，此时应用崩溃 → 消息彻底丢失**。broker 内部路由没有这个窗口。

**死信消费者 `SeckillDlqConsumer` 的核心逻辑：先查订单状态，再决定是否回补。**
```java
boolean orderExists = orderMapper.exists(...eq(Order::getOrderNo, payload.getOrderNo()));
if (orderExists) {
    // 什么都不做，只记录
    metrics.mqEvent("seckill", "dlq_order_exists");
} else {
    compensationService.compensate(payload, "消息进入死信队列且订单未落库", true);
}
```
注释解释了为什么这一步不能省："死信不等于业务失败。消息可能已经成功创建了订单，只是在 `basicAck` 那一刻 channel 断了，broker 于是把它当失败消息走了死信。如果死信消费者无脑回补库存，就会出现'订单在、库存也还回去了'——**直接超卖**。"

**判断依据必须是数据库里的真实结果，而不是"消息进了死信"这个间接信号。**

**死信消费者自己也 ACK**（不是 nack）：
```java
// 注意这里是 ACK 而不是 nack：死信已经是最后一站，
// 再 nack 只会让消息在死信队列里无限循环。
channel.basicAck(deliveryTag, false);
```
而且 `catch (Exception e)` 分支里也是 ACK + 告警，注释："让死信消息无限重投会把死信队列堵死，反而让真正需要人看的问题淹没在重复日志里。"

**追问：** 死信队列里的消息谁来处理？
**答：** 三层：
1. `SeckillDlqConsumer` 自动处理（回补库存 + 记录失败）；
2. `t_mq_message` 表里 `status = 2`（失败）+ `error_msg` 保留现场，`idx_status_retry(status, retry_count, update_time)` 索引支持按失败状态查询；
3. `SeckillReconcileJob` 每 5 分钟对账兜底——**即使死信消费者的回补也失败了，对账任务会在 15 分钟内发现并修复漂移**。

**这是一个很好的"防御纵深"的例子：每一层都不假设上一层一定成功。**

> 💡 **面试官意图：** "死信不等于业务失败"是高级考点。很多人以为消息进死信就是处理失败了。

---

### Q37. 延迟队列怎么实现的？为什么不能用消息级 TTL？

**结论：** 用 **TTL + DLX** 模拟，两个队列：重试队列（TTL=5s）和订单延迟队列（TTL=30s）。这两个队列**都没有消费者**，消息进来后"躺着"直到 TTL 到期，由 DLX 重新投回业务队列。

```java
// 重试队列
args.put("x-message-ttl", (int) properties.getMq().getRetryDelay().toMillis());  // 5000ms
args.put("x-dead-letter-exchange", properties.getMq().getSeckillExchange());
args.put("x-dead-letter-routing-key", properties.getMq().getSeckillRoutingKey());

// 订单延迟队列
args.put("x-message-ttl", (int) properties.getSeckill().getOrderExpire().toMillis());  // 30000ms
args.put("x-dead-letter-exchange", properties.getMq().getOrderCloseExchange());
args.put("x-dead-letter-routing-key", properties.getMq().getOrderCloseRoutingKey());
```

**为什么用队列级 TTL 而不是消息级 TTL？** `RabbitMQConfig` 的第 2 个设计点：
> "RabbitMQ 的**消息级 TTL 会造成队头阻塞**：队列只检查队头消息是否过期，一条 TTL=30 分钟的消息堵在队头，后面 TTL=5 秒的消息也得等它过期才能出来。"

**队头阻塞的具体后果：** 如果重试队列用消息级 TTL，一条本该 5 秒后重试的消息可能因为前面堵着一条 30 分钟的消息而等 30 分钟——**重试延迟完全不可控**。

**什么时候必须换方案：** "如果将来要支持'不同订单不同超时'，必须换成 `rabbitmq_delayed_message_exchange` 插件或按超时档位拆分多个队列。" 本项目所有订单超时统一 30 秒（`order-expire`），所以队列级 TTL 既简单又没有阻塞问题。

**为什么用延迟队列而不是 `basicNack(requeue=true)`？** 第 1 个设计点：
> "`requeue=true` 会把消息立刻重新投回队首，如果失败原因是下游瞬时故障（比如数据库连接池满了），立刻重投等于**零间隔自旋**，只会让下游更没机会恢复，还会把日志刷爆。用 TTL + DLX 构造的延迟队列，能让每次重试之间有确定的间隔（本项目 5 秒），给下游留出恢复时间。"

**重试次数怎么传递？** 用消息头 `retryCount`：
```java
int retryCount = retryCountOf(message);
if (retryCount < maxRetry) {   // maxRetry = 3
    messageSender.republish(message, exchange, retryRoutingKey, retryCount + 1);
    channel.basicAck(deliveryTag, false);
}
```
`retryCountOf` 兼容 `Number` 和 `String` 两种类型（因为 JSON 序列化后可能变成字符串），解析失败返回 0。

**追问：** `republish` 为什么必须保持原 `messageId` 不变？
**答：** `ReliableMessageSender.republish` 的注释："**关键点：保持原 `messageId` 不变**。消费幂等依赖 `messageId`，换了 ID 就等于把同一条消息变成一条新消息，**幂等直接失效**。重试次数用消息头 `retryCount` 传递。"
这是一个非常容易犯的错——如果 republish 时顺手 new 一个新 UUID，那么：
- `t_mq_message.uk_message_id` 不再冲突 → 幂等闸门失效；
- 如果第一次消费其实已经创建了订单（只是 ACK 失败），第二次会用新 messageId 再创建一单 → 撞 `uk_user_activity` → 触发补偿 → **"订单在、库存也还回去了" = 超卖**。

> 💡 **面试官意图：** TTL+DLX 是标准做法，但"队头阻塞"和"republish 必须保 messageId"是真正的考点。

---

### Q38. 消费失败的"重试"是谁在重试？为什么把 Spring 的重试关掉？

**结论：** 由**消费端自己**通过延迟重试队列控制，Spring 容器级重试被显式关闭。

```yaml
spring:
  rabbitmq:
    template:
      retry:
        enabled: false      # 可靠性由业务侧本地台账 + Confirm 兜底，不用模板重试
    listener:
      simple:
        retry:
          enabled: false    # 重试策略由消费端自己控制（延迟重试队列），不用容器重试
```

**为什么关掉：**
1. **延迟不可控。** Spring 容器的 retry 默认是**立即重试**（或固定/指数退避的 `BackOff`），无法和"重试之间要有 5 秒确定间隔"这个需求对齐——用 `FixedBackOff` 也能做，但那是**线程内 sleep**，会占用消费者线程（浪费 `concurrency = 4` 里的一个槽位）。延迟队列把"等待"这件事交给了 broker，**不占用消费者线程**。
2. **重试次数语义会混乱。** 容器重试和业务侧 `retryCount` header 是两套计数，叠加起来"最多重试几次"就说不清了。关掉容器重试后，**`retryCount` 是唯一的计数来源**。
3. **异常语义丢失。** 容器重试会对所有异常一视同仁；而本项目需要区分"可重试（DB 抖动）"和"不可重试（业务失败）"——后者必须直接进死信，重试是浪费。

**`maxRetry = 3`、`retryDelay = 5s` 的实际效果：** 一条消息最多被处理 4 次（首次 + 3 次重试），总耗时约 15 秒后进死信。

**取舍：** 关掉容器重试意味着**自己实现重试逻辑**（`handleRetryable` 方法），代码量增加了。但换来了"重试语义完全可控"。**在可靠性关键的链路上，我更愿意自己掌握这件事。**

**追问：** 如果 republish 成功但 `basicAck` 失败会怎样？
**答：** 代码注释专门点出了这个坑：
> "这里有个容易忽略的坑：**如果 republish 成功但 basicAck 失败**（比如 channel 刚好断开），broker 会把原消息重投，同时重试队列里也有一份——同一条消息被处理两次。这正是幂等必须做到'数据库唯一键级别'而不是'应用层 if 判断'的原因。"

这个场景下：
- 原消息重投 → `uk_message_id` 命中 → `DuplicateMessageException` → ACK，不补偿；
- 重试队列里的消息 5 秒后到达 → 同样 `uk_message_id` 命中 → ACK。

**两条路径都安全，因为幂等建在唯一索引上而不是"我检查一下有没有处理过"。**

> 💡 **面试官意图：** "为什么关掉框架的重试"测的是"你知不知道框架默认行为会和你自己的设计冲突"。

---

### Q39. 消费端的 `prefetch` / `concurrency` / `max-concurrency` 怎么定的？

**结论：** `prefetch = 50`、`concurrency = 4`、`max-concurrency = 16`。

```yaml
listener:
  simple:
    acknowledge-mode: manual
    prefetch: 50
    concurrency: 4
    max-concurrency: 16
```

**逐个解释：**

- **`prefetch = 50`**：每个消费者最多预取 50 条未确认消息。太小（比如 1）会让消费者频繁等待 broker，吞吐上不去；太大（比如 1000）有两个问题：① 消息集中在少数消费者手里，**其他消费者空闲**，负载不均；② 手动 ACK 模式下，预取的消息在内存里堆积，如果消费速度慢于投递速度，**消费者内存会持续增长**。50 是在"吞吐"和"均衡/内存"之间的折中。
- **`concurrency = 4`**：常驻消费者数量。对应 4 个并发消费线程起步。
- **`max-concurrency = 16`**：突发时可以扩到 16。Spring AMQP 的 `SimpleMessageListenerContainer` 会**按需增加**消费者（当队列有积压时），而不是一上来就开满。

**为什么不用固定 16 个？** 因为大部分时间队列是空的（秒杀是**脉冲式**流量），常驻 16 个消费者意味着 16 个线程（每个还占一个数据库连接）常年闲置。**动态扩缩容更适合脉冲场景。**

**关键限制（这个必须主动说）：并发消费 + 一人一单的库存在 DB 层是有竞争的。** 16 个消费者同时打 `t_seckill_activity` 的同一行做 `deductStock`，会在 InnoDB 行锁上排队。所以 `max-concurrency` 不能无限调大——**调大到超过 MySQL 单行的处理能力就是在做无用功**，还会挤占连接池（`maximum-pool-size = 50`）。这也解释了为什么 Hikari 只配了 50：

> "连接池不是越大越好：50 已经足够打满单实例 MySQL 的写能力，继续加大只会把压力转成 MySQL 侧的锁等待与上下文切换。"

**追问：** 那如果积压严重，正确的扩缩容方式是什么？
**答：** **水平扩展消费者实例**，而不是无限加大单实例的 `max-concurrency`。因为：
1. 单实例的消费者线程数受限于 JVM 线程和连接池；
2. 多实例消费天然是"竞争消费"（同一个队列，多个消费者分摊），`prefetch` 保证不会有一个实例独占所有消息；
3. **真正的瓶颈是 `t_seckill_activity` 的单行行锁**——扩到一定程度后，无论多少个消费者都要在同一行上排队。要突破这个瓶颈只能做**库存分片**（把一个活动的库存拆成 N 份，每份一行，减少行锁竞争），但那会带来"分片间库存不均"的新问题。

> 💡 **面试官意图：** 考"MQ 参数不是拍脑袋定的"，也考"你能不能识别出真正的瓶颈在哪一层"。

---

### Q40. 消息的顺序性，本项目怎么处理？

**结论：** **不依赖消息顺序，而是让每个业务动作幂等且可交换。**

**本项目需要保序的场景其实很少：**
| 场景 | 是否需要保序 | 本项目的处理 |
| --- | --- | --- |
| 秒杀扣减 | 否 | 每次扣减都是独立的，Lua 原子执行 |
| 订单创建 | 否 | `uk_message_id` + `uk_order_no` 幂等，重复投递无害 |
| 订单超时关单 | 否 | `cancelIfUnpaid` 用条件更新 `WHERE status = 0`，只有第一个成功 |
| 支付回调 vs 超时关单 | **是（但用状态机解决）** | 两个都做条件更新，DB 状态机保证只有一个成功 |

**支付和关单的"竞争"是最典型的"需要保序"场景**，但本项目的解法是**不用消息顺序，而用数据库状态机**：
```sql
-- 支付
UPDATE t_order SET status = 1, pay_time = ? WHERE order_no = ? AND status = 0
-- 关单
UPDATE t_order SET status = 2, cancel_time = ? WHERE order_no = ? AND status = 0
```
两个语句**谁先执行谁赢**，后执行的返回 0 行。这比"严格保证支付消息先于关单消息到达"简单得多，而且**在分布式环境下可靠得多**——因为：

**"保证顺序"在 MQ 里代价极高：**
- RabbitMQ 的单个队列 FIFO 可以保证顺序，但**引入重试/死信就破坏了顺序**（重试的消息会排到队尾）；
- 多消费者并发消费也破坏顺序；
- 牺牲并发换顺序（一个队列一个消费者）会直接牺牲吞吐。

**所以更划算的工程选择是：让操作幂等 + 用状态机约束合法转移。** 这样"乱序到达"就退化成"重复到达"，而重复是幂等的。

**追问：** 什么场景下你**必须**保序？
**答：** 需要**追加语义**的场景，比如"账户流水"（先扣款后入账的语义不能反）、"版本化的配置更新"（v3 不能覆盖在 v4 之上）。这类场景的解法通常是：**按业务键（比如 accountId）hash 到固定队列**，每个队列单消费者，从而在"同一业务键内保序"的同时保持整体并发。
本项目没有这类需求——库存和订单状态都是"用条件判断决定成败"，不是"追加"。**如果将来做资金账户，就必须换成按 accountId 分区保序的方案。**

> 💡 **面试官意图：** "不用顺序而用幂等 + 状态机"是资深答案。答"用单个队列单消费者保证顺序"会被追问吞吐怎么办。

---

### Q41. `MessageSendTracker` 的 `pending` 用 Caffeine 而不是 `ConcurrentHashMap`，为什么？

**结论：** 因为需要**有界 + 有 TTL** 两个性质，`ConcurrentHashMap` 一个都没有。

```java
this.pending = Caffeine.newBuilder()
        .maximumSize(200_000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build();
```

**这段代码解决的是一个已知的 OOM 风险。** `MessageSendTracker` 的"已知风险"注释：
> "Spring AMQP 的 `CachingConnectionFactory` 会在内存里保存待确认的 `CorrelationData`。如果 broker 长时间不可达、confirm 一直不来，这个 Map 会持续增长直到 **OOM**。缓解手段：一是本类用**有界 + 有 TTL** 的 Caffeine 自己管理上下文；二是定时扫描清掉超时条目并触发补偿；三是生产上必须对这个 Map 的大小做监控告警。"

**三个数字的依据：**
- **`expireAfterWrite = 30 分钟`**："远超任何合理的 confirm 延迟，同时保证内存占用有上界。" 正常情况下 confirm 是毫秒级返回的，`confirmTimeout = 5s` 就判定失败并补偿了。所以 30 分钟这个值纯粹是**为了兜住极端情况下的内存**——一个正常条目在 5 秒后就会被 `compensateTimedOut()` 清掉。
- **`maximumSize = 200_000`**："是第二道保险，防止极端情况下把堆撑爆。" 假设单个 `PendingSend` 约 500 字节（含消息体快照），20 万条约 **100MB**。这是"极端情况下可接受的上界"。
- **`pendingCount()`** 暴露给监控（`pending.estimatedSize()`），配合"生产必须监控这个值"这条纪律。

**取舍：** 用 Caffeine 的代价是**没有 `ConcurrentHashMap` 那样的 O(1) 强一致遍历**（`findTimedOut()` 用的是 `pending.asMap().values()` 遍历，Caffeine 的 `asMap()` 是弱一致的视图）。但这里只需要"能扫到就行"，不需要强一致快照。**用弱一致性换有界内存，很划算。**

**追问：** 如果扫描时正巧有一条消息的 confirm 到达了怎么办？
**答：** `compensateTimedOut()` 用的是 `pending.asMap().remove(send.getMessageId())`——**原子移除**。如果 confirm 已经先把它移除了，这次的 `remove` 返回 `null`，`compensate(null, reason)` 里第一行就是 `if (send == null) { log.debug("补偿时找不到待确认上下文，跳过"); return; }`。
**即使两边真的都触发了补偿，`SeckillCompensationService` 的三层防重（订单存在性检查 + SETNX 守卫 + Lua 总库存封顶）也会保证库存只回补一次。** 这是"多层防御"的价值：每一层都不假设上一层可靠。

> 💡 **面试官意图：** 一个 Caffeine 的选择背后是"内存有上界"的工程意识。能主动说出 OOM 风险和数字依据是加分项。

---

### Q42. `MessageSendTracker` 只处理发送侧，那消费侧的"台账"在哪？

**结论：** 在 `t_mq_message` 表里。**一张表同时承担三个职责**（`01-schema.sql` 注释）：

```sql
-- 消息表：一张表同时承担三个职责
--   a) 生产者投递记录（direction=1）：Confirm 超时未确认的消息由此重投
--   b) 消费者幂等记录（direction=2）：message_id 唯一键 = 消费去重
--   c) 失败台账（status=2）：死信 / 人工排查 / 对账的依据
```

表结构关键点：
```sql
id          BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
message_id  VARCHAR(64)     NOT NULL COMMENT '业务消息唯一ID',
biz_type    VARCHAR(32)     NOT NULL COMMENT 'SECKILL_ORDER / ORDER_TIMEOUT',
biz_key     VARCHAR(128)    NOT NULL COMMENT '业务键，如 orderNo 或 actId:userId',
direction   TINYINT         NOT NULL DEFAULT 2 COMMENT '1生产 2消费',
status      TINYINT         NOT NULL DEFAULT 0 COMMENT '0待处理 1成功 2失败',
retry_count INT             NOT NULL DEFAULT 0,
payload     TEXT,                                   -- 消息体快照
error_msg   VARCHAR(512),
UNIQUE KEY uk_message_id (message_id),
KEY idx_status_retry (status, retry_count, update_time),
KEY idx_biz (biz_type, biz_key)
```

**索引设计的意图：**
- `uk_message_id`：消费幂等的物理保证；
- `idx_status_retry(status, retry_count, update_time)`：支撑"找出所有失败且重试未耗尽的消息"这类运维查询；
- `idx_biz(biz_type, biz_key)`：支撑"按业务键查这条消息的全部记录"（比如某订单相关的所有消息）。

**`payload` 存消息体快照的价值**：出问题时能直接看到**当时发的是什么**，而不是靠日志拼。这一点在排查"消息内容不对"类问题时是决定性的。

**注意 `direction` 的使用：** 描述里说 `direction=1` 用于生产者投递记录，但**本项目生产侧实际用的是内存台账**（`MessageSendTracker`），不是这张表。为什么？
→ 这正是 Q44 的核心权衡：**秒杀热路径上不能有额外的 DB 写**。`t_mq_message` 的 `direction=1` 是为**订单超时关单那条链路**准备的（那条链路量不大但绝不能丢，用的是"落库 + 延迟消息 + 兜底扫描三重保障"）。

**追问：** `uk_message_id` 是全表唯一还是可以加 `direction` 组成联合唯一？
**答：** 本项目是**全表唯一**（`UNIQUE KEY uk_message_id (message_id)`）。理由是：同一个 `messageId` 不应该同时在"生产记录"和"消费记录"里出现两次——它就是一个业务消息的唯一标识。如果做成 `(message_id, direction)` 联合唯一，就等于允许同一条消息有两条记录，这在排查时必须额外判断"哪条是权威的"。
**代价是**：如果将来真的需要同时记录生产和消费两侧（比如要做全链路消息追踪），就必须改设计——可能的方案是加一个 `t_mq_message_flow` 明细表，而不是放宽唯一键。

> 💡 **面试官意图：** 考"一张表三种用途"的设计，以及"为什么唯一键不加 direction"这类的严谨性追问。

---

### Q43. `SeckillOrderMessage` 的每个字段为什么都要在消息里？不能只传 orderNo 让消费端自己查吗？

**结论：** 因为**消息是"事实的快照"，消费端不应该依赖"可变的外部状态"**。

```java
public SeckillOrderMessage(String messageId, String orderNo, Long userId, Long activityId,
                           Long productId, String productTitle, BigDecimal seckillPrice,
                           Integer quantity, Integer totalStock, LocalDateTime createTime)
```
`SeckillService` 构造时的来源全部是 `snapshot`（活动快照，来自 L1/Redis）：
```java
new SeckillOrderMessage(messageId, orderNo, userId, activityId,
        snapshot.getProductId(), snapshot.getProductTitle(), snapshot.getSeckillPrice(),
        1, snapshot.getTotalStock(), LocalDateTime.now(clock));
```

**如果只传 `orderNo` 让消费端自己查：**
1. **多一次 DB 查询**（消费端本来就要写订单，再加一次查询是纯浪费）；
2. **更严重：价格和标题可能已经被改过了。** 用户 10:00 抢的时候秒杀价是 99 元，如果 10:02 运营把价格改成 199，消费端查到的是 199——**用户看到的是 99，订单金额变成 199，这是客诉和资损**。
3. **活动可能已经结束/被删除**，查询拿不到数据。

**本项目快照字段里就有一个 `snapshotTime`**（`SeckillActivitySnapshot` 的最后一个字段 `LocalDateTime.now(clock)`），它标记了"这个快照是什么时候取的"，为的就是让"快照可能过时"这件事变得**显式可查**。

**取舍：** 快照的代价是"消息体变大"和"快照可能过时"。
- 消息体变大：本项目消息体大概几百字节，相对 MQ 的承载能力完全可以忽略。
- 快照过时：价格过时的**方向是"保护用户"**（用户看到的价格就是下单价格），这是正确的方向。总量过时会"少卖一点"（Q12 讨论过）。

**"让它变成显式可查"是这个设计最值得说的地方：** `snapshotTime` 字段不是为了业务逻辑，而是为了**运维和排查**。发现某笔订单价格不对时，直接看 `create_time - snapshotTime` 的差值，如果差了几分钟，就说明是快照陈旧导致的。**预留可观测性字段，比事后加日志靠谱得多。**

**追问：** 那消费端还要不要校验活动状态？
**答：** 要，但不是"重新读活动"——而是**校验 DB 的库存**。`OrderCreationService` 用条件更新 `deductStock` 来保证 DB 侧的库存不会为负，如果返回 0 就抛 `NonRetryableMessageException("数据库库存不足，Redis 与 DB 库存出现漂移")`。
**注意这是一个"不可重试"异常**：重试一万次库存也不会变出来，所以直接进死信 + 打 ERROR 告警（"两者出现漂移，是必须告警的信号"）。**"能重试"和"重试有用"是两件不同的事。**

> 💡 **面试官意图：** 消息体设计 + "价格快照为什么重要"是资深考点。`snapshotTime` 这类可观测性字段体现了成熟度。

---

### Q44. 为什么用内存台账（`MessageSendTracker`）而不是本地消息表（Outbox）？

**结论：** 因为**秒杀热路径上零额外 DB 写入是决定性的**。

`MessageSendTracker` 的注释把两个方案列得很清楚：

**方案 A（本项目）：内存台账 + 超时补偿 + 对账兜底**
- 优点：秒杀热路径上**零额外数据库写入**。"这是决定性的——秒杀接口能扛住高并发，正是因为把数据库操作全部异步化了，为了'可靠'再塞一次 INSERT 进去，**等于自毁前提**。"
- 缺点：进程崩溃会丢失未确认的上下文，Redis 库存已扣、订单没产生。
- 兜底：`SeckillReconcileJob` 通过比对"Redis 库存 / DB 剩余库存 / 在途计数"把这类漂移修回来。**用对账代替同步写库来保证最终一致。**

**方案 B：本地消息表（Outbox）**
- 业务与消息记录同事务落库，强可靠，但**热路径多一次数据库写入**。
- 它更适合"量不大但绝不能丢"的场景——**本项目订单超时关单那条链路就用了类似的思路**（落库 + 延迟消息 + 兜底扫描三重保障）。

**这个决策的量化依据：** 秒杀活动的库存典型是几百到几千件。如果每次抢购都在热路径写一条消息表，那就是**和扣库存同量级的 DB 写入**——而 Redis 侧本来可以拦住 99% 的失败请求（`localSoldOutCache` + Lua 快速否定）。也就是说：

| 方案 | 热路径 DB 写 | 用户 RT 影响 | 崩溃丢消息的风险 |
| --- | --- | --- | --- |
| 内存台账 | **0** | 无 | 有（靠对账兜） |
| 本地消息表 | **1 次 INSERT** | +1 次 DB 往返（可能几十 ms） | 无 |

**关键判断：崩溃丢消息这个风险，能不能被对账兜住？能。** 因为丢消息的后果是"Redis 库存少了、订单没生成、在途计数多了"——三个数字都是可观测、可比对的，对账能精确定位并修复（Q50）。而"多一次 DB 写"的后果是**每次抢购都慢一点、DB 压力大一截**，没有任何机制能弥补。

**"用对账代替同步写库"是这个项目最重要的架构决策之一。** 它的前提是：**业务状态必须是可对账的（有权威数据源 + 有可比对的量）**。如果业务没有这样的不变式，这个方案就不成立。

**追问：** 那订单超时关单为什么可以用本地消息表？
**答：** 因为**量级完全不同**。秒杀峰值几万 QPS，关单是稳态流量（每个订单只产生一次，30 秒后触发）。而且关单链路**不允许丢消息导致的状态悬空**——虽然也有 `OrderTimeoutFallbackJob` 兜底，但那条链路本来就是"落库后才能发消息"（订单已经在 DB 里了），顺手在同一个事务里插一条消息记录几乎零成本。

**"同一个技术选型在不同链路上可以有不同答案"——判断依据是量级和风险的不对称性，而不是教条。**

> 💡 **面试官意图：** 这是全项目最能体现"架构判断力"的一题。答"本地消息表更可靠所以应该用它"是在展示教条而不是思考。

---

### Q45. `PendingMessageRetryJob` 的扫描间隔为什么是 2 秒？

**结论：** 因为 `confirmTimeout = 5s`，扫描间隔必须**显著小于**它。

`PendingMessageRetryJob` 的注释：
> "为什么扫描间隔是 2 秒：`confirmTimeout` 默认 5 秒，扫描间隔必须显著小于它，否则补偿会滞后得让人以为系统卡住了。2 秒的扫描成本极低（只是遍历一个内存 Map），完全可以承受。"

**几个数字的关系：**
```
用户点击 → sendSeckillOrder → MessageSendTracker.register（写入 pending）
                                         │
                              confirm 正常回来（毫秒级）→ invalidate，结束
                                         │
                              confirm 没回来
                                         ▼
                    t = 5s   confirmTimeout 到 → findTimedOut() 认为它超时
                    t = 6s   下一次扫描触发（2s 一次，最坏等 2s）→ 补偿
```
**最坏的补偿延迟 = confirmTimeout(5s) + 扫描间隔(2s) = 7 秒。**

**为什么不再快一点（比如 500ms）？** 遍历一个可能有几万个条目的 Caffeine Map 是纯 CPU 操作，本身不贵，但**扫描太频繁是纯粹的浪费**——正常业务下 pending 里最多只会有"最近几秒内发送的几十条"，遍历它们的时间是微秒级，但**每秒 2 次 × N 个实例 × 每天 86400 秒**的定时任务调度如果有锁竞争或线程池占用就成了负担。2 秒是一个"让用户感知不到"和"不给系统加负担"的平衡点。

**更重要的：这个任务必须自己兜住异常。**
```java
} catch (Exception e) {
    // 定时任务抛异常会导致后续调度被抑制（Spring 默认行为），
    // 所以必须自己兜住，保证任务永远活着。
    log.error("Confirm 超时补偿任务异常", e);
    metrics.mqEvent("seckill", "pending_job_error");
}
```
**"定时任务抛异常会导致后续调度被抑制"是一个真实的 Spring `@Scheduled` 行为**（对 `fixedDelay` 任务，异常会记 ERROR 但任务会继续；但 `fixedRate` 在某些配置下会停止；而且**内部有 stateful 逻辑时更容易出问题**）。所以**每一个 `@Scheduled` 方法的整个方法体都在 try-catch 里**是纪律——项目里 `OrderTimeoutFallbackJob` 也是这么写的。

**追问：** 如果补偿失败（比如 Redis 不可用）会怎样？
**答：** `compensateTimedOut()` 里是 `pending.asMap().remove(send.getMessageId())`，**先移除再补偿**（`compensate` 里的 `try/catch` 会吞掉异常并打 ERROR："补偿本身失败是最严重的情况：库存被永久占用，只能靠对账修复")。
这有一个隐患：**移除后如果补偿失败，这条记录就再也扫不到了。** 但这是**有意接受**的：
1. 如果保留它，下次扫描会再补偿一次——虽然 `SeckillCompensationService` 的 SETNX 守卫会挡住，但会产生大量重复日志；
2. **真正的兜底是对账任务**（`SeckillReconcileJob` 每 5 分钟），它会发现"Redis 库存比期望少了"，然后修复。
**这又是"不要假设上一层可靠，但也不要让每一层都做全部的事"的体现。**

> 💡 **面试官意图：** 定时任务的参数取值 + "定时任务必须自己吞异常"是实战型考点。

---

### Q46. 补偿逻辑为什么必须收敛到一个类里？三层防重分别防什么？

**结论：** 因为补偿是"整个系统里最容易出错的一段逻辑：库存已经扣了，但订单没成。**补偿一旦重复执行或漏执行，后果分别是超卖和少卖。** 散落在多处实现必然会出现不一致。"

`SeckillCompensationService` 的三层防重：

**第一层：先确认订单确实不存在**
```java
boolean orderExists = orderMapper.exists(...eq(Order::getOrderNo, msg.getOrderNo()));
if (orderExists) {
    log.warn("订单已存在，跳过库存补偿（避免超卖）...");
    return false;
}
```
防的是这个窗口：
```
broker 已把消息路由给消费者 → 消费者成功创建订单 → 事务提交
但 Confirm 帧丢失 / 超时才到达 → 超时扫描触发补偿
```
此时订单明明已经存在，若无条件回补库存，结果就是**订单在、库存也还回去了 = 超卖**。

**第二层：SETNX 一次性守卫**
```java
if (!stockService.tryAcquireCompensateGuard(msg.getMessageId())) {
    log.info("该消息已完成过补偿，跳过 messageId={}", msg.getMessageId());
    return false;
}
```
防的是"同一个 messageId 从五个入口触发补偿"：
发送异常、Confirm nack、Return 回调、超时扫描、死信消费。

实现：`redis.opsForValue().setIfAbsent(RedisKeys.seckillCompensateGuard(messageId), "1", Duration.ofHours(24))`。
**TTL 取 24 小时**：`SeckillStockService` 注释说"足够覆盖任何合理的补偿延迟"。**为什么不用永久 key？** 会无限膨胀（每个 messageId 一个 key，秒杀场景下每天几百万个）。24 小时之后这条消息早就不可能再被触发了（MQ 消息的 TTL 远小于这个值）。

**第三层：Lua 侧的总库存封顶**
```lua
local current = tonumber(stock)
if current >= totalStock then return current end
local newStock = redis.call('INCR', stockKey)
```
注释："即使前两层都被绕过，回补脚本里 `newStock > totalStock 则取 totalStock` 也保证库存不会超过总库存——把错误限制在'少卖'而不是'超卖'。**宁可少卖，绝不超卖**，这是交易系统的第一原则。"

**注意一个刻意的设计：`rollback` 脚本里【没有】做幂等判断。**
`seckill_rollback.lua` 的注释：
> "本脚本刻意不做幂等判断，因为它的调用方都已经在更可靠的地方做了去重——a/b/c 场景由 Java 侧的『补偿守卫 key』(SETNX) 保证只执行一次；d 场景由数据库 `t_stock_flow` 的 `uk_order_change(order_no, change_type)` 唯一键保证只执行一次。**在 Lua 里再做一层半吊子幂等，只会让语义变模糊、增加出错的可能。**"

**这是一个非常成熟的设计判断：** 幂等应该在**最外层、最可靠的地方**做一次（唯一索引 or SETNX），而不是在每一层都做一遍。多层半吊子幂等会导致"到底哪一层拦住了"变得不可知，出问题时无法定位。

**追问：** `releaseQualification` 参数为什么补偿场景是 `true` 而超时关单是 `false`？
**答：** 这是同一个脚本能覆盖四类场景的关键：
- **`true`（消息投递失败 / 消费失败进死信 / 唯一键冲突）**：订单确实没落库，用户不该被判定为"已经买过"，所以要把用户从"已购集合"里 `SREM` 掉，让他还能重新抢。
- **`false`（超时关单）**：`OrderTimeoutConsumer` 里显式传 `false` 并注释："一人一单是硬规则，资格不回收，否则用户会在 Redis 通过、在 DB 撞唯一键。"
  库存放回去给别人抢，但这个用户的购买资格**已经消耗掉了**——DB 里的 `uk_user_activity` 也不允许同一用户再建一单。如果 Redis 侧回收了资格，用户会在 Redis 通过 → 投递 MQ → 消费端撞唯一键 → 触发补偿 → 又是一轮循环，产生大量无意义的消息和补偿。

> 💡 **面试官意图：** "为什么不做多层幂等"这个问题很少人能答好。这是"知道在哪里做一次比在哪里做多次更重要"的体现。

---

### Q47. 对账的"不变式"是什么？为什么修复方向必须是"以 DB 为准"？

**结论：** 不变式是"Redis 库存 = DB 剩余库存 - 在途数"，修复方向永远是把 **Redis 对齐到 DB**。

`SeckillReconcileService` 的注释：
```
稳态（无在途请求）下：   Redis 库存  ==  DB available_stock
有在途请求时：           Redis 库存  ==  DB available_stock - 在途数
```
推导：每一次成功的抢购，Redis 先减 1（进入在途），订单落库后 DB 也减 1；每一次超时关单或补偿，两边同时加回 1。**所以差值只可能来自"在途"。**

代码：
```java
int expected = dbAvailable - inflight;
int drift = redisStock - expected;
boolean consistent = drift == 0;
```

**为什么修复方向必须是"Redis 对齐到 DB"：**
> "数据库有唯一索引、有事务、有 CHECK 约束，是**唯一能自我保证正确性的一层**。Redis 里的库存只是一份**可以被重建的投影**。所以漂移时永远是把 Redis 对齐到 DB，而不是反过来——反过来会把已经发生的错误'固化'进权威数据源。"

**这个原则在项目里出现了至少三次，方向完全一致：**
1. 对账修复：`stockService.resetStock(activityId, Math.max(targetStock, 0), ttl)` —— Redis ← DB
2. 超时关单：先提交 DB 事务，再回补 Redis（Q64）
3. 补偿前先查订单是否存在，存在就不回补 —— **DB 的订单是权威，Redis 的库存是投影**

**取舍：** "以 DB 为准"意味着**如果 DB 本身被写错了，对账会把这个错误固化到 Redis**。这是真实的局限。所以：
- **修复默认是关闭的**（Q49）：`auto-repair` 在生产建议先只告警；
- DB 本身的正确性靠**唯一索引 + CHECK 约束 + 事务**保证，而不是靠对账。

**"投影"这个词很重要。** 把 Redis 定位成"可重建的投影"，就自动回答了"Redis 挂了怎么办"（重建）、"Redis 数据错了怎么办"（以 DB 重放）。**如果一个系统里的每份数据都能明确它是"权威"还是"投影"，一致性问题就解决了一大半。**

**追问：** 为什么 `repair` 里重置库存时要重新计算 TTL？
**答：**
```java
Duration ttl = Duration.between(LocalDateTime.now(clock), activity.getEndTime());
if (ttl.isNegative() || ttl.toMinutes() < 10) {
    ttl = Duration.ofMinutes(30);
} else {
    ttl = ttl.plusHours(1);
}
```
两个考虑：① 活动已结束或即将结束时，不能让修复把 key 设成几秒的 TTL（那样修复等于没修），所以兜底 30 分钟；② 活动还早时用"剩余时间 + 1 小时"，和预热逻辑（`ttlFor`）保持一致的语义。
**更关键的一步是 `preheatService.preheat(activityId)`：**
```java
// 库存被改写后，本地的"售罄"标记必须清掉，否则会继续错误地拒绝请求
preheatService.preheat(activityId);
```
`preheat` 内部会调 `activityService.evictLocal(activityId)` 并在 `preheatStock` 里清 `localSoldOutCache`。**这是"库存被修改"和"本地标记被清除"必须成对出现**的纪律——如果只改了 Redis 库存而不清本地标记，用户会继续被拒绝（Q5 讨论过）。

> 💡 **面试官意图：** "权威 vs 投影"的思维模型是资深工程师的标志。能说清"什么时候以谁为准"就能自己推导出所有一致性方案。

---

### Q48. 在途计数有什么用？它为什么会"掩盖"故障？

**结论：** 在途计数有两个用途：**对账修正项** 和 **泄漏探测器**。而它的危险性质是——**它会掩盖故障**。

`SeckillReconcileService` 的注释讲得非常透彻：
> "在途计数有一个麻烦的性质：它会**掩盖**故障。如果进程在'Redis 已扣减、消息还没确认'时崩溃，Redis 库存永久少了 1，而在途计数也永久多了 1——**两者相减正好抵消，`drift` 依然是 0，单纯比对库存根本发现不了。**"

**破局点：在途是瞬态量。**
> "正常业务下，从扣减到落库只有几十毫秒，每 5 分钟采样一次，几乎不可能采到'在途 > 0'。所以**连续多次采样都看到在途 > 0，就等价于发现了泄漏**。这时把库存对齐到 DB、在途清零，完成修复。"

代码：
```java
private static final int STALE_INFLIGHT_THRESHOLD = 3;
...
if (inflight > 0) {
    int streak = staleInflightCounter.merge(activityId, 1, Integer::sum);
    if (streak >= STALE_INFLIGHT_THRESHOLD) {
        log.error("【库存对账】在途计数疑似泄漏 activityId={} inflight={} 连续采样次数={}", ...);
        if (autoRepair && ...) {
            repair(activityId, dbAvailable, activity, "在途计数泄漏修复");
        }
        staleInflightCounter.remove(activityId);
    }
}
```
`consistent == true` 时会 `staleInflightCounter.remove(activityId)` —— **计数必须能被清零，否则一次正常的在途会让计数器累积**。

**为什么是 3 次？** 对账 cron 是 `0 */5 * * * ?`（每 5 分钟一次），3 次 = **15 分钟**。为什么不能是 1 次？
- 概率上：正常在途只存在几十毫秒，5 分钟采一次正好采中的概率极低，但**不是零**——如果采样时恰好有一个超大活动正在洪峰，采到"在途 = 500"是完全可能的。
- 一次止损的风险：误判会执行 `resetInflight` + `resetStock(dbAvailable)`，把**正在进行的 500 个在途请求全部抹掉**。虽然"库存对齐到 DB"方向是对的，但那 500 个请求落库后会各自再减一次 DB 库存——**Redis 库存被虚高了**（因为那 500 个在途的扣减在 Redis 里被清零了，但它们在 DB 里还会扣）。
- 3 次（15 分钟）几乎排除了"瞬时洪峰"的可能。**这是一次典型的"用延迟换准确度"的权衡**：泄漏的代价是库存被占用，迟 15 分钟修复完全可以接受；误判的代价是数据错乱，必须极力避免。

**`staleInflightCounter` 是进程内 `ConcurrentHashMap`**（"重启归零"）。为什么不做成分布式的？因为**对账任务本身应该只有一个实例执行**（或者各实例各算各的，最终结果一致）。如果多实例各自维护计数，可能一个实例看到 3 次、另一个看到 2 次，谁都不知道该不该修。**本项目没做分布式对账锁**——这是一个真实的设计缺口，我会在面试里主动说："生产环境应该用分布式锁（比如 `lock:seckill:reconcile`）保证同一时刻只有一个实例执行自动修复，否则两个实例可能同时 `resetStock` 造成竞争。"

**追问：** 在途计数怎么被归还？
**答：** 三条路径：
1. **订单成功落库后**：`OrderCreatedListener`（监听 `OrderCreatedEvent`，在 `AFTER_COMMIT` 阶段）调 `stockService.inflightDec(activityId)`，执行 `seckill_inflight_dec.lua`（`DECR` 但不小于 0）；
2. **补偿时**：`rollback(..., decInflight = true)`；
3. **超时关单时**：`rollback(..., decInflight = false)`，因为"订单早已落库，在途计数在下单成功时就归还过了"（`OrderTimeoutConsumer` 的注释）。

**注意 2 和 3 的差异**：补偿场景要归还（因为那次扣减的在途从来没被还过），超时关单场景**不归还**（因为下单成功时已经还了，再还一次就变成负数了）。`inflightDec` 的 Lua 里有 `if inflight > 0 then DECR` 的保护，但**语义上搞清楚"什么时候该还"比靠保护更重要**。

> 💡 **面试官意图：** "在途计数会掩盖故障"是全项目最精彩的洞察之一。能讲清楚"为什么连续 3 次"就是完整的答案。

---

### Q49. 对账为什么默认只告警、不自动修？`auto-repair: true` 为什么在配置里是 true？

**结论：** 因为**自动修复的前提是"对账逻辑本身是对的"**。

`SeckillReconcileService` 的注释：
> "如果对账逻辑有 bug，自动修复会把一个数据错误**放大成持续的数据破坏**。所以生产环境建议先跑一段时间只告警，确认 `drift` 恒为 0 之后再开启 auto-repair。"

**这是"自动化危险操作"的通用原则：任何会自动改写权威数据的任务，都必须先经过一段只读观察期。**

**关于配置：** `application.yml` 里确实写的是 `auto-repair: true`，并且有一行注释说明：
```yaml
# 发现漂移时是否自动修复（面试演示建议开，生产建议先只告警）
auto-repair: true
```
**这里有一个刻意的差异，我会在面试里主动说清楚：** 演示环境开 `true` 是为了让面试官能看到"发现漂移 → 自动修复"的完整闭环；**生产环境我会上 `false`**，先用 `RECONCILE_ENABLED=true` + `auto-repair=false` 跑一周，确认 `mall.reconcile.drift` 指标恒为 0，再逐步灰度开启。

**代码里还有一层双保险：**
```java
public List<ReconcileReport> reconcileAll() {
    reports.add(reconcile(activity, properties.getReconcile().isAutoRepair()));
}
...
if (autoRepair && properties.getReconcile().isAutoRepair()) {
    repair(...);
}
```
方法参数和配置项都必须是 true 才修。`reconcileOne(activityId, autoRepair)` 是给**观测接口**用的——运维可以传 `false` 手动"预演"一次对账，看看会修成什么，而不真的修。**"预演模式"对高危操作是必需的能力。**

**监控指标：**
```java
public void reconcileDrift(long activityId, int drift) {
    counter("mall.reconcile.drift", "activity", String.valueOf(activityId)).increment();
    registry.gauge("mall.reconcile.last.drift", List.of(Tag.of("activity", ...)), drift);
}
```
一个 Counter（漂移次数）+ 一个 Gauge（最后一次漂移量）。**告警规则应该是 `mall.reconcile.drift > 0` 就告警**（因为 `drift-tolerance: 0`）——漂移本身就是一个"不该发生"的事件。

**追问：** `drift-tolerance: 0` 是不是太严格了？在途请求会不会造成假漂移？
**答：** 不会，因为**在途已经被减掉了**（`expected = dbAvailable - inflight`）。这就是在途计数的第一个用途。容忍度设 0 是合理的，因为对账公式已经吸收了瞬态差异。
如果设成非 0（比如 5），意思是"允许 5 件的漂移不告警"——这会掩盖真实的稳步泄漏（每次泄漏 1 件，5 次以内都不告警）。**在"应该恒为 0"的量上设置容忍度，等于放弃了对小幅度持续泄漏的检测能力。** 所以 0 是对的。

> 💡 **面试官意图：** "为什么默认不自动修"考的是对"自动化危险操作"的敬畏。**越是资深的面试官越在意这个。**

---

### Q50. 进程崩溃导致"Redis 扣了库存、订单没生成、内存台账也丢了"，系统怎么自愈？

**结论：** 靠**对账任务的三次采样检测 + 以 DB 为准的修复**。这是"用对账代替同步写库"这个架构决策的兜底。

**故障的完整时间线：**
```
t=0     用户点击 → Lua 扣减成功（stock -1）→ inflight +1
t=1ms   resultService.markQueueing(orderNo)  ← 结果缓存写了，但订单还没落库
t=2ms   MessageSendTracker.register(msg)     ← 内存台账写入
t=3ms   convertAndSend 发出
t=4ms   ★ 进程崩溃（OOM / kill -9 / 宿主机宕机）
```
**结果：**
- Redis：`stock` 少了 1，`bought` 集合里有这个用户，`inflight` 多了 1；
- MQ：消息可能到了 broker（没消费），也可能根本没发出去；
- DB：没有订单；
- **内存台账：丢了**——即使 MQ 里有消息，`MessageSendTracker` 也没法关联上下文了。

**自愈路径：**

| 时间 | 机制 | 结果 |
| --- | --- | --- |
| t+5s | 另一实例的 `PendingMessageRetryJob` | **无效**——pending 在崩溃实例的内存里 |
| t+5min | `SeckillReconcileJob` 第 1 次采样 | `inflight=1 > 0`，`streak=1`，记 note，**不修** |
| t+10min | 第 2 次采样 | `streak=2`，**不修** |
| t+15min | 第 3 次采样 | `streak=3 >= STALE_INFLIGHT_THRESHOLD` → **判定泄漏** → `repair(activityId, dbAvailable, ...)` |
| 修复动作 | `resetInflight` + `resetStock(dbAvailable)` + `preheat` | Redis 库存对齐到 DB，在途清零，本地售罄标记清除 |
| 用户侧 | `queryResult(orderNo)` | 结果缓存里的"排队中"30 分钟后过期 → 回落到 DB → 查不到订单 → 前端提示重新抢购 |

**关键点：**
1. **崩溃本身是不可恢复的**，用户的那次抢购确实失败了（他的资格也被保留在 `bought` 集合里，所以**他不能再抢了**——这是"少卖"的一种：库存还回去了，但那个用户的资格没回收）。**这是一个已知的、可接受的损失**：极端情况下单个用户的资格被消耗但没买到，只能靠人工补偿。
2. **系统的库存总量会自愈**：15 分钟后 `drift` 被修成 0，库存能被其他用户抢到。
3. **如果崩溃的是"最后一条消息"，MQ 里的那条消息 30 分钟/永久都不会被消费（没有消费者针对它）**——不对，秒杀队列是有消费者的，消息会正常被消费并创建订单。这种情况下：
   - 订单成功创建 → `uk_message_id` 通过（因为消息还是那条）→ DB 库存减 1 → `OrderCreatedListener` 调 `inflightDec` → **在途被归还**；
   - 此时 `inflight = 0`，`drift = 0`，对账不会误判。
   **也就是说：消息还在 broker 的情况下，系统会自然恢复，不需要对账介入。**

**所以真正需要"在途泄漏检测"的场景是：消息也没发出去（`convertAndSend` 之前就崩了）。** 这个窗口极小（`register` 到 `convertAndSend` 之间），但确实存在。

**追问：** 有没有办法缩小这个窗口？
**答：** 有，代价递增：
1. **调整顺序**：先 `convertAndSend` 再 `register`？不行——`CorrelationData` 必须在发送前创建并注册，否则 confirm 回来找不到上下文。
2. **`register` 里同步写一条轻量记录到 Redis**（而不是 DB）：`SET seckill:pending:{messageId} 1 EX 300`。代价是**热路径多一次 Redis 写**——而热路径已经有一次 Lua 了，再加一次 RTT 会让 RT 增加约 50%。收益是崩溃后其他实例能扫到这个悬空记录。
3. **接受它**（本项目）：窗口是微秒级，发生的概率极低，而且后果（单用户资格被消耗）是**可人工补偿的**，不是资损。

**我会这样回答面试官："这是一个我明确识别并评估过的风险，我选择了接受它，因为它的概率极低、后果可控、而修复它的代价是热路径 RT 增加 50%。如果业务要求更高，我会用方案 2。"** —— 这种回答比"我不会让它崩溃"强得多。

> 💡 **面试官意图：** 这题考的是"你能不能诚实、精确地描述自己系统的失败模式"，而不是"你的系统有没有缺陷"。**有缺陷但你知道，远好过你以为没有缺陷。**

---

### Q51. `OrderCreatedListener` 为什么用事件而不是直接在 `createFromSeckill` 里做副作用？

**结论：** 因为副作用（归还 Redis 在途计数、发送延迟消息）**必须在数据库事务提交之后才能执行**。

`OrderCreationService` 的最后一步：
```java
// ---------- 6. 提交后的副作用 ----------
// 由 OrderCreatedListener 在 AFTER_COMMIT 阶段处理
eventPublisher.publishEvent(new OrderCreatedEvent(this, order, msg));
```

`OrderCreatedListener` 用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 监听。

**如果直接在 `createFromSeckill` 里做会怎样：** `createFromSeckill` 是 `@Transactional`，如果在方法内部直接调 `stockService.inflightDec()`（Redis 操作）和 `messageSender.sendOrderTimeout()`（MQ 操作），而**事务最后回滚了**（比如后面 `publishEvent` 之前的某一步抛异常）：
- Redis 的在途计数已经被归零，但订单没落库 → **在途计数少了一个，对账公式就会误判 drift**；
- 延迟关单消息已经发出 → 30 秒后 `OrderTimeoutConsumer` 收到消息，去 `cancelIfUnpaid` 发现订单不存在 → `CancelResult.notCanceled` → 什么都不做（安全），但白跑一趟。

`OrderService.cancelIfUnpaid` 的注释把同一原则讲得更清楚：
> "Redis 库存的归还由调用方在**事务提交后**执行。为什么不放在同一个方法里？因为 Redis 不参与数据库事务，放在事务内一旦后续回滚，Redis 的修改无法撤销。正确顺序是：先让数据库事务成功提交，再做 Redis 补偿；万一 Redis 那一步失败，对账任务会把库存修回来。**数据库是权威，Redis 是可重建的投影**——顺序不能反。"

**取舍：**
- **事件机制的代价**：调试链路变长（一个操作的效果分散在多个监听器里），而且 `AFTER_COMMIT` 阶段的异常**不会回滚事务**（事务已经提交了），所以监听器内部必须自己 try-catch + 告警。如果监听器抛异常，Spring 会记录 ERROR 但事务照样是成功的。
- **备选方案**：手动在 Service 方法返回后（调用方）执行副作用——但这样"提交后副作用"的纪律就靠调用方自觉了，容易漏。事件机制把这个纪律**编码进了框架层面**。

**追问：** 如果 `AFTER_COMMIT` 的监听器里 Redis 挂了，在途计数归零失败怎么办？
**答：** `SeckillStockService.inflightDec` 内部自己兜住了：
```java
try {
    redis.execute(seckillInflightDecScript, List.of(RedisKeys.seckillInflight(activityId)));
} catch (Exception e) {
    // 在途计数只影响对账精度，失败不能影响主链路
    log.warn("归还在途计数失败 activityId={}（不影响订单）", activityId, e);
}
```
**注释的定位非常准确："在途计数只影响对账精度，失败不能影响主链路"。** 这是对"这个数据有多重要"的清醒判断：
- 它不影响交易正确性（订单和 DB 库存才是权威）；
- 它只影响对账的准确性——最多导致一次"假漂移"告警（`inflight` 没减少，`expected` 就偏小，`drift` 会显示为正数）；
- 误报的后果是"多一次告警"，可以接受。

**反过来如果这里抛异常呢？** 事务已经提交，异常只会打到日志；但如果是同步调用（不是事件），异常可能被上层 catch 后误判为"订单创建失败"——那才是真正的灾难。**所以"该吞的异常必须吞掉，并且吞的位置要有明确理由"，而不是简单地"统一 try-catch"。**

> 💡 **面试官意图：** `AFTER_COMMIT` 是交易系统的必修课。能讲清"Redis 操作不能放事务内"以及"为什么用事件而不是手动调用"是加分项。

---

### Q52. 补偿守卫用 Redis 的 `SETNX`，但 Redis 会丢数据，守卫丢了怎么办？

**结论：** 守卫丢了会导致**重复补偿**，但**不会超卖**——因为第三层（Lua 总库存封顶）+ 第一层（订单存在性检查）挡住了。

**分析守卫丢失的路径：**

| 路径 | 守卫丢失 | 后果 | 兜底 |
| --- | --- | --- | --- |
| Redis 主从切换丢写 | 守卫 key 没了 | 同一 messageId 可能补偿两次 | 见下 |
| Redis 重启/`FLUSHDB` | 守卫全没 | 同上 | 见下 |
| 守卫 24h TTL 到期 | 守卫消失 | 同一 messageId 24h 后再补偿 | **不可能**：那时候消息和 pending 台账早就没了 |

**重复补偿的实际后果分析（关键）：**
```
第一次补偿：rollback(stock +1, SREM bought, DECR inflight)
第二次补偿：
  ① 订单存在性检查：订单确实不存在（本来就是没落库才补偿的）→ 通过
  ② SETNX 守卫：丢了 → 通过
  ③ Lua 总库存封顶：
     - 如果当前库存已经回到 totalStock → return current，库存不变 ✅
     - 如果当前库存还没到 totalStock（比如被别人抢走了 1 件）→ INCR！
```
**第三种的场景需要认真看：** 补偿后库存 = totalStock，然后恰好有另一个用户抢走了 1 件，库存变成 totalStock-1，此时第二次补偿执行 `INCR` → 库存又回到 totalStock。**结果：库存多了 1 件（相对于真实情况），而那个抢走的用户也会创建一个订单 → 总计卖出了 totalStock + 1 件 = 超卖！**

**所以这个缺口是真实存在的。** 怎么进一步兜底？

1. **DB 层的 `Conditional Update` 是最终防线**：`deductStock` 用的是 `WHERE available_stock >= #{num}`，而 `rollbackStock` 用的是 `WHERE available_stock + #{num} <= total_stock`。即使 Redis 说"有货"，DB 的 `t_seckill_activity.available_stock` 是权威的——**超卖的最后一票否决权在 DB**。
   具体：多出来的那个用户会在消费端执行 `deductStock`，如果 DB 的 `available_stock` 已经是 0（因为前面已经卖满了 totalStock 件），`rows == 0` → 抛 `NonRetryableMessageException("数据库库存不足，Redis 与 DB 库存出现漂移")` → 补偿 → 进死信。**用户看到失败，但数据是对的。**

2. **对账任务会把这个漂移修回来**：`Redis 库存 != DB 剩余 - 在途` → 告警 + 修复。

3. **`t_stock_flow.uk_order_change(order_no, change_type)`**：即使前面的防线都失效，**同一个订单号的"回补"只能落一次流水**。如果第二次补偿想再写一条 ROLLBACK 流水，会撞唯一键。注意：这**不会阻止 Redis 的 INCR**（因为 Redis 在 DB 之后执行），但它留下了一个"这条订单被回补了几次"的痕迹——**如果流水只有一条但日志显示补偿执行了两次，就能立刻定位到守卫丢失**。

**我会这样回答："守卫丢失会导致重复补偿，最终由 DB 的条件更新和唯一索引挡住，表现为'用户抢购失败'（少卖）而不是超卖。这是我在设计时就有意让防线递进的结果——每一层都不假设上一层可靠。"**

**追问：** 你怎么知道"守卫丢了"这件事发生了？（监控指标）
**答：** 三条线索：
1. **日志**：`SeckillCompensationService` 里"秒杀补偿开始"的 ERROR 日志如果对**同一个 `orderNo`** 出现两次 → 守卫失效了（因为第二次的 SETNX 本该返回 false 并打"已完成过补偿，跳过"）；
2. **指标对比**：`mall.mq.event{action=compensate}` 的计数 vs `t_stock_flow` 里 `change_type=2`（回补）的**流水行数**（由 `uk_order_change` 保证唯一）——**如果补偿次数 > 回补流水行数，说明有重复补偿被漏掉了**；
3. **对账兜底**：即使完全没监控到，`mall.reconcile.drift` 也会在下一轮对账时暴露。

**教训：** **"防重机制失效"必须有一个可观测信号，否则你只是"相信"它在工作。** 当前实现缺一个"重复补偿被守卫拦下的次数"计数器——应该把 `compensate_skipped_guard` 和 `compensate` 两个指标**放在一起看**，比值异常就是信号。

> 💡 **面试官意图：** 极品追问。考的是"你的多层防御，在一层失效时其他层还能不能兜住"，以及"你能不能推导出失效的后果"。

---

### Q53. 为什么 `sendOrderTimeout` 失败只记日志不抛异常？这里的取舍和秒杀消息有什么不同？

**结论：** 因为**延迟关单消息不是唯一保障**——有 `OrderTimeoutFallbackJob` 兜底。两个链路的可靠性要求不同，处理也就不同。

```java
public void sendOrderTimeout(OrderTimeoutMessage msg) {
    try {
        rabbitTemplate.convertAndSend(...);
        metrics.mqEvent("order-timeout", "send");
    } catch (Exception e) {
        // 延迟消息发不出去不会导致资损：订单会一直停在待支付状态。
        // 有 OrderTimeoutFallbackJob 按 expire_time 扫描兜底关闭，
        // 所以这里只记录，不影响下单主流程。
        metrics.mqEvent("order-timeout", "send_exception");
        log.error("订单超时消息发送失败，将由兜底扫描任务处理 orderNo={}", msg.getOrderNo(), e);
    }
}
```

**对比秒杀消息：**
```java
public void sendSeckillOrder(SeckillOrderMessage msg) {
    try {
        rabbitTemplate.convertAndSend(...);
    } catch (Exception e) {
        // 发送本身抛异常：消息确定没有发出去，立刻补偿，不能等 Confirm 超时
        sendTracker.compensateByMessageId(msg.getMessageId(), "生产端发送异常: " + e.getMessage());
        throw new IllegalStateException("消息发送失败", e);   // ← 抛出
    }
}
```

**为什么一个抛、一个不抛？**

| 维度 | 秒杀消息 | 延迟关单消息 |
| --- | --- | --- |
| 消息丢了的后果 | **Redis 扣了库存、订单永不创建**（库存凭空消失） | 订单一直停在"待支付" |
| 有没有兜底 | 有（Confirm/Return/超时扫描/对账） | 有（`OrderTimeoutFallbackJob` 60 秒扫一次） |
| 发消息时的事务状态 | 在秒杀接口里，**还没落库** | 在 `AFTER_COMMIT` 里，**订单已经在 DB 里了** |
| 抛异常的影响 | 用户拿到明确失败（补偿已做） | 会导致**监听器里的异常**（事务已提交，抛了也没用） |
| 选择 | **抛出**，让接口给用户明确反馈 | **只记日志** |

**关键洞察：延迟关单消息是在 `AFTER_COMMIT` 里发的，此时订单已经在 DB 里了。** 抛异常无法回滚任何东西，只会污染日志、并可能影响同一批次的其他监听器。而"发不出去"这个事实本身就已经有一个**主动扫描机制**在兜底了（`status = 0 AND expire_time <= now`，用 `idx_status_expire` 索引）——**所以最优处理就是"记日志 + 让兜底机制接手"。**

**"凡是消息触发的关键动作，都必须有一个扫描兜底"（`OrderTimeoutConsumer` 注释）——这条原则让两个链路的异常处理可以完全不同。**

**追问：** `OrderTimeoutFallbackJob` 为什么单批限制 200 条？
**答：** 代码里有 `BATCH_SIZE = 200` 和一句注释："单次扫描上限：一次捞太多会把数据库连接池占满，分批处理更稳。" 而且达到上限时会打 WARN："超时订单扫描达到批次上限 200，可能存在积压，请检查延迟队列消费情况"——**这个 WARN 本身就是一个监控信号**：正常情况下延迟队列会及时处理，扫描任务几乎捞不到数据；一旦扫描连续打满批次，说明延迟队列那条路出问题了。
**这是一个很漂亮的设计：用"兜底机制的工作量"来监控"主路径的健康度"。** 主路径正常时兜底是空的；兜底开始干活，就说明主路径有问题。

> 💡 **面试官意图：** "为什么同样的发送失败，一处抛异常一处只记日志"是极好的分辨题。答"统一处理比较规范"就暴露了缺乏判断。

---

### Q54. MQ 消息的持久化你做了哪些？只设 `deliveryMode=PERSISTENT` 够吗？

**结论：** 不够。持久化需要**三个层面**都做到，缺一个都会丢消息。

**① 消息本身持久化**
```java
private void applyCommonProperties(Message message, String messageId) {
    props.setMessageId(messageId);
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setContentEncoding(StandardCharsets.UTF_8.name());
    // 持久化：默认是 PERSISTENT，但显式写出来是为了防止有人改配置后静默变成非持久化
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
}
```
注释很关键："默认是 PERSISTENT，但**显式写出来是为了防止有人改配置后静默变成非持久化**"。这是一个"防御未来的人改错"的设计——显式比隐式好。

**② 交换机 / 队列持久化**
```java
return new DirectExchange(properties.getMq().getSeckillExchange(), true, false);
//                                                          ↑ durable=true
return QueueBuilder.durable(properties.getMq().getSeckillQueue()).withArguments(args).build();
```
`RabbitMQConfig` 的注释："`durable=true`：broker 重启后交换机依然存在，否则重启会导致生产者发送失败、消费者订阅丢失。"

**③ 集群层面的持久化（本项目 docker-compose 用单节点，生产必须补）**
- **`publisher-confirm-type: correlated` + `publisher-returns: true`**：确认消息真的进了 broker 并被路由。
- **队列镜像 / Quorum Queue**：单节点 broker 的磁盘坏了，持久化的消息一样丢。生产要用 Quorum Queue（RabbitMQ 3.8+）或镜像队列。本项目 `rabbitmq:3.13-management-alpine` 单节点，`volumes: rabbitmq-data:/var/lib/rabbitmq` 只保证容器重启不丢，**不保证磁盘故障不丢**。

**三层之外还有第四层：业务侧的兜底。** 即使消息全丢了：
- 秒杀侧：`PendingMessageRetryJob` + `SeckillReconcileJob`（Q50）；
- 关单侧：`OrderTimeoutFallbackJob`（Q53）。

**"MQ 的持久化只能保证'broker 不主动丢'，不能保证'消息一定被处理'。所以业务侧必须有对账/扫描兜底。"**

**追问：** `requested-heartbeat: 30s` 是干什么的？
**答：** TCP 心跳。当连接处于空闲状态（一段时间没有数据往来）时，AMQP 双方会交换心跳帧来确认对方还活着。30 秒意味着：如果 broker 或网络挂了，客户端最多 30 秒（实际是两个心跳周期约 60 秒）就能发现连接断开。
**为什么需要它？** 因为 TCP 连接可能处于"半开"状态——网络中断了但双方都不发数据，操作系统的 TCP keepalive 默认要 2 小时才发现。心跳把这个检测时间缩短到秒级。**没有心跳的话，生产者可能对着一个已经死掉的连接发了很久消息，直到 TCP 缓冲区满了才发现——这期间所有消息都丢了。**

> 💡 **面试官意图：** 持久化的三层 + 心跳的作用，能答全的人不多。

---

### Q55. 死信队列和延迟队列的队列名、交换机名分别是什么？（细节题）

**结论：** 全部在 `application.yml` 的 `mall.mq` 下，**没有一个字符串是硬编码在代码里的**。

| 用途 | 名称 |
| --- | --- |
| 秒杀交换机 | `mall.seckill.exchange`（direct, durable） |
| 秒杀业务队列 | `mall.seckill.order.queue` |
| 秒杀 routingKey | `seckill.order` |
| 延迟重试队列 | `mall.seckill.order.retry.queue`（TTL=5s，DLX→秒杀交换机） |
| 重试 routingKey | `seckill.order.retry` |
| 死信交换机 | `mall.dlx.exchange` |
| 秒杀死信队列 | `mall.seckill.order.dlq` |
| 死信 routingKey | `seckill.order.dlq` |
| 订单延迟交换机 | `mall.order.delay.exchange` |
| 订单延迟队列 | `mall.order.delay.queue`（TTL=30s，DLX→关单交换机） |
| 订单延迟 routingKey | `order.delay` |
| 订单关单交换机 | `mall.order.close.exchange` |
| 订单关单队列 | `mall.order.close.queue` |
| 关单 routingKey | `order.close` |

**为什么全部配置化而不是常量类？** 因为**队列名是部署环境的一部分**——测试环境、预发环境、生产环境可能需要不同的命名空间（比如加环境前缀避免共用 broker 时冲突）。硬编码在 Java 常量里就没法按环境覆盖。

**唯一"硬编码"的地方是 `@RabbitListener` 的队列名**，而且用的是 SpEL 表达式而不是字符串：
```java
@RabbitListener(queues = "#{mallProperties.mq.seckillQueue}")
@RabbitListener(queues = "#{mallProperties.mq.orderCloseQueue}")
@RabbitListener(queues = "#{mallProperties.mq.seckillDlq}")
```
**用 `#{mallProperties.mq.seckillQueue}` 而不是 `${mall.mq.seckill-queue}`** —— 前者从**已绑定的 `@ConfigurationProperties` Bean** 里取（类型安全、支持 IDEA 跳转、支持重构），后者是占位符替换（拼错了运行时才发现）。这是一个值得注意的细节。

**追问：** `RabbitMQConfig` 里为什么要用 `@Bean` 方法互相调用（`seckillOrderQueue()` 调 `to(seckillExchange())`）？
**答：** 这是 **Spring 的 `@Configuration` 代理机制**——`@Configuration` 类会被 CGLIB 增强，`@Bean` 方法之间的调用会被拦截并返回**同一个单例 Bean**，而不是每次新建一个交换机对象。
**这个机制有个知名的坑：** 如果 `@Configuration` 被换成 `@Component`（或者开了 `proxyBeanMethods = false`），`seckillExchange()` 每次调用都会**创建一个新的 `DirectExchange` 对象**——虽然 Spring 容器里注册的还是第一个，但 `Binding` 里绑定的可能是另一个实例。RabbitAdmin 声明时用的是容器里的 Bean，所以实际上还能work，但如果有任何依赖"两个引用是同一个对象"的逻辑就会出问题。**能说清这个机制是 Spring 熟练度的体现。**

> 💡 **面试官意图：** 细节题，测"你是配置的搬运工还是真的读过自己的配置"。

---

## 六、分布式锁与并发（10 题）

### Q56. Redisson 的看门狗（watchdog）是什么？本项目用了它吗？

**结论：** 看门狗是 Redisson 的**自动锁续期**机制；**本项目没依赖它**，用的是显式 `leaseTime`。

**看门狗机制：** 当调用 `lock.lock()` **不指定 `leaseTime`** 时，Redisson 默认加锁时间 30 秒（`lockWatchdogTimeout`），然后启动一个后台定时任务，**每 1/3 加锁时间（10 秒）续期一次到 30 秒**，直到 `unlock()` 被调用或进程死亡（JVM 退出后定时任务停止，锁在 30 秒内自动过期）。

**本项目用的是：**
```java
locked = lock.tryLock(pc.getRebuildLockWait().toMillis(),   // 200ms 等待
                      pc.getRebuildLockLease().toMillis(),   // 10s 租约
                      TimeUnit.MILLISECONDS);
```
**显式给了 `leaseTime = 10s`，所以看门狗不生效。**

**为什么不依赖看门狗：**
1. **重建操作是确定性的**：查一次 DB + 写一次 Redis，正常情况下几毫秒到几十毫秒。10 秒租约是 1000 倍的余量，**足够了**。
2. **避免"锁被无限续期"**：如果重建逻辑卡住（比如 DB 连接池满、慢查询），看门狗会一直续期，锁永远不释放，**所有请求都拿不到锁并降级查库**——而这正是加锁想避免的事。显式租约保证"最坏情况下 10 秒后锁自动释放"，系统能自愈。
3. **少一个后台线程**：看门狗每个锁一个定时任务（Redisson 用时间轮实现，开销不大但也不是零）。
4. **显式比隐式好**：租约时间写在配置里（`rebuild-lock-lease: 10s`），是可审计、可调整的。看门狗的 30 秒是藏在 Redisson 全局配置里的，不读文档根本不知道。

**取舍：** 显式租约的风险是"如果重建真的超过 10 秒，锁会提前释放，另一个线程也进来查库"。此时最多是**两个并发请求都查一次 DB**——比"锁永远不释放"好得多。而且这种情况会打 `contended` 指标，能被发现。

**追问：** 锁提前释放后，第一个线程重建完成时会 `unlock()`，会不会误删别人的锁？
**答：** 不会，因为用了 `isHeldByCurrentThread()` 检查：
```java
} finally {
    // isHeldByCurrentThread 是必须的：如果 tryLock 超时失败，
    // 直接 unlock 会抛 IllegalMonitorStateException；
    // 更危险的情况是锁已因超时被 Redis 释放、又被别的线程拿到，
    // 此时 unlock 会误删别人的锁。
    if (locked && lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```
**注意这里是双重保护：**
1. `locked` 变量：如果 `tryLock` 超时返回 `false`，根本不会走 `unlock`（避免 `IllegalMonitorStateException`）；
2. `isHeldByCurrentThread()`：即使 `locked == true`，如果锁已经被超时释放并被别人拿走，这里也返回 false，不会误删。

**Redisson 的 `RLock` 内部是用"锁 value = `UUID:threadId`"来实现的**，`unlock` 时会用 Lua 脚本校验 value 是否匹配（不匹配就抛异常而不是删除）。所以 Redisson 本身就能防误删——**但显式检查是更清晰的意图表达，也不依赖实现细节。**

> 💡 **面试官意图：** 看门狗是"知道名词"和"知道什么时候不该用"的分水岭。答"用了 Redisson 就有看门狗"暴露了不了解实现。

---

### Q57. `tryLock` 的 `waitTime = 200ms` 是怎么定的？

**结论：** 因为**等超过 200ms 就不如直接查库**。

```java
locked = lock.tryLock(pc.getRebuildLockWait().toMillis(),   // 200ms
                      pc.getRebuildLockLease().toMillis(),   // 10s
                      TimeUnit.MILLISECONDS);
if (!locked) {
    metrics.cacheAccess(CACHE_NAME, "lock", "contended");
    return waitForCacheFilled(productId);   // 自旋 3 × 50ms = 150ms
}
```
`waitForCacheFilled` 的注释："等待总时长约 150ms，超过就认为重建异常慢，此时降级直查数据库保证可用性（**宁可给数据库一点压力，也不能让用户一直卡着**）。"

**两条等待路径的总时长：**
- 抢锁等待：最多 200ms；
- 抢不到锁后自旋等缓存：最多 150ms；
- **最坏情况总 RT ≈ 350ms + 一次 DB 查询**（自旋超时后降级直查）。

**为什么是这两个数？**
- 缓存重建（查一次 DB + 写一次 Redis）的 P99 大概是**几毫秒到十几毫秒**（Redis RTT ~1ms，DB 单行查询 ~1-5ms，加序列化）。所以 200ms 是 **10 倍以上的余量**。
- 超过 200ms 还没拿到锁，说明**要么有大量并发在抢，要么重建本身很慢**。这两种情况下继续等都只会让用户 RT 恶化，而"直接查一次 DB"是确定性的（单次 DB 查询在几毫秒内完成）。
- **关键判断：多一次 DB 查询的代价 << 用户等 1 秒的代价。** 在缓存重建的并发量级下（不会像秒杀那么大），多几次 DB 查询完全能承受。

**取舍：**
- 如果把 `waitTime` 调大（比如 2 秒）：用户 RT 变差，而且在锁竞争严重时会有很多线程堆积在锁上。
- 如果把 `waitTime` 调成 0：立即放弃，等于**加锁完全没用**（所有并发请求都去查库）——这是最差的选择。
- **200ms 是"锁能起到收敛作用"和"用户不能等太久"的平衡点。**

**追问：** 这两个值应该根据什么调整？
**答：** 根据 **`mall.cache.rebuild` 这个 Timer 指标的 P99**。如果 P99 是 5ms，那 200ms 的 waitTime 就是 40 倍余量，甚至可以考虑下调到 50ms（让用户更快降级）。如果 P99 涨到 100ms（比如 DB 慢查询），那 200ms 就不够了，会有大量请求降级直查——**这时候正确的做法不是调大 waitTime，而是排查 DB 为什么慢。**
**"参数值应该由监控数据推导，而不是拍脑袋"——这是我会在面试里强调的话。**

> 💡 **面试官意图：** 考"参数背后的推理"。能说出"调大 waitTime 是在掩盖问题"就是加分。

---

### Q58. 异步重建里为什么要"在任务内部自己加锁"？

**结论：** 因为**锁的获取与释放必须在同一个线程**，否则 Redisson 会抛 `IllegalMonitorStateException`。

`ProductCacheService.asyncRebuild` 的注释：
> "任务内部自己加锁并做双重检查——锁的获取与释放必须在同一个线程，如果在调用线程加锁、在异步线程释放，Redisson 会抛 `IllegalMonitorStateException`。"

**这个坑很隐蔽。** 直觉上会写：
```java
// ❌ 错误写法
public void asyncRebuildWrong(Long productId) {
    RLock lock = redisson.getLock(...);
    lock.tryLock();                          // 在调用线程（Tomcat 线程）加锁
    rebuildExecutor.execute(() -> {
        try {
            ProductDetailVO vo = loadFromDb(productId);
            writeCache(productId, vo);
        } finally {
            lock.unlock();                   // ← 在异步线程解锁 → IllegalMonitorStateException!
        }
    });
}
```
**为什么抛异常：** Redisson 的锁 value 是 `UUID:threadId`。`unlock` 时会用 Lua 校验"当前线程是不是锁的持有者"。Tomcat 线程的 `threadId` 和线程池线程的 `threadId` 不同，校验失败 → 抛 `IllegalMonitorStateException`。

**更严重的是：** 如果这里用了 `lock.unlock()` 但被吞掉异常（或者用的是 `lock.forceUnlock()`），会导致锁被错误释放，而调用线程还"以为"自己持有锁。`forceUnlock` 会直接删掉锁，这是更危险的操作，**不应该出现在业务代码里**。

**正确的写法**（本项目的做法）：把 `tryLock` 和 `unlock` 都放在异步任务内部：
```java
rebuildExecutor.execute(() -> {
    RLock lock = redisson.getLock(RedisKeys.productRebuildLock(productId));
    boolean locked = false;
    try {
        locked = lock.tryLock(0, pc.getRebuildLockLease().toMillis(), TimeUnit.MILLISECONDS);
        if (!locked) return;         // 抢不到说明别人在重建，本次放弃（旧数据继续服务）
        ...  // 重建
    } finally {
        if (locked && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
        rebuilding.remove(productId);   // ← 状态清理也必须在 finally
    }
});
```

**注意 `tryLock(0, leaseTime)` 的 `waitTime = 0`：** 注释解释了："`waitTime=0`：非阻塞。抢不到说明别的线程正在重建，本次直接放弃，**旧数据继续服务**，下一次逻辑过期时还有机会。" 这是"降级而不是失败"的典型——逻辑过期场景下缓存里有可用数据，没必要等。

**追问：** `lock` 对象需要缓存复用吗？
**答：** 不需要，而且**不应该**。Redisson 的 `getLock(key)` 返回的是一个轻量的门面对象（内部通过 Redisson 的 `CommandAsyncExecutor` 与 Redis 交互），创建成本很低。而且 `RLock` 实例**不是线程安全的**（它的状态与持有它的线程绑定），所以**必须每个线程/每次调用创建新的**。缓存一个 `RLock` 实例然后跨线程使用是严重的错误。
项目里所有 `redisson.getLock(...)` 都是在方法内部调用的，符合这个纪律。

> 💡 **面试官意图：** "锁的加解锁必须在同一线程"是 Redisson 使用的基本纪律，但异步场景下极易违反。这题能测出实战经验。

---

### Q59. `rebuilding` 这个 `Set` 会不会有内存泄漏？

**结论：** 理论上不会，因为**每个分支都清理了**。但这个类确实值得仔细看，因为它有三个清理点。

```java
private final Set<Long> rebuilding = ConcurrentHashMap.newKeySet();

private void asyncRebuild(Long productId) {
    if (!rebuilding.add(productId)) {          // 已经有了 → 直接返回
        metrics.cacheAccess(CACHE_NAME, "rebuild", "dedup");
        return;
    }
    try {
        rebuildExecutor.execute(() -> {
            ...
            finally {
                ...
                rebuilding.remove(productId);   // 清理点 ①
            }
        });
    } catch (RejectedExecutionException e) {
        // 队列满：放弃本次重建
        rebuilding.remove(productId);           // 清理点 ②
        metrics.cacheAccess(CACHE_NAME, "rebuild", "rejected");
        log.warn("缓存重建线程池已满，跳过本次重建 productId={}", productId);
    }
}
```
**两个清理点覆盖了两条路径：** ① 任务真的进了线程池并执行完（无论成功失败，`finally` 保证清理）；② 任务被拒绝（`finally` 不会执行，所以必须单独清理）。

**还有第三个隐含的路径：如果 `rebuildExecutor.execute()` 在 `rebuilding.remove` 之前抛出了 `RejectedExecutionException` 之外的其他异常呢？** 比如 `execute` 内部抛 `NullPointerException`（极不可能，但理论上）。这时 `catch (RejectedExecutionException)` 不匹配，异常会向上抛给 `asyncRebuild` 的调用者（`getDetail`），而 `rebuilding` 里的 ID **永远不会被清理**。

**更稳的写法**是把 `try` 的范围扩大到包含 `execute`，用 `catch (Exception e) { rebuilding.remove(productId); throw e; }` 或者干脆 `catch (Throwable)`。**但这个"缺陷"在本项目里的实际影响是有限的**：`rebuilding` 只是一个**优化**，即使某个 productId 被永久标记为"正在重建"，后果是"这个商品不会再触发异步重建"——数据会一直陈旧到**物理 TTL 到期（30 分钟）**，之后缓存 miss 走互斥锁路径重建（那条路径不依赖 `rebuilding`）。

**我会主动在面试里承认这一点，并说明为什么选择不修：** "这是一个理论上的小缺陷。它的最坏后果是某个商品的数据陈旧 30 分钟，而且只在'`execute()` 抛出非 `RejectedExecutionException` 异常'这种极罕见情况下才发生。修它的成本是代码稍微绕一点，但我认为**在这里加一个 `catch (Throwable)` 反而是过度防御**——`ThreadPoolTaskExecutor.execute` 只可能抛 `RejectedExecutionException`，抛别的异常说明 JVM 已经出了严重问题。"

**"识别风险 → 评估后果 → 决定是否修 → 说明理由"，这个链条比"把所有风险都修掉"更能体现工程判断力。**

**追问：** 那什么情况下你会坚持修它？
**答：** 如果 `rebuilding` 承担的是**正确性职责**（而不只是优化），就必须修。比如"如果标记残留会导致这个商品永久不可用"，那就是 P0。这里它的注释明确写了"它是纯优化，即使判断失效也不会出错——**真正的互斥仍由 Redisson 锁保证**"，所以可以接受。**"这个状态如果残留，最坏后果是什么"是判断要不要做严实防护的唯一标准。**

> 💡 **面试官意图：** 高级题。面试官想看你能不能**主动指出自己代码里的理论缺陷并给出理性的取舍**，而不是硬说"我的代码没问题"。

---

### Q60. 三个线程池的拒绝策略为什么都选 `CallerRunsPolicy`？

**结论：** 因为**这三个 `TaskExecutor` 池里的任务都是"必须发生"的事**，宁可拖慢当前请求，也不能默默丢掉。

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```
出现在 `cacheRebuildExecutor`、`seckillCompensateExecutor`，`cacheDoubleDeleteScheduler`（`ThreadPoolTaskScheduler` 单线程）不涉及拒绝策略。

| 池 | 核心/最大 | 队列 | 任务性质 | 拒绝的后果 |
| --- | --- | --- | --- | --- |
| `cacheRebuildExecutor` | 4 / 8 | 2048 | 异步重建缓存 | 缓存永远重建不出来（雪崩式恶化） |
| `seckillCompensateExecutor` | 4 / 8 | 4096 | 补偿 Redis 库存 | **库存被永久占用（少卖）+ 对账才能修** |
| `cacheDoubleDeleteScheduler` | 1（scheduler，**不走拒绝策略**） | — | 延迟双删 | 缓存残留旧值 |
| `taskScheduler` | 4（scheduler，**不走拒绝策略**） | — | 所有 `@Scheduled` 任务 | 定时任务延迟执行 |

`ThreadPoolConfig` 的类注释定下了总原则：
> "**绝不使用 `Executors.newXxx()` 的默认构造。** 无界队列 + 无界线程数的默认实现，在流量突增时只会把问题从'拒绝服务'变成'**OOM**'，而且是在几分钟后以更难排查的方式爆发。每个池都显式指定：核心数、最大数、队列容量、拒绝策略、线程命名。"

**为什么 `CallerRunsPolicy` 是对的：**
```java
// cacheRebuildExecutor 的注释
// 拒绝策略选 CallerRunsPolicy：队列满说明重建任务已经积压，
// 此时让调用线程自己跑，相当于给上游一个自然的背压（请求变慢而不是被丢弃），
// 也不会出现"缓存永远重建不出来"的雪崩式恶化。
```
**关键机制：`CallerRunsPolicy` 让调用线程（Tomcat 工作线程）自己执行任务。** 这产生了一个天然的反压：
1. 队列满 → Tomcat 线程去跑重建任务 → 这个线程被占用几毫秒到几十毫秒；
2. 并发请求的响应变慢 → 上游（如果有限流/超时）会开始拒绝；
3. **系统进入"慢但正确"的状态，而不是"快但错"的状态。**

**其他三种策略为什么不合适：**
- **`AbortPolicy`（默认）**：直接抛 `RejectedExecutionException`。对缓存重建意味着"这个 key 永远重建不出来"，`rebuilding` 里的标记还会残留（虽然第二个清理点处理了）——**缓存逐渐全部失效 = 雪崩**。
- **`DiscardPolicy`**：静默丢弃。最糟糕——**你甚至不知道任务丢了**。
- **`DiscardOldestPolicy`**：丢弃队列里最老的。等于把已经排队很久的重建任务扔掉，但没有解决队列满的根本问题。

**取舍：** `CallerRunsPolicy` 的代价是**上游的 RT 会变得不可预测**。在极端情况下，Tomcat 线程池的 500 个线程可能都在跑重建任务，导致接口全面变慢。
**这是一个明确的选择：用"RT 抖动"换"数据正确"。** 而"RT 抖动"是有边界的（`setAwaitTerminationSeconds`、Tomcat 超时、上游限流），"数据错误"没有边界。

**追问 1：** `seckillCompensateExecutor` 为什么单独定义一个线程池而不是复用 `cacheRebuildExecutor`？
**答：** 三个理由：
1. **故障隔离**：缓存重建变慢（比如某商品 DB 查询很慢）不应该影响秒杀补偿。如果共用一个池，重建任务把队列占满后，补偿任务会被 `CallerRunsPolicy` 交给调用线程——而补偿的调用线程可能是 MQ 消费线程，那会拖慢消息消费。
2. **容量规划不同**：重建是"稳态低频"（只有逻辑过期时触发），补偿是"突发高频"（MQ 故障时会集中补偿）。队列容量 2048 vs 4096 的差异反映了两者的量级差异。
3. **可观测性**：每个池有业务前缀线程名（`mall-cache-rebuild-` / `mall-seckill-compensate-`），`jstack` 一眼就能看出是哪个池打满了。
```java
// ThreadPoolConfig 的注释
// 线程命名我特意加了业务前缀：出问题时 jstack 一眼能看出是哪个池打满了，
// 不用去数 pool-7-thread-13 这种毫无信息量的名字。
```

**追问 2：** 项目里的"定时任务"用的是线程池还是调度器？它们的隔离性怎么保证？
**答：** 这是**另一个独立的资源层，而且我曾经在这里踩过一个真实的坑（见第十四节缺陷 ⑤）**。

现在 `ThreadPoolConfig` 里有 **4 个 Bean**，分成两类：

| Bean | 类型 | 规格 | 用途 |
| --- | --- | --- | --- |
| `cacheRebuildExecutor` | `ThreadPoolTaskExecutor` | 4 核心 / 8 最大 / 队列 2048 | 缓存异步重建（由业务代码 `execute()` 提交） |
| `seckillCompensateExecutor` | `ThreadPoolTaskExecutor` | 4 核心 / 8 最大 / 队列 4096 | 秒杀补偿 |
| **`taskScheduler`** | `ThreadPoolTaskScheduler` | **4 线程 + `@Primary`** | **所有 `@Scheduled` 任务**（对账、预热、超时兜底、投递补偿） |
| `cacheDoubleDeleteScheduler` | `ThreadPoolTaskScheduler` | 1 线程 | 只服务延迟双删（用 `@Qualifier` 显式注入） |

**Executor 和 Scheduler 的区别：** `@Scheduled` 需要的是 `TaskScheduler`（它要支持 `CronTrigger` / `fixedDelay` 这类"按时间点触发"的语义），而不是 `ThreadPoolTaskExecutor`。**这是两个不同的抽象，不能混用。**

**我在这个位置上犯的错：** 原本只定义了一个单线程的 `ThreadPoolTaskScheduler`（本意是给延迟双删用），**结果它被 `@EnableScheduling` 当成了所有 `@Scheduled` 任务的调度器** —— 日志里能看到 `mall-cache-double-delete-1` 这个线程在跑「订单超时兜底扫描」，名字和职责完全对不上。
**危害是一个任务卡住会让所有定时任务停摆**：对账任务扫到一张大表卡了 3 分钟，期间的超时关单兜底也跟着停了。

**修复：**
```java
// 业务定时任务的调度器：4 线程 + @Primary
@Bean(name = "taskScheduler")
@Primary
public ThreadPoolTaskScheduler mallTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("mall-sched-");        // ← 线程名不再误导
    scheduler.setRemoveOnCancelPolicy(true);
    // 全局兜底异常处理：@Scheduled 方法抛出的异常如果没人接，
    // Spring 只会打一行日志，而且默认行为是【停止后续调度】——
    // 一个偶发异常就会让这个任务永远不再执行。这是最容易埋雷的地方。
    scheduler.setErrorHandler(t -> log.error("定时任务执行失败（已捕获，后续调度不受影响）", t));
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(10);
    scheduler.initialize();
    return scheduler;
}
```
**三个修复要点：**
1. **Bean 名字刻意取成 `taskScheduler`** —— Spring 的 `ScheduledAnnotationBeanPostProcessor` 会**优先按这个名字查找**，加上 `@Primary` 双保险，避免类型注入时的歧义；
2. **`@Primary` 让"默认调度器"的选择变成显式的**，而不是"取决于容器里恰好有哪个 Bean"（这正是原来出错的机制）；
3. **独立的线程名前缀 `mall-sched-`** —— 这样 `jstack` 和日志里能一眼区分"这是定时任务"还是"这是延迟双删"。**原来的 Bug 之所以能被发现，就是因为日志里的线程名和任务名对不上。**

**为什么延迟双删仍然保持单线程？** 因为它**不需要并发**（延迟删除任务非常轻，一条 `DEL`），而且"同一时刻只有一个删除任务在跑"便于排查。**关键是它现在通过 `@Qualifier("cacheDoubleDeleteScheduler")` 显式注入，不会再抢走 `@Scheduled` 的调度权。**

**还有一个额外的收获：`scheduler.setErrorHandler(...)`。** 修复这个 Bug 时我才注意到：**`@Scheduled` 方法抛出的未捕获异常，Spring 的默认行为是停止后续调度** —— 一个偶发异常就会让这个任务永远不再执行。
项目里每个 `@Scheduled` 方法的**整个方法体都已经包在 try-catch 里**（`PendingMessageRetryJob` 的注释明确说了"定时任务抛异常会导致后续调度被抑制（Spring 默认行为），所以必须自己兜住，保证任务永远活着"），**加上这个全局 `ErrorHandler` 就是双保险**：方法内漏掉的异常由调度器统一兜住。

> 💡 **面试官意图：** "为什么不用默认的 AbortPolicy"是很好的分辨题，能说出"CallerRunsPolicy 是一种背压"就到位了。**能进一步说出"`TaskScheduler` 和 `TaskExecutor` 是两个抽象"、"自己定义任何一个 `TaskScheduler` 会抑制 Spring Boot 的自动装配"、"默认调度器被抢走会让所有定时任务串在一根线程上"——这是生产级的踩坑经验，极少有人能答到。**

---

### Q61. `rebuilding`、`LocalSoldOutCache`、Caffeine 这些进程内状态，多实例部署时怎么保证不出错？

**结论：** 都遵守同一条原则——**进程内状态只做优化，不做正确性判断；正确性交给 Redis / DB。**

| 进程内状态 | 作用 | 多实例的后果 | 兜底 |
| --- | --- | --- | --- |
| `ProductCacheService.localCache`（L1） | 减少 Redis RT | 3 秒内不一致 | `l1-ttl = 3s` |
| `ProductCacheService.rebuilding` | 重建任务提交去重 | 别的实例也在重建（最多 N 个重建任务） | Redisson 锁 |
| `LocalSoldOutCache` | 挡住售罄后的流量 | A 实例标记了，B 实例还在放请求 | Redis Lua 正确拒绝 |
| `SeckillActivityService.localCache` | 减少活动快照的 Redis RT | 5 秒内活动信息可能陈旧 | `activity-local-ttl = 5s` |
| `SeckillReconcileService.staleInflightCounter` | 在途泄漏采样计数 | 各实例独立计数 | 见下 |

**每一条都经过同一次检验："如果这个状态在某个实例上是错的（或者根本不存在），系统会怎样？"**
- L1 过期 → 回 L2 拿，正确；
- `rebuilding` 残留 → 该商品靠物理 TTL 到期后走互斥锁路径重建，正确；
- 售罄标记残留 → 10 秒 TTL 后自愈，或者被 `rollback`/`resetStock` 主动清除，正确；
- 活动快照陈旧 → 5 秒 TTL，且活动的开始/结束时间不会频繁变，正确。

**唯一的例外是 `staleInflightCounter`。** 多实例各自维护计数，理论上：
- 实例 A 采样 3 次都看到在途 > 0 → 判定泄漏 → 修复；
- 实例 B 也采样 3 次 → 也判定泄漏 → **也修复**。

两次修复都执行 `resetInflight` + `resetStock(dbAvailable)`，**结果是一样的（幂等）**，所以不会造成数据错误。但如果 `auto-repair` 开启且两个实例**同时**执行，会有一个短暂的竞争窗口（`SET` 操作是幂等的，最终值相同）。

**我在面试里会主动指出这个缺口：** "生产环境应该给对账任务加一个分布式锁（`lock:seckill:reconcile:{activityId}`），保证同一时刻只有一个实例在修同一个活动。**当前实现的结果是幂等的所以不会出错，但会浪费重复的 DB 查询和 Redis 写。**"

**"多实例下这个状态不一致会怎样"这个问题，应该在设计每一个进程内状态时都问自己一遍。** 这是我在这个项目里反复使用的检查清单。

**追问：** 那为什么不用 Redis 来做"正在重建"的标记（比如 `SETNX lock:rebuild:{id}`）？
**答：** **因为那正是 Redisson 锁在做的事。** 再加一层 Redis 标记是纯冗余：多一次 RTT、多一个可能不一致的状态、多一个需要清理的 key。`rebuilding` 用本地 `Set` 的价值在于"**零网络成本地拦住绝大多数重复提交**"——如果任务真的提交到了线程池，里面还有 Redisson 锁兜底。
**"优化的层用最便宜的方式做，正确性交给可靠的一层"——这个分工让每一层都做自己擅长的事。**

> 💡 **面试官意图：** "进程内状态在多实例下的正确性"是分布式系统的核心问题。能列出清单并说出兜底机制，说明你系统性地想过。

---

### Q62. `UserContext` 用 `ThreadLocal`，如果用了虚拟线程或者异步，会有问题吗？

**结论：** 会有问题，而且本项目有意避开了这些场景。

```java
private static final ThreadLocal<LoginUser> CURRENT = new ThreadLocal<>();
```
写入在 `LoginInterceptor.preHandle`，清理在 `afterCompletion`：
```java
@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                            Object handler, Exception ex) {
    UserContext.clear();
}
```
注释说明了为什么必须清理：
> "Tomcat 线程池会复用工作线程，只在 `preHandle` 里 set 而不清理，下一个复用同一线程的请求就会带着上一个用户的身份进入业务逻辑——**这是一类真实发生过的越权漏洞**。放在 `finally` 里保证异常路径也会清理。"

**三种会出问题的场景：**

| 场景 | 问题 | 本项目的状态 |
| --- | --- | --- |
| **业务里提交异步任务**（`executor.submit(() -> UserContext.getUserId())`） | 子线程读不到父线程的 ThreadLocal | ⚠️ 项目里 `asyncRebuild` 和 `inflightDec` **都不读 UserContext**，只用传入的参数 |
| **虚拟线程** | `ThreadLocal` 在虚拟线程上是可用的（`ThreadLocal` 存在 `Thread` 对象里，虚拟线程也是 `Thread`），但有**内存开销问题**：一个虚拟线程一个 `ThreadLocal` 副本，百万虚拟线程 → 内存爆炸 | ⚠️ 项目**只在 Tomcat 线程**上写 UserContext；`SeckillStockConcurrencyTest` 里的虚拟线程**只用传入的 userId**，不碰 UserContext |
| **线程池复用 + 忘记清理** | 身份串号（越权） | ✅ 用 `afterCompletion` 保证清理 |

**为什么本项目没有用 `InheritableThreadLocal` 或 `TransmittableThreadLocal`：** 因为**没有需要传递用户上下文的异步场景**。所有需要用户 ID 的地方都在 Controller → Service 的同步调用链里。**"不引入不需要的机制"本身就是一种设计。**

**但这个纪律需要显式维护：** `asyncRebuild` 和 `inflightDec` 都在线程池里执行，它们需要的信息全部通过**方法参数**传递（`productId`、`activityId`），而不是从 `UserContext` 读。这是一个值得注意的编码规范：**异步任务不接受隐式的线程上下文，只接受显式的参数。**

**追问：** 那虚拟线程 + `ThreadLocal` 的组合你怎么看？
**答：** 这是 Java 21 之后一个真实的问题。`ThreadLocal` 在虚拟线程上的语义是"每个虚拟线程一份副本"，如果一个请求创建 1000 个虚拟线程，就会有 1000 份副本。**Scoped Values（JEP 446）就是为了替代这个场景设计的**——它的作用域是结构化的（跟着代码块而不是线程走），生命周期更短、支持共享不可变值。
**本项目暂时不需要迁移**，因为：① 用户上下文只在 Tomcat 线程内有效；② 测试里的虚拟线程不读上下文。**但如果将来要把秒杀接口改造成"一个请求 spawn 多个虚拟线程"的形态，就必须把 `UserContext` 换成 `ScopedValue`。**

> 💡 **面试官意图：** `ThreadLocal` + 虚拟线程是 Java 21 时代的前沿考点。能区分"语义上可用"和"性能上不合适"是加分项。

---

### Q63. 秒杀场景如果非要用分布式锁，你会怎么设计？

**结论：** 如果一定要用，我会**按活动 ID 分段加锁**，并且**只锁"消息投递"这一段**，而不是锁整个扣减流程。

**为什么只锁投递：** 因为扣减已经被 Lua 原子化了，唯一可能存在竞争的是"投递 MQ 与本地补偿"这一段（比如同一用户短时间内重复投递）。把锁的范围压到最小是分布式锁的第一原则。

**设计：**
```java
// 锁的粒度为"活动"，避免锁粒度过细导致锁数量爆炸
RLock lock = redisson.getLock("lock:seckill:" + activityId);
// waitTime = 0（不等待，快速失败）——秒杀场景用户不能等
// leaseTime = 3s（足够一次 MQ 投递，且远小于活动周期）
if (lock.tryLock(0, 3, TimeUnit.SECONDS)) {
    try { messageSender.sendSeckillOrder(msg); }
    finally { lock.unlock(); }
} else {
    throw new BusinessException(ResultCode.SECKILL_BUSY);
}
```

**这个方案的问题（我会全部说出来）：**
1. **吞吐下降一个数量级**：所有对同一活动的投递被串行化。如果 MQ 投递需要 2ms，那把 10000 QPS 压到 500 QPS。
2. **需要处理锁超时/续期/误删**（Q56、Q58 的所有问题）。
3. **锁的粒度两难**：按活动锁 → 同活动串行；按用户锁 → 锁数量爆炸（几十万个用户 = 几十万个 key）。**而秒杀的核心矛盾恰恰是"同一个活动被大量用户同时抢"，任何按活动的串行化都直接摧毁吞吐。**
4. **引入新的故障点**：Redisson 挂了 → 整个秒杀不可用（而当前设计下 Redis 挂了 L2 还有别的降级路径）。

**所以我的结论是：**
> "秒杀这个场景**不适合用分布式锁**，因为它的竞争模式（大量用户抢同一个活动）和锁的串行化本质是冲突的。锁适合的是'竞争稀疏但每次操作昂贵'的场景——比如缓存重建（Q60：竞争少、操作是两次跨资源往返）。**选锁的标准是'临界区里有没有值得被串行化的昂贵操作'，而不是'有没有并发'。**"

**追问：** 那如果需求变成"同一个用户 1 秒内只能抢 1 次"呢？
**答：** 这**不需要分布式锁**，用限流就够了——而且项目里已经做了：`mall.rate-limit.rules.seckill` 是 `5/1s` 滑动窗口，维度包含 `USER`。如果要更严，把 limit 调成 1 即可。
**"用限流解决重复请求"比"用锁解决重复请求"轻量得多**：限流是无状态的判定（Lua + ZSET），不需要持锁、不需要续期、不需要处理锁释放。这是一个典型的"**用更弱的原语解决更弱的问题**"的判断。

> 💡 **面试官意图：** 反问式题。考你能不能**论证"不该用"**，而不是被问题引导着去设计一个不该存在的方案。

---

### Q64. 为什么"回补 Redis"必须放在数据库事务提交之后？反过来的两种失败模式分别是什么？

**结论：** 因为两种失败模式的后果**完全不对称**。

`OrderTimeoutConsumer` 的注释：
> "Redis 不参与数据库事务。如果把它放在事务内：
> ```
> 回补 Redis 库存 → 后续语句失败 → 数据库回滚 → 但 Redis 已经加回去了
> 结果：库存凭空多出来 → 超卖
> ```
> 反过来（先提交数据库、再回补 Redis）最坏情况是 Redis 没来得及加回去，这时对账任务会把库存修回来。**两种失败模式的后果完全不对称**：一个导致超卖（资损），一个导致少卖（可由对账自愈）。工程上永远选后者。"

**两种失败模式的详细对比：**

| 顺序 | 崩溃/失败点 | Redis 状态 | DB 状态 | 后果 | 能自愈吗 |
| --- | --- | --- | --- | --- | --- |
| **先 Redis 后 DB（❌）** | DB 事务回滚 | 库存已 +1 | 订单仍是待支付 | **超卖**（库存虚高） | ❌ 对账会按 DB 修回来，**但超卖的订单已经产生** |
| **先 DB 后 Redis（✅）** | Redis 调用失败 | 库存没 +1 | 订单已取消 | **少卖**（库存少了 1） | ✅ 对账把 Redis 对齐到 DB |

**再深一层：为什么"对账能修"这么重要？**

对账的不变式：`Redis 库存 == DB available_stock - 在途`。
- 在"先 DB 后 Redis 失败"的场景里：DB 的 `available_stock` 已经 +1 了，Redis 没 +1 → `drift = redisStock - (dbAvailable - 0) = -1` → **对账立刻发现并修复**（把 Redis 对齐到 DB）。
- 在"先 Redis 后 DB 失败"的场景里：Redis +1 了，DB 回滚了（`available_stock` 没变）→ `drift = +1` → 对账也会发现并修复 **Redis**（对齐到 DB）。**但那个已经"看到有货"的用户如果拿到了库存并下单成功了呢？** 那就真的超卖了——**因为对账到修复之间有一个窗口（最长 5 分钟 + 3 次采样）**。在这个窗口里，虚高的库存会被真实用户抢走。

**"能被对账发现"不等于"不会造成损害"——受损的窗口长度决定了严重程度。** 所以正确顺序是"先 DB 后 Redis"，把损害窗口压到最小。

**这个原则在项目里的一致性体现：**
1. `OrderService.cancelIfUnpaid`：`@Transactional` 方法里只做 DB 操作，`CancelResult` 返回后由调用方（`OrderTimeoutConsumer` / `OrderTimeoutFallbackJob`）执行 `stockService.rollback(...)`；
2. `OrderCreatedListener`（AFTER_COMMIT）：订单提交后才 `inflightDec` + `sendOrderTimeout`；
3. `SeckillCompensationService`：先查 DB（订单是否存在），再改 Redis。

**追问：** 那"扣减"为什么可以先 Redis 后 DB？
**答：** 因为**方向不同，危害也不同**：
- 扣减是"先 Redis 扣、再 DB 扣"。如果 DB 扣失败（`rows == 0`）→ 抛 `NonRetryableMessageException` → 补偿把 Redis 加回去。此时 Redis 已经扣了但 DB 没扣，**Redis 库存比真实少 = 少卖**，对账能修。
- 如果反过来"先 DB 扣、再 Redis 扣"：DB 扣了、Redis 没扣 → Redis 库存虚高 → **这个用户和其他用户都可能再抢 → 超卖 + DB 库存已经扣了但没有对应订单**。

**所以两个方向都是"先把损害限制在少卖一侧"。** 这是同一个原则的两个应用：**任何跨资源的操作，都要选择"失败时偏向少卖"的顺序。**

**"少卖"之所以能被接受，是因为有对账这个自愈机制把它变成"暂时少卖"。** 而这个自愈机制的前提是"DB 是权威"——如果 DB 也可疑，整个体系就塌了。所以 **DB 的约束（唯一索引、CHECK、事务）是整个架构的地基。**

> 💡 **面试官意图：** 这是分布式事务的经典问题。能说出"两种失败模式不对称"就到位了；能进一步说清"对账能修不等于没损害"就是资深。

---

### Q65. 你项目里的并发问题，除了这些还有哪些地方用了同步机制？

**结论：** 我把项目里的同步机制做了一张全景表，因为面试官经常问"你项目里哪里有并发"。

| 位置 | 机制 | 为什么用这个（而不是别的） |
| --- | --- | --- |
| 秒杀扣减 | **Redis Lua** | 原子、1 次 RTT、无锁开销 |
| DB 库存扣减 | **条件 UPDATE + InnoDB 行锁** | 把判断压进 DB，比 `SELECT FOR UPDATE` 少一次往返 |
| 订单状态流转 | **条件 UPDATE（状态机）** | `WHERE status = 0` 让并发只有一个成功 |
| 一人一单 | **唯一索引 `uk_user_activity`** | 数据库层面的强约束，不依赖应用判断 |
| 消息幂等 | **唯一索引 `uk_message_id`** | 同上 |
| 防重复回补 | **唯一索引 `uk_order_change` + Redis SETNX** | DB 层 + Redis 层的双重防重 |
| 缓存重建 | **Redisson `RLock`** | 临界区是"查 DB + 写 Redis"两次跨资源往返，值得串行化 |
| 重建任务去重 | **Caffeine 键集 `ConcurrentHashMap.newKeySet()`** | 纯优化，零网络成本 |
| 本地售罄标记 | **Caffeine（无锁内部实现）** | 只做快速否定 |
| 内存台账 | **Caffeine `Cache`** | 有界 + TTL，避免 OOM |
| 指标句柄缓存 | **`ConcurrentHashMap.computeIfAbsent`** | 避免重复拼接标签 + 注册 |
| 在途泄漏计数 | **`ConcurrentHashMap.merge`（原子累加）** | 避免"读-改-写"竞态 |
| 用户上下文 | **`ThreadLocal` + `afterCompletion` 清理** | 请求级隔离 |

**这份表格能直接回答"你项目里哪些地方处理了并发"，而且每一条都有"为什么不用别的"。**

**特别值得指出的三个"用简单机制解决复杂问题"的例子：**

1. **用 `ConcurrentHashMap.merge` 而不是 `if (map.get(x) == null) { map.put(x, 1); } else { map.put(x, map.get(x) + 1); }`**
   ```java
   int streak = staleInflightCounter.merge(activityId, 1, Integer::sum);
   ```
   后者有经典的"读-改-写"竞态（两个线程都读到 null，都 put 1，结果应该是 2 但实际是 1）。

2. **用 `ConcurrentHashMap.computeIfAbsent` 缓存 Micrometer 句柄**
   ```java
   return counters.computeIfAbsent(key, k -> Counter.builder(name).tags(tags).register(registry));
   ```
   `MallMetrics` 注释说明了原因："Micrometer 的 `registry.counter(name, tags)` 每次调用都要做一次标签拼接和 map 查找，放在秒杀热路径上是纯粹的浪费。"

3. **用数据库唯一索引而不是应用层判断**
   ```java
   // OrderCreationService 的注释
   // 先查一次是为了给出明确的异常语义；真正的并发正确性由
   // t_mq_message.uk_message_id 保证（两个消费者同时查到"不存在"是必然的）。
   ```
   **"应用层的 if 判断在并发下是无效的"** —— 这是分布式系统里最重要的一条认知。

**追问：** 那项目里哪些地方用了 JUC 的原子类或 CAS？为什么用它们而不是别的方式？
**答：** **只有测试代码用了 `AtomicInteger` 做计数**（`SeckillStockConcurrencyTest` 里统计 success/soldOut/repeat）——因为那是"一个进程内累加"的场景，不需要跨进程正确性。
**生产代码里没有用 CAS 做业务判定**，这是刻意的：
- **跨进程的并发用 Redis Lua / DB 条件更新**（它们才是正确的抽象）；
- **进程内的累加用 `ConcurrentHashMap.merge`**（`staleInflightCounter`）——比手写 CAS 循环简单，而且 `merge` 本身就是原子的；
- **进程内的缓存句柄用 `computeIfAbsent`**（`MallMetrics`）——同样是原子的复合操作。

**判断标准：** **"这个状态的正确性是否需要跨进程？"** 需要 → Redis/DB；不需要 → 用 JUC 的现成原子复合操作，**而不是自己写 CAS 循环**（容易遗漏 ABA、容易在失败重试里写错）。

> 💡 **面试官意图：** 展示"并发处理的全景"而不是零散的点。这张表本身就是一个很好的回答结构。

---

## 七、MySQL 与索引（13 题）

### Q66. 三组唯一索引分别是什么？各防什么问题？

**结论：** `01-schema.sql` 里明确列了，这是"最终兜底幂等"的设计。

```sql
PRIMARY KEY (id),                                          -- 雪花 ID
UNIQUE KEY uk_order_no (order_no),                         -- 订单号幂等
-- 一人一单：Redis 的 Set 判定只是「准入闸门」，真正的强约束在数据库
UNIQUE KEY uk_user_activity (user_id, activity_id),        -- 一人一单
-- 消息幂等：同一消息重复投递时，第二条 INSERT 直接撞唯一键，天然去重
UNIQUE KEY uk_message_id (message_id),                     -- MQ 消息幂等
```

| 索引 | 防什么 | Redis 侧对应的"软"防 | 失效场景 |
| --- | --- | --- | --- |
| `uk_order_no` | 同一订单号重复插入 | 订单号由雪花 ID 生成，本身几乎不重复 | 雪花 ID 撞车（workerId 配置错误）、人为重放 |
| `uk_user_activity` | 一人多单 | `seckill:bought` Set（`SISMEMBER`） | Redis 主从切换丢写、Set 被误删、活动重新预热清空 |
| `uk_message_id` | 同一消息重复消费 | 无（`alreadyConsumed` 是先查，有竞态） | 消息重复投递（至少一次语义下的必然现象） |

**文件头的设计说明第 4 条写得很清楚：**
> "订单表上用【三组唯一索引】做最终兜底幂等：`uk_order_no` —— 订单号幂等；`uk_user_activity` —— 一人一单（**并发下 Redis Set 判定失效时的最后防线**）；`uk_message_id` —— MQ 消息幂等。"

**还有第四个在别的表上：**
```sql
-- t_stock_flow
UNIQUE KEY uk_order_change (order_no, change_type)
```
> "保证同一订单的『扣减』『回补』各只能落一次，杜绝超时关单与死信回补重复回补库存。"

**取舍：唯一索引的代价是"插入失败要处理"。**
```java
try {
    orderMapper.insert(order);
} catch (DuplicateKeyException e) {
    // 走到这里说明消费记录没冲突、但订单唯一键冲突了，
    // 即 uk_user_activity / uk_order_no 命中。
    // 意味着 Redis 侧的一人一单判定被绕过，属于必须告警的一致性缺口。
    throw new DuplicateOrderException(...);
}
```
- 必须**显式转换异常语义**（`DuplicateKeyException` → `DuplicateOrderException`），让消费端能做正确决策。
- **`uk_user_activity` 命中是一个"必须告警"的信号**（说明 Redis 侧失效了），而不是"正常业务"。

**追问：`uk_user_activity(user_id, activity_id)` 为什么不是 `(activity_id, user_id)`？**
**答：** 因为这个索引的**查询模式**决定的。它同时服务两个需求：
1. **插入时的唯一性校验**（与列顺序无关）；
2. **查询"某个用户在某个活动下的订单"**（最左前缀匹配 `user_id`）。

用户的订单查询（`OrderService.listByUser`）是"按 userId 查"——虽然它用的是 `idx_user_create(user_id, create_time)`，但如果将来要查"某用户在某活动下的订单"，`uk_user_activity` 就能直接服务。
如果反过来写成 `(activity_id, user_id)`，那么："按 user_id 查"就用不了这个索引（违反最左前缀），而"按 activity_id 查"有单独的 `idx_activity(activity_id)` 覆盖。**所以 `(user_id, activity_id)` 的复用价值更高。**

**这是一个"索引列顺序要按查询模式定"的实例**——而不仅仅考虑唯一性（唯一性对列顺序不敏感）。

**追问：** `t_order_item` 为什么不需要唯一索引？它不怕重复插入吗？
**答：** `t_order_item` 里**只有 `KEY idx_order_id` 和 `KEY idx_order_no`，没有唯一索引**——因为它的重复插入**已经被订单表的唯一键挡住了**：
```java
orderMapper.insert(order);        // ← 这里会撞 uk_message_id / uk_user_activity / uk_order_no
orderItemMapper.insert(item);     // ← 只有上面成功了才会执行
```
两者在**同一个事务**里，所以：
- 订单唯一键冲突 → 抛 `DuplicateOrderException` → 整个事务回滚 → **订单项也不会留下**；
- **所以"订单项重复"这件事在当前设计下不可能发生。**

**反向思考：如果 `t_order_item` 也加了唯一索引（比如 `uk_order_product(order_id, product_id)`），会怎样？** 在"一个订单里有多个相同商品"的场景下（比如买 2 件同一商品但拆成两行）就会误拦。**本项目每单只有一个商品项（`quantity` 字段表达数量），所以没有这个需求。**
**结论：不是每张表都需要唯一索引——只需要在"可能被重复写入的入口"上放唯一索引。** 订单项是订单的从属数据，它的写入时机由订单的唯一性保证，再加一层是冗余的。

> 💡 **面试官意图：** 唯一索引 + 索引列顺序是 MySQL 的必考项。能说出"列顺序按查询模式定"就到位了。

---

### Q67. `useAffectedRows=true` 这个参数是干什么的？不配会怎样？

**结论：** 不配的话，MySQL Connector/J 默认会带上 `CLIENT_FOUND_ROWS` 标志，让 `UPDATE` 返回**"匹配行数"**而不是**"实际变更行数"**——而本项目的核心逻辑**全靠影响行数判断是否抢到**，会直接失真。

`SeckillActivityMapper` 的注释：
> "注意 JDBC 参数 `useAffectedRows=true`：MySQL Connector/J 默认会带上 `CLIENT_FOUND_ROWS` 标志，使 `UPDATE` 返回的是『匹配行数』而不是『实际变更行数』，进而让这种『靠影响行数判断是否抢到』的写法在边界情况下失真。"

**配置位置：**
```yaml
url: jdbc:mysql://...?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&useAffectedRows=true
```

**具体会失真在哪？**

`CLIENT_FOUND_ROWS` 让 MySQL 返回"WHERE 匹配到的行数"。对于：
```sql
UPDATE t_order SET status = 2, cancel_time = ? WHERE order_no = ? AND status = 0
```
- **开了 `useAffectedRows=true`（默认值域是 false，这里显式设为 true）**：返回"实际被修改的行数"。如果订单已经是 `status = 2`，`WHERE` 不匹配 → 返回 **0**。
- **没开（默认行为）**：返回"匹配的行数"——但如果 `WHERE` 不匹配，匹配行数也是 0。

**等一下，这里需要更精确。** `CLIENT_FOUND_ROWS` 影响的场景是：**`WHERE` 匹配到了行，但 `SET` 的值和原值相同**。例如：
```sql
-- 订单已经是 status=2，你再执行一次
UPDATE t_order SET status = 2 WHERE order_no = 'X' AND status = 2
```
- 有 `CLIENT_FOUND_ROWS`：返回 **1**（"我找到了 1 行"）；
- 无 `CLIENT_FOUND_ROWS`（即 `useAffectedRows=true`）：返回 **0**（"我没有真的改任何行"）。

**在本项目的真实场景里：** 大部分 `UPDATE` 都带了 `WHERE status = 前驱状态` 这样的条件，所以"匹配但未变更"的情况较少。**但风险是真实存在的**，特别是在这些地方：
- `rollbackStock`：`WHERE available_stock + num <= total_stock`。如果回补后库存**恰好**不变（理论上不会，因为 `+num` 一定改变值），不会触发。
- `deductStock`：`WHERE available_stock >= num`，`SET available_stock = available_stock - num`。**如果 `num = 0`**（脏数据传了 0），那么匹配但未变更 → 有 `CLIENT_FOUND_ROWS` 会返回 1，被误判为"扣减成功"。

**真正的教训是：** "靠影响行数判断业务结果"这个模式**依赖于连接参数的精确语义**。这是一个跨层的隐式依赖——Java 代码里的 `if (rows == 1)` 实际上依赖 JDBC URL 里的一个参数。**显式设置 `useAffectedRows=true` 是把这种隐式依赖显式化。**

**追问：** 为什么不在 Java 里加一层校验（比如扣减后重新查一次库存）？
**答：** 那要多一次 DB 查询，而且**同样有并发窗口**（查的时候别的请求又改了）。**正确的做法是把语义建立在"单条 SQL 的条件更新"上**——`WHERE available_stock >= num` 本身就表达了"只有库存够才扣"，`SET available_stock = available_stock - num` 是原子执行。影响行数就是"这个条件是否成立"的权威答案。
**加一层查询是在用更多的代码换来更多的竞态，是典型的反向优化。**

> 💡 **面试官意图：** 这是一个非常"细"的题，能答对说明你真的踩过或者至少理解这个参数。答不出来不致命，但答对了很亮眼。

---

### Q68. `CHECK` 约束在 MySQL 里真的生效吗？

**结论：** **MySQL 8.0.16+ 才真正生效**，之前的版本会解析但忽略。

`01-schema.sql` 的注释明确了这一点：
```sql
-- 库存不能为负：即使应用层校验被绕过，数据库这层也要拦下来。
-- （MySQL 8.0.16+ 的 CHECK 才真正生效；写成列内约束保证 DDL 幂等）
CONSTRAINT ck_activity_stock_non_negative CHECK (available_stock >= 0)
```

**两个设计细节：**

1. **为什么是"表级约束"而不是"列内约束"？** 注释说"写成列内约束保证 DDL 幂等"——实际上这里的写法是**表级 `CONSTRAINT`**，配 `CREATE TABLE IF NOT EXISTS` 实现 DDL 幂等（整体跳过创建）。如果用 `ALTER TABLE ... ADD CONSTRAINT` 就需要额外的"存在性判断"。
   *（这里我在面试时会诚实地说：注释里的"列内约束"措辞和实际写法的表级 `CONSTRAINT` 略有出入，从 DDL 幂等的角度，`CREATE TABLE IF NOT EXISTS` + 表级 `CONSTRAINT` 是前后一致的做法。）*

2. **它的定位是"最后一道防线"**：注释写得很清楚——"**即使应用层校验被绕过**（比如直接改库、脚本 bug）时数据库还能拦住库存变负"。

**为什么有了 Redis Lua 和多层条件更新，还要这个？**

| 层 | 能防什么 | 防不了什么 |
| --- | --- | --- |
| Lua `if stock <= 0 then return 1` | 正常流量下的超卖 | Redis 数据被人工改错 |
| `WHERE available_stock >= num` | 并发下的 DB 超卖 | **`SET available_stock = -1` 这样的直接写** |
| `CHECK (available_stock >= 0)` | **任何来源的负库存** | 逻辑上的超卖（库存非负但卖出超过总量） |

**CHECK 的价值在于它是"不管你怎么写 SQL 都拦得住"的约束。** DBA 手工执行的 SQL、数据修复脚本、运维工具——只要让库存变成负数就会被数据库拒绝。

**取舍：** CHECK 约束的代价是**写操作的轻微开销**（每行更新都要校验），以及在**数据修复时的麻烦**（比如你想临时把库存改成 -1 来标记异常，会被拒绝）。
**"不能写入非法数据"这个收益远大于"修复时多一点麻烦"。** 而且如果确实需要修复，可以用 `SET SESSION check_constraint_checks = OFF`（MySQL 8.0.16+ 之前需要用 `ALTER TABLE ... DROP CHECK`）——**但这时候你至少是显式地、有意识地绕过了防护，而不是静默地写入了错误数据。**

**追问：** 那 `total_stock` 和 `available_stock` 的关系也有约束吗？
**答：** **没有 CHECK 约束，而是在应用层的 SQL 条件里表达：**
```sql
UPDATE t_seckill_activity
   SET available_stock = available_stock + #{num}
 WHERE id = #{activityId}
   AND available_stock + #{num} <= total_stock
```
为什么不做成 `CHECK (available_stock <= total_stock)`？**因为那会引入一个更危险的约束**：如果运营需要调整 `total_stock`（比如补货），而当前 `available_stock` 恰好大于新的 `total_stock`，那么 **`UPDATE total_stock` 本身会失败**——你连补货都做不了，还得先临时改库存。**把约束写在应用层的条件里，既有同样的保护效果，又不阻塞合法的运维操作。这是一个很实际的取舍。**

> 💡 **面试官意图：** "MySQL 的 CHECK 约束从哪个版本开始生效"是个经典坑题。能连带说出"为什么 total_stock 不用 CHECK"就更好了。

---

### Q69. 为什么订单主键用雪花 ID 而不是自增？

**结论：** 三个理由，`01-schema.sql` 的设计说明里写了前两个。

> "秒杀订单主键、消息表主键使用【雪花 ID】而非自增：高并发插入下 InnoDB 自增主键存在 **AUTO-INC 锁竞争**，且自增在**分库分表**下无法全局唯一；雪花 ID **趋势递增**，兼顾 B+ 树写入局部性。"

**展开三个理由：**

1. **`AUTO-INC` 锁竞争**：InnoDB 的自增主键在插入时需要申请自增锁。在 `innodb_autoinc_lock_mode = 1`（连续）模式下，批量插入会持有表级自增锁到语句结束。秒杀场景下大量并发插入会在这个锁上排队。**雪花 ID 完全不需要申请，由应用生成。**

2. **分库分表下的全局唯一**：自增在单库内唯一，但拆到多个库后，分库 0 和分库 1 都会产生 `id = 1`。雪花 ID 由 `(时间戳 + 机器号 + 序列号)` 组成，**天然全局唯一**，可以直接做分片键（`id % 16`）。

3. **趋势递增 + B+ 树局部性**：雪花 ID 的高位是时间戳，**大致随时间递增**（同一毫秒内会有多个值）。这保证了插入基本发生在 B+ 树的最右页，**避免了随机主键（比如 UUID）造成的页分裂和随机 I/O**。
   *注意：这是雪花 ID 相比 UUID 的关键优势。如果只说"雪花 ID 唯一"，那 UUID 也唯一——但 UUID 做主键会导致严重的写放大。*

**权衡（我会主动说）：**

| 维度 | 雪花 ID | 自增 ID | UUID |
| --- | --- | --- | --- |
| 全局唯一 | ✅ | ❌（分库后） | ✅ |
| 写入局部性 | ✅ 趋势递增 | ✅ 严格递增 | ❌ 随机 |
| 索引空间 | 8 字节 BIGINT | 8 字节 | **16 字节**（或 36 字符） |
| 生成开销 | 有（应用内） | 无（DB 内） | 有 |
| 可读性 | 差（长数字） | 好 | 差 |
| 依赖 | **机器号配置 + 时钟** | 无 | 无 |
| 安全性 | 递增可被遍历估算业务量 | **递增，易被遍历** | 不可遍历 |

**所以"订单号"和"订单 ID"在本项目里是**两个不同的字段**：
```java
order.setId(IdWorker.getId());                    // 雪花 ID，主键，内部用
order.setOrderNo(msg.getOrderNo());               // "SO" + 雪花 ID，对外暴露
```
```java
private String generateOrderNo() {
    return "SO" + IdWorker.getIdStr();
}
```
**为什么不把外部订单号做成 UUID（不可遍历）？** `SeckillService` 的注释解释了：
> "用雪花 ID 而不是『时间戳 + 随机数』：**随机数在极高并发下存在碰撞概率**，而 `uk_order_no` 一旦命中冲突，会在消费端表现为『订单唯一键冲突』，被误判成重复抢购，非常难排查。雪花 ID 由 (时间戳 + 机器号 + 序列号) 组成，单机内严格递增且全局唯一。"

**这是一个很精彩的取舍：** 随机数看起来更"安全"（不可遍历），但**在极高并发下会产生碰撞**，而碰撞的后果（被误判为重复抢购）比"可遍历"严重得多。而且订单查询本身有**越权校验**（`queryVO` 会校验 `userId` 归属），所以"可遍历"的实际风险可控。

**追问：** 雪花 ID 的时钟回拨怎么处理？
**答：** 这是雪花 ID 的真实风险。MyBatis-Plus 的 `IdWorker` 在检测到时钟回拨时会**抛异常**（而不是生成可能重复的 ID）——这是一个合理的默认行为（fail-fast）。
**但在生产环境这可能变成可用性问题**：NTP 校时导致的少量回拨（几十毫秒）会让所有 ID 生成失败。
**缓解手段：** ① 用 NTP 的 `-x`（slew）模式做平滑校时而不是跳跃校时；② 配置足够的 `workerId` 唯一性（每台机器/每个 Pod 不同）；③ 如果对可用性要求极高，可以改用**号段模式**（DB 或 Redis 分配号段，如美团 Leaf），它没有时钟依赖，代价是需要一次远程调用获取号段（但可以一次拿 1000 个，摊薄开销）。
**本项目用雪花 ID**，因为秒杀场景下 ID 生成是热路径的一部分，**不能引入额外的远程调用**。

> 💡 **面试官意图：** 经典题，但"随机数碰撞会被误判为重复抢购"这个细节能体现真实经验；"趋势递增 vs UUID 的写放大"能体现 MySQL 深度。

---

### Q70. 订单状态流转为什么一定要"条件更新"？

**结论：** 因为"先 SELECT 判断状态，再 UPDATE"两步之间存在窗口，支付回调和超时关单可能**同时通过判断**，导致一个订单既被置为已支付又被置为已取消，**库存还会被错误回补**。

`OrderMapper` 的注释：
> "「先 SELECT 判断状态，再 UPDATE」两步之间存在窗口，支付回调与超时关单可能同时通过判断，一个订单既被置为已支付又被置为已取消；带条件的一条 UPDATE 把判断压进 InnoDB 行锁里，**两个并发事务只有一个能改到行，天然互斥**。"

```sql
-- 支付
UPDATE t_order SET status = 1, pay_time = #{payTime} WHERE order_no = #{orderNo} AND status = 0
-- 取消
UPDATE t_order SET status = 2, cancel_time = #{cancelTime} WHERE order_no = #{orderNo} AND status = 0
-- 完成
UPDATE t_order SET status = 3 WHERE order_no = #{orderNo} AND status = 1
```

**为什么"库存回补必须挂在返回 1 的分支上"？**
`OrderService` 的注释：
> "只有 `cancelOrder` 返回 1（即本次调用真正把状态从 0 改成 2）才回补库存。这样『超时关单』『用户主动取消』『兜底扫描』**三条路径并发触发时，也只会回补一次**——幂等性由数据库的状态机保证，而不是靠应用层加锁。"

```java
int rows = orderMapper.cancelOrder(orderNo, LocalDateTime.now(clock));
if (rows == 0) {
    // 订单已经支付或已经被取消，本次无事可做。
    // 这是并发场景下的正常分支，不是错误。
    log.debug("订单状态已变更，跳过取消 orderNo={} currentStatus={}", orderNo, order.getStatus());
    return CancelResult.notCanceled(orderNo);
}
```
**注意 `rows == 0` 的日志级别是 `debug` 而不是 `error`** —— 因为这是**并发下的正常分支**，不是错误。**日志级别的选择反映了对"什么是异常"的判断**。如果打成 ERROR，在秒杀后的关单高峰会刷出大量假告警。

**三条并发路径：**
1. `OrderTimeoutConsumer`（延迟队列，30 秒后触发）；
2. `OrderTimeoutFallbackJob`（兜底扫描，60 秒一次，扫 `status = 0 AND expire_time <= now`）；
3. 用户主动取消（如果有这个接口）。

**三条路径都调 `cancelIfUnpaid`，只有第一个能拿到 `rows == 1`。** 不需要任何锁、不需要"检查是否已经取消"的额外查询——**一个带条件的 UPDATE 同时解决了"判断"和"执行"。**

**追问：** 如果我想加一个"支付超时后再支付"的容错（比如用户卡在支付页），怎么做？
**答：** **不应该这么做**——"超时关单"这个规则的意义就是"30 秒后订单失效，库存还给别人"。如果允许超时后支付，就可能出现"库存已经给别人了但这个订单也支付成功"→ **超卖**。
如果业务上确实需要延长支付时间，正确的做法是：① 延长 `order-expire`（配置文件里改，30s → 5min）；② 或者提供"重新下单"入口，让用户重新抢（此时会走完整的 Lua + 唯一索引流程）。
**"改配置"和"改语义"是两件事**——把 `expire_time` 从 30 秒改成 5 分钟是配置调整；允许超时订单支付是破坏状态机的语义。**前者随时可以做，后者绝不可以。**

> 💡 **面试官意图：** 条件更新 + "库存回补挂在成功分支上"是交易系统的核心模式。能说出"日志级别应该是 debug"是细节加分项。

---

### Q71. `t_order` 的 `idx_status_expire(status, expire_time)` 索引是为哪个查询建的？

**结论：** 为 `OrderTimeoutFallbackJob` 的兜底扫描建的。

```java
// OrderService.findExpiredUnpaid
return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
        .eq(Order::getStatus, com.lxt.mall.domain.enums.OrderStatus.CREATED)  // status = 0
        .le(Order::getExpireTime, LocalDateTime.now(clock))                    // expire_time <= now
        .last("LIMIT " + limit));
```
对应 SQL：`WHERE status = 0 AND expire_time <= ? ORDER BY ... LIMIT 200`

`OrderTimeoutFallbackJob` 的注释：
> "扫描条件是 `status = 0 AND expire_time <= now`，而 `idx_status_expire` 索引就是为这个查询建的。"

**索引为什么是 `(status, expire_time)` 这个顺序？**
- **`status` 在前**：`status = 0` 是等值条件，把范围缩到"待支付订单"（通常是全部订单的一小部分）；
- **`expire_time` 在后**：在等值前缀之后，可以高效地做范围扫描 `<= now`。
- **反过来 `(expire_time, status)` 会差一些**：`expire_time <= now` 这个范围条件会扫到所有历史订单（不仅仅是待支付的），然后回表过滤 `status`。

**这就是"联合索引列顺序"的经典判断：等值条件在前，范围条件在后。**

**其他两个索引的用途：**

| 索引 | 服务的查询 |
| --- | --- |
| `idx_user_create(user_id, create_time)` | `OrderService.listByUser`：`WHERE user_id = ? ORDER BY id DESC`（列表分页） |
| `idx_activity(activity_id)` | 对账/统计：按活动查订单 |
| `uk_order_no` | `findByOrderNo`、`markPaid`、`cancelOrder` 的 `WHERE order_no = ?` |
| `uk_user_activity` | 一人一单唯一性 + 按用户查活动订单 |
| `uk_message_id` | 消息幂等 + 按 messageId 反查订单 |

**注意 `listByUser` 用的是 `ORDER BY id DESC` 而不是 `ORDER BY create_time DESC`：**
```java
IPage<Order> result = orderMapper.selectPage(page, Wrappers.<Order>lambdaQuery()
        .eq(Order::getUserId, userId)
        .orderByDesc(Order::getId));   // ← 按主键倒序，不是 create_time
```
**为什么？** 因为 `id` 是雪花 ID（趋势递增），**按 `id DESC` 排序的结果和 `create_time DESC` 几乎一致**，但**主键索引是聚簇索引，不需要回表**。

如果按 `create_time DESC` 排：索引 `idx_user_create(user_id, create_time)` 能提供有序性，但**如果需要额外字段（`SELECT *`）就要回表**，MySQL 优化器可能选择"先按索引排序再回表"或"filesort"。**按主键排序能让优化器明确地走聚簇索引的逆序扫描。**

**注意：`WHERE user_id = ? ORDER BY id DESC` 这个组合用不上 `idx_user_create` 的有序性**（因为索引的第二列是 `create_time` 而不是 `id`），实际上会是"用 `idx_user_create` 定位 user_id 的所有行，然后 filesort"。
**这是一个真实的（小）设计不一致**，我会在面试里主动说：
> "正确做法应该是 `idx_user_create(user_id, id)`，这样 `WHERE user_id = ? ORDER BY id DESC` 就可以完全靠索引完成（索引天然有序），不需要 filesort。当前用 `create_time` 的原因是我最初想的是时间排序，后来改成了主键排序但没有同步调整索引。**这个不一致在小数据量下没有影响（每个用户的订单通常只有几十条），但订单量大了之后会产生 filesort，需要修正。**"

**主动承认自己代码里的不一致，并给出修正方案，比硬说"我的索引设计是对的"要好得多。**

**追问：** 分页用 `LIMIT offset, size` 有什么问题？
**答：** 深分页时 `offset` 越大越慢（MySQL 要扫描并丢弃前 offset 行）。**优化方案是"游标分页"**：`WHERE user_id = ? AND id < #{lastId} ORDER BY id DESC LIMIT 20`，用上一页的最后一条记录的 ID 作为游标。
**本项目用的是 `MyBatis-Plus` 的 `Page`（offset 分页）**，因为用户的订单列表通常只看前几页，深分页的实际影响有限。**但如果要做"全部订单导出"或者"运营后台的全量列表"，就必须改成游标分页或者异步导出。** 这是一个"知道问题但当前场景不需要解决"的判断。

> 💡 **面试官意图：** 索引列顺序是必考题。能主动指出自己索引设计的不一致，是加很多分的诚实。

---

### Q72. `t_stock_flow` 的 `uk_order_change(order_no, change_type)` 解决什么问题？

**结论：** 保证同一订单的"扣减"和"回补"**各只能落一次**——杜绝超时关单与死信回补**重复回补库存**。

```sql
UNIQUE KEY uk_order_change (order_no, change_type)
```
`change_type`：`1 = 扣减（DEDUCT）`，`2 = 回补（ROLLBACK）`。

`01-schema.sql` 的注释：
> "`uk_order_change(order_no, change_type)` 是『同一订单同一类变更只能落一次』的硬约束。**超时关单回补与死信回补同时发生时，只有一个能成功。**"

**具体的触发路径：** 一个秒杀订单创建失败（进死信），同时它的 30 秒超时也到了（延迟队列触发关单）：
```
路径 A（死信消费者）: 发现订单不存在 → compensate → rollback Redis
路径 B（超时关单）:    cancelIfUnpaid → 订单不存在 → notCanceled → 什么都不做
```
或者反过来，订单已经创建成功，但又进了死信：
```
路径 A（死信消费者）: 发现订单已存在 → 不回补 ✅
路径 B（超时关单）:   条件更新 status 0→2 成功 → 回补库存（这是正确的）
```

**在 `OrderService.cancelIfUnpaid` 里的捕获处理：**
```java
try {
    StockFlow flow = new StockFlow();
    ... flow.setChangeType(StockChangeType.ROLLBACK); ...
    stockFlowMapper.insert(flow);
} catch (DuplicateKeyException e) {
    log.warn("库存回补流水已存在，跳过写入 orderNo={}", orderNo);
}
```
注释说明了为什么必须显式捕获：
> "库存流水：`uk_order_change` 保证同一订单的回补只落一次。如果真的撞了唯一键，说明前面有并发路径已经回补过，这里**必须显式捕获并继续**——抛出会导致整个取消事务回滚，让一个**已经完成的取消操作前功尽弃**。"

**这里有一个非常精妙的连锁分析：**

如果这里不捕获、让 `DuplicateKeyException` 抛出：
1. `@Transactional` 方法抛出异常 → **事务回滚**；
2. 回滚的内容包括 `orderMapper.cancelOrder(orderNo, ...)` —— **订单又变回"待支付"状态**！
3. 但 Redis 库存**已经在事务外被回补了**（在 `cancelIfUnpaid` 返回后由调用方执行？不，这里还没返回）……

**等一下，需要仔细看顺序。** `cancelIfUnpaid` 的完整顺序是：
```java
@Transactional
public CancelResult cancelIfUnpaid(String orderNo, String remark) {
    Order order = findByOrderNo(orderNo);              // 1. 查
    int rows = orderMapper.cancelOrder(orderNo, ...);  // 2. 条件更新 status 0→2
    if (rows == 0) return notCanceled(orderNo);
    int quantity = ...;
    int restored = activityMapper.rollbackStock(...);  // 3. DB 库存回补
    ...
    try { stockFlowMapper.insert(flow); }               // 4. 流水（可能撞唯一键）
    catch (DuplicateKeyException e) { log.warn(...); }  // 5. 捕获并继续 ✅
    return new CancelResult(true, ...);                 // 6. 返回
}
// 调用方（OrderTimeoutConsumer）在事务提交后执行 Redis 回补
```
**如果第 4 步抛出且不捕获：** 事务回滚 → 第 2 步的 `status = 2` 被回滚 → 第 3 步的 `rollbackStock` 被回滚 → **但 Redis 侧还没有任何操作**（因为 Redis 回补在 `CancelResult` 返回之后）。

**实际后果：** 订单仍然是"待支付"，DB 库存没有回补。**没有立即的数据损坏**，但：
- `OrderTimeoutConsumer` 会因为异常走 `catch` → `basicNack(requeue=false)` → 消息被丢弃；
- 兜底扫描下次会再试一次 —— 但**它会再次撞唯一键**（因为流水记录在另一个事务里已经提交了？不，如果这次事务回滚了，流水也没提交）……

**这个分析链太长了，而这恰恰是重点：** 一个"看起来只是记日志"的 catch，实际上决定了整个事务的成败和状态机的走向。**这种"局部代码选择影响全局一致性"的地方，是最需要仔细推理的地方。**

**正确的结论是：** 第 4 步撞唯一键意味着"**有并发路径已经写过回补流水了**"。这种情况下：
- 那个并发路径**一定也做了状态更新和库存回补**（因为流水是最后写的）；
- 所以本路径**什么都不做是最安全的**——但当前路径已经把 `status` 改成 2 了吗？如果并发路径也改了，那 `cancelOrder` 在本路径会返回 0，根本走不到第 4 步。
- **所以撞唯一键只能是"同一个事务内重复插入"或者极端的时序**。捕获并继续是正确的防御。

**我会这样总结：** "这个 catch 的本质是'流水表是审计记录，不是事务的成败条件'。审计记录写不进去（因为已经有了），不应该让业务操作回滚。**区分'业务必要数据'和'审计数据'，并给它们不同的事务地位，是这里的关键判断。**"

**追问：** 那为什么 `stock_flow` 的插入不放在事务外？
**答：** 因为它和"DB 库存回补"必须一致——**如果库存回补了但没有流水，就失去了可追溯性**，对账时无法解释库存为什么变了。`t_stock_flow` 是"可追溯"的核心（表注释："库存流水：可追溯 + 防重复回补"）。
**"审计数据和业务数据在同一个事务里，但对审计数据的冲突要容忍"** —— 这是一个很细致的平衡。

> 💡 **面试官意图：** 这个题非常深。能分析到"为什么必须捕获而不能抛出"就说明你理解了事务边界和审计数据的关系。

---

### Q73. MySQL 的事务隔离级别是什么？`deductStock` 会有幻读问题吗？

**结论：** InnoDB 默认 **RR（可重复读）**，`deductStock` **不存在幻读问题**，因为它锁的是单行、不是范围。

```sql
UPDATE t_seckill_activity
   SET available_stock = available_stock - #{num}
 WHERE id = #{activityId}
   AND available_stock >= #{num}
```

**为什么不会有幻读：**
- **幻读的定义**是"同一个事务内两次范围查询返回了不同的行集"（比如 `WHERE age > 10` 第一次返回 3 行，第二次返回 4 行）。
- `deductStock` 的 `WHERE` 是 `id = ?`（**主键等值查询**）→ 只锁定这一行（加行级排他锁 `X` 锁），**不存在"范围"**，所以不涉及幻读。
- 而且这是**当前读（current read）**：`UPDATE` 语句会读取最新已提交的数据（不是 MVCC 快照），并对匹配的行加锁。所以并发的 `UPDATE` 会串行执行。

**并发下的实际行为：**
```
T1: UPDATE ... WHERE id=2001 AND available_stock >= 1   → 拿到 id=2001 的行锁，available_stock 100 → 99
T2: UPDATE ... WHERE id=2001 AND available_stock >= 1   → 等待 T1 释放行锁
T1: COMMIT
T2: 拿到锁，重新读取 available_stock = 99 → 99 >= 1 成立 → 99 → 98
```
**关键：T2 在拿到锁之后会重新读取最新值**（这就是"当前读"），所以不会基于陈旧的快照做判断。**这是 `UPDATE ... WHERE` 模式能正确工作的根本原因。**

**本项目里真正处理"并发插入"的地方（这才是 RR 下有讲究的场景）：**
- **`t_order` 的 `uk_user_activity`**：两个消费者同时 `INSERT` 同一个 `(user_id, activity_id)`。
  - **RR + 唯一索引** 下，第二个 `INSERT` 会**阻塞**等待第一个事务提交，然后返回 `DuplicateKeyException`。这是正确行为（而不是"两个都成功"）。
  - **注意：如果是 RC（读已提交）**，行为是一样的——唯一索引的冲突检测和隔离级别无关。
- **`t_mq_message` 的 `uk_message_id`**：同上。

**MySQL RR 的间隙锁（gap lock）在这个项目里的影响：**
- `UPDATE ... WHERE id = ?`（主键等值）只加行锁，**不加间隙锁**（因为是唯一索引的等值匹配，确定只有一行）。
- 如果 `deductStock` 的 `WHERE` 是范围条件（比如 `WHERE available_stock > 0 LIMIT 1`），RR 下就会加间隙锁，**容易产生死锁**。**这也从侧面说明了"用主键等值条件 + 条件更新"是更安全的设计。**

**追问：** 那什么情况下项目里会出现死锁？
**答：** 一个理论场景：`OrderCreationService` 的一个事务里，先更新 `t_seckill_activity`（拿活动行锁），再 `INSERT t_order`（拿唯一索引锁）。如果两个事务操作的是**相同的活动和相同的用户**，顺序一致所以不会死锁（都是先活动后订单）。但如果将来加了"多商品下单"（一个事务里锁多个活动行），就必须**约定加锁顺序**（比如按 activityId 升序），否则会死锁。
**更隐蔽的一个：** `rollbackStock` 和 `deductStock` 操作同一行，如果两个事务分别持有该行锁又去申请对方的锁，就会死锁。**本项目通过固定的 SQL 执行顺序规避了这个问题。**
**检测到死锁时，MySQL 会回滚其中一个事务并报 `DeadlockLoserDataAccessException`** —— 这种异常**是可重试的**（重试通常能成功）。**所以消费端应该把死锁判定为"瞬时故障"走延迟重试队列，而不是"不可重试"进死信。** 当前代码里死锁会落到 `catch (Exception e)` 分支走重试——**这是正确的行为，但我没有显式识别 `DeadlockLoserDataAccessException`，可以改进为"显式识别并优先重试"。**

> 💡 **面试官意图：** 隔离级别是必考题。但"主键等值更新不加间隙锁所以不会死锁"这个分析是加分项。

---

### Q74. 金额为什么用 `DECIMAL`？`version` 字段有什么用？

**结论：** `DECIMAL` 是因为 `FLOAT/DOUBLE` **无法精确表示十进制小数**；`version` 字段是**预留的乐观锁**，但本项目**实际上没有用它**做任何判断。

**金额：**
```sql
price          DECIMAL(10, 2)  NOT NULL COMMENT '原价',
seckill_price  DECIMAL(10, 2)  NOT NULL COMMENT '秒杀价',
pay_amount     DECIMAL(10, 2)  NOT NULL COMMENT '应付金额',
```
`01-schema.sql` 设计说明第 1 条："金额一律 `DECIMAL`，绝不用 `FLOAT/DOUBLE`。"

**为什么：** `FLOAT`/`DOUBLE` 是 IEEE 754 二进制浮点，**无法精确表示 0.1 这类十进制小数**。经典的例子：
```java
System.out.println(0.1 + 0.2);          // 0.30000000000000004
System.out.println(1.0 - 0.9);          // 0.09999999999999998
```
**在交易系统里这会累积成对账误差。** 一万笔订单各差 0.0000001 元，对账时就找不平。
`DECIMAL(10, 2)` 精确到分，`10` 是总位数（最大值 99999999.99），`2` 是小数位。

**Java 侧对应 `BigDecimal`：**
```java
BigDecimal price = msg.getSeckillPrice();
order.setPayAmount(price.multiply(BigDecimal.valueOf(quantity)));
```
注意用 `BigDecimal.valueOf(long)` 而不是 `new BigDecimal(double)` —— **`new BigDecimal(0.1)` 会得到一个包含 0.1 的二进制精确表示（0.1000000000000000055511151231257827021181583404541015625）**，而 `BigDecimal.valueOf(0.1)` 走的是 `Double.toString(0.1)` → `"0.1"` → 正确的十进制。
**这个区别在数量乘法时会被放大**。

**`version` 字段：**
```sql
version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
```
出现在 `t_product` 和 `t_seckill_activity` 上。MyBatis-Plus 支持 `@Version` 注解自动做乐观锁：
```sql
UPDATE t_seckill_activity SET available_stock = ?, version = version + 1 WHERE id = ? AND version = ?
```

**但本项目没有用 MyBatis-Plus 的乐观锁**，因为：
1. **库存扣减用的是"条件更新"而不是"版本号"**：`WHERE available_stock >= num` 直接表达了业务条件，比 `WHERE version = ?` 更直接（版本号冲突时你不知道是"库存不够"还是"并发改动"）。
2. **加上 `version` 会让 SQL 更长、更容易出错**，而且**和 `available_stock` 构成了两套并发的表达方式**（冗余）。
3. **业务上"库存不够"是一个正常结果，而"版本冲突"是一个需要重试的技术错误**。用 `available_stock >= num` 能一次区分这两者；用 `version` 则需要重试后才知道。

**所以 `version` 字段在本项目里实际上是"预留但未使用"的。** 我会在面试里主动说：
> "`version` 字段是建表时预留的，`t_product` 上将来做"商品编辑"时可以用它做乐观锁（防止两个管理员同时改商品覆盖对方的修改）；`t_seckill_activity` 上基本用不到，因为库存扣减走的是条件更新。**"预留一个不用的字段"这件事本身值得反思**——更好的做法是等真正需要时再 `ALTER TABLE` 加字段（MySQL 8 加列是 instant DDL，很快）。但如果团队规范是"所有业务表都带 version/create_time/update_time"，那统一性也有价值。"

**追问：`t_product` 的乐观锁为什么有用？**
**答：** 商品编辑是典型的"读-改-写"场景：管理员 A 打开商品页（读），管理员 B 也打开（读），A 改了标题保存，B 改了价格保存——**如果没有乐观锁，B 的保存会覆盖掉 A 的标题修改**。
用 `version` 就能检测到："B 提交时 `WHERE version = B读到的版本` 不成立（因为 A 已经加了 1）" → 返回 0 行 → 提示"商品已被他人修改，请刷新重试"。
**注意这和库存扣减的区别：** 商品编辑是"**整体覆盖**"语义（后写覆盖先写是错的），所以需要乐观锁；库存扣减是"**增量操作**"语义（`available_stock = available_stock - 1` 不会覆盖），所以条件更新就够了。**"整体覆盖 vs 增量操作"是选择乐观锁的判断标准。**

**追问：** 那 `version` 字段既然没用，为什么不删掉？
**答：** 三个考虑：
1. **`t_product` 上它有用**（Q74 后面提到的商品编辑乐观锁场景），不是纯冗余；
2. **删列的收益极低**（`INT NOT NULL DEFAULT 0` 占 4 字节，几百万行也就几 MB），而**`ALTER TABLE DROP COLUMN` 在生产大表上是有风险的操作**（MySQL 8 的 instant DDL 可以快速 drop，但如果有从库延迟、有依赖它的代码就会出问题）；
3. **保留它有一个"防御性"价值**：将来做商品编辑、活动配置修改时**不需要再改表结构**。

**但我会承认它的真正问题不在字段本身，而在于"我没有显式声明它是预留的"** —— 代码和文档里都没有说明，后来的人看到 `version` 会以为"这里在用乐观锁"，然后去找一个不存在的 `@Version` 注解。**这比删掉它更容易造成误解。**
**正确的做法是：在 `SeckillActivity` 实体的 `version` 字段上加注释说明"预留，当前库存扣减用条件更新，不使用乐观锁"** —— **"预留字段必须标注，否则它就是噪音。"**

> 💡 **面试官意图：** `DECIMAL` 是送分题。`version` 字段"预留但未用"能展示诚实，而"整体覆盖 vs 增量操作"是真正的高级判断。

---

### Q75. 为什么"删除"都做成状态位，而且不启用 MyBatis-Plus 的逻辑删除？

**结论：** 因为本项目的"删除"都是业务状态（订单取消、活动结束），**没有物理删除语义**；而启用 MP 的逻辑删除会**静默往 `WHERE` 里注入条件**，容易和手写 SQL、唯一索引产生冲突。

`application.yml` 的注释：
```yaml
global-config:
  db-config:
    id-type: assign_id          # 雪花 ID
    # 本项目的"删除"都是状态位（订单取消、活动结束），没有物理删除语义，
    # 因此刻意不启用 MP 的逻辑删除，避免 WHERE 里被静默注入条件。
```

**"静默注入条件"的具体危险：**

如果启用了逻辑删除（`@TableLogic`），MP 会自动给所有查询加 `WHERE deleted = 0`。这会带来三个问题：

1. **和你手写的 SQL 打架。**
   `SeckillActivityMapper.deductStock` 是 `@Update` 注解写的手写 SQL，**MP 的逻辑删除不会自动加到这种手写 SQL 上**（它的注入是在 MP 的 `BaseMapper` 方法里做的）。结果是：MP 生成的查询会过滤已删除数据，手写的不会——**同一张表两套语义**。

2. **唯一索引会被逻辑删除的行占据。**
   假设订单表有 `deleted` 字段和 `uk_user_activity(user_id, activity_id)`。用户 A 取消了一次订单（`deleted = 1`），然后想重新抢——**新订单插入时会撞上那条"已删除"的订单的唯一键！**
   这是逻辑删除最经典的坑。绕过它需要把 `deleted` 加进唯一索引（`uk_user_activity(user_id, activity_id, deleted)`），但那样又限制了"同一用户只能有一条已删除订单"。

3. **索引效率下降。** 每个查询都多一个 `deleted = 0` 条件，需要额外的索引或过滤。

**本项目的做法：用 `status` 表达状态，用 `WHERE` 条件精确控制语义。**

| 表 | 状态表达 | 语义 |
| --- | --- | --- |
| `t_order` | `status`：0待支付 1已支付 2已取消 3已完成 | 取消 = `status = 2`，**数据保留**（对账、客服、统计都需要） |
| `t_seckill_activity` | `status`：0未开始 1进行中 2已结束 | 结束 = `status = 2` |
| `t_product` | `status`：1上架 0下架 | 下架 = `status = 0` |
| `t_user` | `status`：1正常 0禁用 | 禁用 = `status = 0` |

**关键区别：** `deleted` 是一个**横切所有业务的状态**（"这条记录存在吗"），而 `status` 是**业务自己的状态机**（"这个业务对象处于什么阶段"）。
- `deleted` 的问题是它会被**自动注入到所有查询**，你无法针对不同场景说"我要看已取消的订单"。
- `status` 由业务代码显式使用（`WHERE status = 0`），**想查什么就查什么**。

**取舍：** 不用逻辑删除意味着**要自己记得在每个查询里加 `status` 条件**。这是一个真实的负担。本项目的做法是**把状态条件收敛在少量地方**：
- 订单查询：`queryVO` / `listByUser`（用户视角只关心自己的订单，不需要过滤状态）；
- 超时扫描：`findExpiredUnpaid`（显式 `status = 0`）；
- 条件更新：`markPaid` / `cancelOrder` / `finishOrder`（`WHERE status = 前驱`）。

**"数据永不物理删除"的另一个好处是审计能力**：`t_order` 里保留所有订单（包括取消的），`t_stock_flow` 里保留所有库存变更——**出问题时能完整回溯**。而如果做了物理删除，历史就没了。

**追问：** 那什么数据会真的物理删除？
**答：** 本项目里**没有**（所有表都是"只增不减"或者在状态位之间流转）。生产环境需要物理删除的场景通常是：
- **数据量特别大的流水表**（比如日志、埋点），需要定期归档+删除；
- **合规要求**（GDPR 的"被遗忘权"，用户注销时要删除个人信息）；
- **临时数据**（比如验证码、会话）。

**处理方式应该是"归档到冷存储后删除热数据"，而不是逻辑删除**——逻辑删除只是把数据留在原地，占着空间和索引，**并没有真正解决问题**。

> 💡 **面试官意图：** 逻辑删除是"看起来对但有很多坑"的典型。能说出"唯一索引被已删除行占据"这个坑就到位了。

---

### Q76. `DATETIME(3)` 的毫秒精度为什么重要？`expire_time` 为什么存字段而不是计算？

**结论：** 毫秒精度是为了**超时判断和状态竞争**的准确性；`expire_time` 存字段是为了**保留创建时的约定**并且**能直接建索引扫描**。

`01-schema.sql` 设计说明第 2 条："时间统一 `DATETIME(3)`（毫秒精度），**超时关单 / 状态竞争判断依赖它**。"

**为什么需要毫秒：**
1. **秒杀场景的时间判断**：`if (now.isBefore(snapshot.getStartTime()))` / `if (now.isAfter(snapshot.getEndTime()))`。秒杀活动可能是 10:00:00.000 开始，如果时间只有秒精度，10:00:00.999 的请求和 10:00:00.000 的请求无法区分。
2. **`expire_time` 的比较**：`WHERE expire_time <= now`。两个订单在同一秒内创建，毫秒精度让它们的超时时间不同（虽然是 30 秒后，但毫秒的差异让"同时超时"的批次更小）。**这减少了兜底扫描一次性捞出大量订单的情况。**
3. **问题排查**：`create_time` / `update_time` 有毫秒精度才能看出"这两个操作是不是同时发生的"。秒级精度下，两个相差 900ms 的操作看起来是"同一时刻"。

**代价：** `DATETIME(3)` 比 `DATETIME` 多占 1~2 字节（MySQL 5.6.4+ 的 `DATETIME` 是 5 字节，`DATETIME(3)` 是 6 字节）。**完全可忽略。**

**为什么 `expire_time` 存字段而不是用 `create_time + 30s` 计算？**

三个理由：
1. **超时规则可能变化。** 如果今天是 30 秒，明天改成 5 分钟，**已经创建的订单必须保留创建时的约定**（否则那些"还有 4 分半才该过期"的订单会被立即关掉）。用字段存储就天然解决了这个问题。
2. **能建索引。** `idx_status_expire(status, expire_time)` 才能支持 `WHERE status = 0 AND expire_time <= now`。如果超时时间是算出来的，索引就没法用（**函数索引可以，但不能用 `NOW()` 这种非确定性函数**）。
3. **可观测。** 运维可以直接 `SELECT order_no, create_time, expire_time FROM t_order WHERE ...` 看到"这一单什么时候该过期"，不需要脑补 30 秒。

**同样道理的还有 `OrderCreationService` 的：**
```java
order.setExpireTime(java.time.LocalDateTime.now(clock).plus(properties.getSeckill().getOrderExpire()));
```
注意用的是 `LocalDateTime.now(clock)` 而**不是** `new Date()` —— 注入的 `Clock` 让测试可以控制时间（`RedisConfig` 里 `@Bean Clock clock() { return Clock.systemDefaultZone(); }`，注释说明："把『取当前时间』变成依赖注入，测试里就能换成固定时钟，从而对『活动未开始 / 已结束 / 订单超时』这类时间相关分支做**确定性验证**，**不必靠 `Thread.sleep` 去赌时序**。"）

**这是一个非常值得强调的工程实践：** `OrderTimeoutTest` 可以注入一个"时间已经过去了 31 秒"的固定 `Clock`，然后断言"订单被正确关闭"——**完全不需要真的等 30 秒**。测试的运行时间从 30 秒降到毫秒级。

**追问：** 那 `create_time` 上的默认值 `CURRENT_TIMESTAMP(3)` 和 Java 侧的 `now(clock)` 会不会冲突？
**答：** 这是一个需要注意的点。`t_order.create_time` 有 `DEFAULT CURRENT_TIMESTAMP(3)`，但 `Order` 实体如果被 MP 显式赋值，就用 Java 的值；如果没赋值，就用 DB 的默认值。
**本项目是一个潜在的不一致点**：`Order` 对象在 `buildOrder` 里**没有设置 `createTime`**，所以会用 DB 的默认值（DB 的时间）；而 `expireTime` 用的是 `LocalDateTime.now(clock)`（应用的时间）。**如果应用服务器和 DB 服务器的时区/时间不一致，`expire_time` 和 `create_time` 就会有一致的偏移**——虽然 `docker-compose.yml` 里通过 `TZ: Asia/Shanghai` 和 `--default-time-zone=+08:00` 对齐了，JDBC URL 也带了 `serverTimezone=Asia/Shanghai`，但这个依赖是**隐式的**。
**更稳的做法是：显式设置 `createTime`（用同一个 `clock`）**，让所有时间都来自同一个时钟源。这是一个我会改进的点。

> 💡 **面试官意图：** `DATETIME(3)` + 注入 `Clock` 做可测试性是成熟度体现。主动指出"应用时间和 DB 时间两个时钟源"是加分的深度。

---

### Q77. `t_mq_message` 的三个索引分别服务什么查询？

**结论：**

```sql
UNIQUE KEY uk_message_id (message_id),
KEY idx_status_retry (status, retry_count, update_time),
KEY idx_biz (biz_type, biz_key)
```

| 索引 | 服务的查询 | 类型 |
| --- | --- | --- |
| `uk_message_id` | `alreadyConsumed(messageId)` / `insertConsumeRecord` 的唯一性校验 | 唯一索引（等值） |
| `idx_status_retry` | "找出所有失败且重试未耗尽的消息"（运维/对账） | 联合索引（等值 + 等值 + 范围） |
| `idx_biz` | "按业务键查这条消息的全部记录"（如某订单相关的消息） | 联合索引（等值 + 等值） |

**`idx_status_retry(status, retry_count, update_time)` 的列顺序分析：**
- `status = 2`（失败）→ 等值，放最前；
- `retry_count < maxRetry` → 范围（或可以在应用层过滤）；
- `update_time` → 用于排序"最早失败的先处理"（FIFO 重试）。

**这个顺序的意图是：** 先按 `status` 缩小到失败记录，再按 `retry_count` 和 `update_time` 排序取出最该重试的一批。**等值 → 等值 → 范围/排序 是联合索引的经典排序方式。**

**`idx_biz(biz_type, biz_key)` 的用途：** `biz_key` 的注释是"业务键，如 `orderNo` 或 `actId:userId`"。所以：
```sql
SELECT * FROM t_mq_message WHERE biz_type = 'SECKILL_ORDER' AND biz_key = 'SO123456...'
```
能查出"这个订单相关的所有消息记录"（可能是生产记录 + 消费记录 + 重试记录）。**这是排查"这个订单到底发生了什么"的第一入口。**

**取舍：索引不是越多越好。** 每多一个索引：
- 插入要维护多个 B+ 树（`t_mq_message` 是**高写入表**，每次消费都要 INSERT）；
- 占用更多磁盘；
- 优化器选择成本上升。

**这三个索引的数量是克制的**——只建了"支撑核心查询"的三个，没有建 `biz_type` 单列索引（被 `idx_biz` 的最左前缀覆盖）、没有建 `create_time` 索引（时间范围查询在这个表上不是核心场景）。

**一个可以改进的点（我会主动说）：** `t_mq_message` 会无限增长。每天几百万条消息，一个月就是上亿行。**需要归档策略**：
- 按 `create_time` 分区（MySQL 分区表）或者定期归档到历史表；
- 或者按 `status = 1 AND create_time < now - 7d` 定期删除（成功且超过 7 天的记录）。
**当前实现没有这个机制，是一个真实的生产隐患。** 我会把它列为"如果重做会加的东西"。

**追问：** 归档/删除 `t_mq_message` 时，怎么保证不会删掉"还需要用于幂等判断"的记录？
**答：** 这是一个**必须谨慎的问题**：`t_mq_message.uk_message_id` 是消费幂等的物理保证。如果删掉了一条记录，而对应的消息**又被重投**（比如 broker 里的消息由于某种原因重新投递），那么：
- `alreadyConsumed` 查不到 → `insertConsumeRecord` 成功 → **消息被当成新消息处理**；
- **但订单表的 `uk_order_no` / `uk_user_activity` 仍然会拦住重复下单**（Q66 的四层防御）→ 抛 `DuplicateOrderException` → 补偿 + ACK。
**所以"删掉幂等记录"的后果是"最多多一次无用的订单创建尝试 + 一次多余的补偿"，而不是重复订单。** 这是多层防御的价值。

**安全的归档边界是"消息已经不可能再被投递"**，判断依据：
1. **时间**：RabbitMQ 里消息的 TTL 和队列积压深度——如果所有队列都是空的且消息的 `create_time` 超过 7 天，**它不可能还在 MQ 里**；
2. **状态**：只归档 `status = 1`（成功）的记录。`status = 2`（失败/死信）的记录必须保留（需要人工处理）；
3. **保守做法**：**先归档到历史表而不是删除**（`t_mq_message_archive`），保留可查性，确认无问题后再清理历史表。

**"归档的边界是'业务上不可能再需要它'，而不是'它看起来已经过期了'"——这个判断需要结合 MQ 的消息生命周期，不能只看时间。**

> 💡 **面试官意图：** 索引数量的克制 + "这张表会无限增长"的自觉，是生产意识。

---

### Q78. `rewriteBatchedStatements=true` 在这个项目里有意义吗？

**结论：** 当前代码里**几乎没有批量插入**，所以这个参数的作用有限；但它是一个"为零成本留出空间"的配置。

```yaml
url: ...&rewriteBatchedStatements=true&useAffectedRows=true
```

**`rewriteBatchedStatements=true` 的作用：** 让 MySQL Connector/J 把 JDBC 的批量操作（`addBatch()` + `executeBatch()`）**重写成一条多值 `INSERT`**（`INSERT INTO t VALUES (1,2),(3,4),...`）或者多语句的 `UPDATE`。默认情况下，`executeBatch()` 只是循环发送单条语句，**网络往返次数等于批量大小**。

**性能提升量级：** 对于 1000 条批量插入，开启后可以快 **10 倍以上**（从 1000 次 RTT 变成 1 次）。

**本项目里哪里用得到：**
- `DataSeeder`（演示数据初始化）：插入 200 个用户——**如果它用了批量插入，这个参数就有用**。
- `ProductBloomFilter.run()`：从 DB 读全部商品 ID（是查询，不是批量插入）。
- **秒杀链路完全没有批量操作**（每次一单）。

**所以这个参数的当前价值是有限的。** 但它的成本是**零**（只是一个 URL 参数），而且：
1. **防止将来加了批量操作时才想起要加**（如果那时候已经上线，改 URL 需要重启）；
2. `DataSeeder` 类场景下**直接受益**。

**我会诚实地在面试里说：** "这个参数在我当前的代码路径里收益很小，因为项目里几乎没有批量写入。它更像是一个零成本的预防性配置。**如果我要展示对它的理解，我应该实际去优化 `DataSeeder` 用批量插入——那才是让这个参数真正产生价值的地方。** 这是我认为当前代码里'配置了但没充分利用'的一个例子。"

**另一个参数：`allowPublicKeyRetrieval=true`**
MySQL 8 默认用 `caching_sha2_password` 认证插件，首次连接需要从服务器获取公钥。**这个参数让客户端可以自动获取公钥**，避免"Public Key Retrieval is not allowed"错误。
**安全影响：** 它允许在**未加密连接**上获取公钥，理论上可以被中间人替换。**生产环境应该用 `useSSL=true` 配正确的证书，而不是依赖 `allowPublicKeyRetrieval`。** 当前配置是 `useSSL=false` + `allowPublicKeyRetrieval=true`，**这是本地开发配置，生产必须改**。

**这是一个很好的"主动坦白配置风险"的例子。** 面试官问"你的配置有什么问题"，答这个会显得很专业。

**追问：** `serverTimezone=Asia/Shanghai` 是必须的吗？
**答：** MySQL 8 的 `DATETIME` 类型本身**不存时区信息**，但 JDBC 驱动在与 `java.util.Date` / `java.sql.Timestamp` 互转时会用 `serverTimezone` 来解释。如果不配或者配错，会出现"时间差 8 小时"的经典问题。
**更根本的解法是**：① 用 `LocalDateTime` 而不是 `Date`（本项目用的就是 `LocalDateTime`，见 `OrderCreationService` 里的 `LocalDateTime.now(clock)`），**`LocalDateTime` 没有时区，不经过驱动的时区转换**；② 统一容器/DB/JVM 的时区为 `Asia/Shanghai`（`docker-compose.yml` 里已经做了：`TZ: Asia/Shanghai`、`--default-time-zone=+08:00`）。
**本项目两条都做了，所以时间问题是被"双重保险"覆盖的。** 但 `serverTimezone` 这个参数**在用了 `LocalDateTime` 之后其实是多余的**——它是为 `Date`/`Timestamp` 服务的。

> 💡 **面试官意图：** 考"你知不知道你配的每个参数在干什么"。主动指出"配了但没用上"和"生产必须改"是很好的信号。

---

## 八、限流与安全（12 题）

### Q79. 滑动窗口和令牌桶，你是怎么选的？

**结论：** 按"是否需要容忍突发"和"接口的性质"来选。

| 算法 | 特点 | 本项目用在哪 | 为什么 |
| --- | --- | --- | --- |
| **滑动窗口** | 严格限制"任意 1 秒内不超过 N 次"，无突发容忍 | **秒杀**（5/1s）、**商品详情**（50/1s） | 秒杀的瓶颈是明确的，必须严格控速；商品详情是高频读接口，严格限流防刷 |
| **令牌桶** | 桶容量允许瞬时突发（最多 `capacity` 个），之后按 `rate` 匀速补充 | **登录**（`capacity = 10`, `rate = 0.5/s`） | 用户可能连续重试几次；同时 BCrypt 很慢，必须限流 |

```yaml
rules:
  seckill:
    window: 1s
    limit: 5
    algorithm: SLIDING_WINDOW
  product-detail:
    window: 1s
    limit: 50
    algorithm: SLIDING_WINDOW
  login:
    window: 60s
    limit: 10
    algorithm: TOKEN_BUCKET
    capacity: 10
    rate: 0.5
```

**令牌桶的语义解读：** `capacity = 10`、`rate = 0.5/s` 意味着：
- 用户最多可以"连点 10 次"（桶满时的突发）；
- 桶空了之后，每 2 秒才补充 1 个令牌；
- 长期速率是 **0.5 次/秒**（每 2 秒 1 次）。

**这个组合对登录场景很合理：** 用户第一次登录失败会立刻重试（可能连试 3~5 次），令牌桶允许这个突发；但如果持续暴力破解，就会被 0.5/s 的速率限制住（**每小时最多 1800 次尝试**）。

**为什么秒杀不用令牌桶？** 因为秒杀需要的是**严格控速**：如果允许突发 10 次，那么"一个人在一瞬间打 10 个请求"就变成了合法行为——而这正是脚本党要做的事。**滑动窗口的 `limit = 5` 意味着"任何 1 秒窗口内都不能超过 5 次"，没有突发空间。**

**两种算法的实现（都在 Lua 里，见 `RedisRateLimiter`）：**
```java
public boolean tryAcquireSlidingWindow(String dimension, String target, int limit, Duration window) {
    String key = RedisKeys.rateLimit(dimension, target);
    long now = System.currentTimeMillis();
    // member 用 UUID 而不是固定值：ZSET 的 member 必须唯一，
    // 否则同一毫秒内的多个请求会被 ZADD 覆盖，计数严重偏低。
    String member = now + "-" + UUID.randomUUID();
    Long result = redis.execute(slidingWindowScript, List.of(key),
            String.valueOf(now), String.valueOf(window.toMillis()),
            String.valueOf(limit), member);
    return result != null && result >= 0;
}
```

**取舍：滑动窗口的内存代价。** 滑动窗口用 ZSET 存每个请求的时间戳，**内存占用和"窗口内的请求数"成正比**。`limit = 50/1s` 意味着每个 key 最多 50 个 member——单个 key 很小，但**如果按 IP 维度限流，key 数量 = IP 数量**，一个 DDoS 场景下可能产生几百万个 key。
- **缓解：ZSET 的 key 必须有 TTL**（`EXPIRE key window`），否则垃圾 key 会永远堆积；
- **令牌桶的内存是 O(1)**（只存"令牌数 + 上次补充时间"两个值）——这是令牌桶的另一个优势，**但本项目选择了在内存可控的前提下优先要严格语义**。

**追问：** 滑动窗口的 Lua 里 `member` 用 UUID 是必须的吗？
**答：** 是必须的。ZSET 的 member 必须唯一，**同一个 member 的重复 `ZADD` 只会更新 score，不会新增元素**。如果用固定值（比如 `userId`）或者只用时间戳，那么同一毫秒内的多个请求会被**合并成一个 member**——`ZCARD` 的计数会严重偏低，**限流形同虚设**。
用 `now + "-" + UUID.randomUUID()` 保证绝对唯一。代价是 member 字符串变长（约 50 字节），但这是**正确性的必要代价**。
**这是一个"看起来多余的 UUID 实际上是必需的"的典型例子**——去掉它代码能跑、测试也不一定红，但在生产的高并发下会静默失效。

> 💡 **面试官意图：** "为什么登录用令牌桶"是设计判断力的体现；"member 必须唯一"是实战型细节。

---

### Q80. 为什么限流的降级策略是 fail-open 而不是 fail-close？

**结论：** 因为限流是**保护措施**，不能因为保护措施挂了就把正常业务全拒绝掉——那会把一次**局部故障**放大成**全局事故**。

`RateLimitAspect` 的注释：
> "Redis 挂了的时候，限流判定会抛异常。此时如果直接拒绝所有请求，就等于『限流组件故障 → 全站不可用』，把一次局部故障放大成全局事故。默认策略是记一条 WARN 然后放行，把决定权交还给业务本身的容量（**DB 连接池、线程池都有各自的上限，不至于真的被打穿**）。需要强保护的接口可以通过配置把 `fail-open` 关掉。"

```java
} catch (RateLimitException e) {
    throw e;                          // 限流拒绝要正常抛出（这是业务结果，不是故障）
} catch (Exception e) {
    if (config.isFailOpen()) {
        log.warn("限流组件异常，降级放行 api={} rule={} cause={}", apiName, rule, e.getMessage());
        metrics.rateLimitDegraded(apiName);
        return joinPoint.proceed();   // 放行
    }
    log.error("限流组件异常，按 fail-close 策略拒绝 api={}", apiName, e);
    throw new RateLimitException("服务繁忙，请稍后重试");
}
```

**注意 `catch` 的顺序：** `RateLimitException` 先被捕获并**重新抛出**——因为它表达的是"用户被限流了"，这是一个**正常的业务结果**，不能被 `fail-open` 逻辑吞掉。
**如果没有这个单独的 catch，`RateLimitException` 会落到 `catch (Exception e)` 里被当成"组件故障"而放行——限流就完全失效了！** 这是一个非常容易犯的错误。

**关键配置：** `mall.rate-limit.fail-open: true`（`MallProperties.RateLimit.failOpen` 默认也是 `true`）。

**"决定权交还给业务自身的容量"是什么意思：**
- Tomcat：`max = 500` 线程 + `accept-count = 2000` 队列，超了会拒绝；
- Hikari：`maximum-pool-size = 50`，超了会抛 `SQLTransientConnectionException`；
- Redis 连接池：`max-active = 64`，超了会等待然后抛异常。

**这些都有明确的上限，所以"没有限流"不等于"没有保护"。** 只是保护点从"入口"退到了"资源层"。

**代价与风险：** `fail-open` 意味着**Redis 挂了之后，秒杀接口的限流失效**。此时几万 QPS 会全部进入 `SeckillService`：
1. 本地售罄标记（不依赖 Redis）仍然生效 → 挡掉售罄后的流量；
2. 活动快照 L1（不依赖 Redis）仍然生效 → 挡掉未开始/已结束的请求；
3. **真正到达 Lua 的请求会失败**（Redis 挂了 `execute` 抛异常）→ 返回 5xx。

**所以 Redis 挂了的情况下，`fail-open` 的实际影响是"用户拿到 5xx 而不是 429"**，而不是"系统被打穿"。**这是"限流降级"和"Redis 主链路降级"共同作用的结果。**

**监控：** `metrics.rateLimitDegraded(apiName)` 打 `mall.rate.limit.degraded` 计数器。`MallMetrics` 的注释："**这个值一旦 > 0 就必须告警。**"

**追问：** 那什么接口应该关掉 `fail-open`（即用 fail-close）？
**答：** 那些"**没有下游保护、或者被绕过后果严重**"的接口：
- **登录接口**：如果没有限流，暴力破解可以无限打（虽然有 BCrypt 的 50~100ms 成本，但攻击者可以用分布式 IP）。不过话说回来，**fail-close 会让"Redis 挂了 = 所有人无法登录"**，这也是很严重的。更好的方案是：登录的限流**加一层本地兜底**（比如 JVM 内的滑动窗口计数器，每个实例独立限流），这样 Redis 挂了还有基本保护。
- **短信/邮件发送接口**：这是**要花钱**的接口，被刷了就是直接资损。这类接口必须 fail-close（宁可暂时不可用，也不能被刷）。
- **秒杀接口**：本项目选择 `fail-open`，因为 Redis 挂了秒杀本来就不能工作（Lua 执行不了），限流限制不限制没差别。

**"fail-open 还是 fail-close"的判断标准是：'被绕过的后果'和'误拒绝的后果'哪个更严重。** 对于花钱的接口、可被利用做坏事的接口，要 fail-close；对于纯读取、有下游保护的接口，可以 fail-open。

> 💡 **面试官意图：** fail-open 是"降级思维"的经典体现。追问"什么接口该 fail-close"能测出判断的灵活性而不是死记一条规则。

---

### Q81. 限流为什么用 AOP 而不是在 Service 里手写？

**结论：** 因为限流是**典型的横切关注点**——它跟业务逻辑零耦合，却需要在十几个入口重复出现。

`RateLimitAspect` 的注释：
> "写进业务方法会带来三个问题——**业务代码被稀释、阈值改动要碰业务类、新增接口时容易漏加**。注解 + 切面把『哪些接口、什么维度、多少阈值』变成一处可审计的声明。"

```java
@PostMapping("/activities/{activityId}/order")
@RateLimit(rule = "seckill",
        dimensions = {RateLimitDimension.USER, RateLimitDimension.IP},
        message = "手速太快啦，请稍后再试")
public Result<SeckillResponse> seckill(@PathVariable Long activityId) { ... }
```

**注解方式的三个好处：**
1. **声明式**：一眼看出这个接口"用了哪条规则、什么维度"；
2. **规则集中**：阈值改了只改 `application.yml`（`mall.rate-limit.rules.seckill.limit`），**不需要重新编译**；
3. **不漏加**：可以用测试或者启动时校验"所有写接口都必须有 `@RateLimit`"。

**阈值配置化的额外价值：可以用配置做压测和演练。** 把 `limit` 临时调成 1，就能验证限流逻辑是否正确；线上出问题时可以临时调大阈值救急（**不需要发版**）。

**取舍：AOP 的代价。**
1. **调试链路变长**：出问题时 `jstack` 里会看到 `RateLimitAspect` 的栈帧，但这是可接受的（有明确的类名）。
2. **Spring AOP 的自我调用失效**：如果 `A` 方法调用同一个类里的 `B` 方法（`B` 上有 `@RateLimit`），**注解不会生效**（因为走的是 `this` 而不是代理）。本项目所有 `@RateLimit` 都标在 **Controller 方法**上（`SeckillController`、`ProductController`、`AuthController`），**外部调用经过代理，所以不会有这个问题**。
3. **代理顺序**：如果有多个切面（事务、限流、日志），顺序会影响行为。**限流应该在最外层**（先限流再进业务），这需要确认切面顺序（`@Order`）。**本项目没有多个切面叠加在同一方法上，所以暂时没有这个问题——但如果将来加了 `@Transactional` 在 Service 上、`@RateLimit` 在 Controller 上，天然就是"限流在外"的正确顺序。**

**"注解放在 Controller 而不是 Service"这个选择是有意的：** 限流是**接口层**的关注点（它关心的是 HTTP 请求的维度：userId、IP、API 名），放在 Controller 上语义最准确。如果放在 Service 上，切面就拿不到 `HttpServletRequest`（无法取 IP）。

**追问：** `resolveApiName` 为什么用"类名.方法名"而不是完整路径？
**答：**
```java
private String resolveApiName(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
}
```
用**简类名**（`getSimpleName()`）而不是全限定名（`getName()`），因为：
1. **限流 key 更短**（`rate:limit:api:SeckillController.seckill` vs `rate:limit:api:com.lxt.mall.seckill.SeckillController.seckill`）——**每个 key 省 20 字节 × 几百万个 key = 可观的内存**；
2. **可读性**：`redis-cli --scan --pattern 'rate:limit:api:*'` 的输出更清楚。
**风险是简类名可能冲突**（如果两个包下有同名的 Controller）。本项目里没有这个情况，而且**限流 key 冲突的后果是"两个接口共享一个配额"——这是一个可以通过命名规范避免的问题，不值得用更长的 key 来防。**

> 💡 **面试官意图：** AOP 的"自我调用失效"和"代理顺序"是常见追问。能说清"注解放 Controller 而不是 Service"的理由是加分。

---

### Q82. `X-Forwarded-For` 取第一个 IP，安全吗？

**结论：** **不安全，本项目是有意为之并明确标注了风险**——因为这里是限流而不是安全审计。

`RateLimitAspect` 的注释：
> "这里是【限流】而不是安全审计，所以按 `X-Forwarded-For` 的**第一个**地址取。**注意 XFF 是客户端可伪造的**：攻击者可以每次请求换一个假 IP 来绕过按 IP 限流。真正可信的来源应该是网关/负载均衡写入的、并且应用服务器只信任最后一跳。也正因如此，本项目所有限流策略里**一定会同时开启 USER 维度**——按 IP 限流只能挡住最粗糙的攻击，不能作为唯一的防线。"

```java
private String clientIp() {
    String xff = request.getHeader("X-Forwarded-For");
    if (StringUtils.hasText(xff)) {
        int comma = xff.indexOf(',');
        return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
    }
    String realIp = request.getHeader("X-Real-IP");
    if (StringUtils.hasText(realIp)) return realIp.trim();
    return request.getRemoteAddr();   // 兜底：没有代理头时用 TCP 连接的地址
}
```

**XFF 的格式和含义：** `X-Forwarded-For: client, proxy1, proxy2`
- **第一个**是"最原始的客户端 IP"——但它是由**第一个代理**根据 TCP 连接地址写进去的，这个值客户端无法伪造（客户端连到代理时，代理看到的是真实 IP）；
- **但如果客户端自己带了 XFF 头**，很多代理会**追加**而不是覆盖，于是 `XFF: 伪造的IP, 真实IP` ——**第一个就变成了伪造值**。

**本项目的风险敞口：**
- 攻击者每次请求设置不同的 `X-Forwarded-For` → **IP 维度限流被完全绕过**；
- **但 USER 维度仍然生效**（`UserContext.getUserId()` 来自 JWT，无法伪造）。

**所以 `SeckillController` 同时开了两个维度：**
```java
@RateLimit(rule = "seckill",
        dimensions = {RateLimitDimension.USER, RateLimitDimension.IP},
        message = "手速太快啦，请稍后再试")
```
注释解释了这个设计：
> "开 USER 是因为这是最可靠的一维——**伪造 IP 容易，伪造登录态难**；开 IP 是为了挡住『同一台机器控制大量账号』的脚本党。**只开其中任何一个都有明显短板。**"

**这个双维度的分析非常精准：**
| 维度 | 能挡什么 | 挡不住什么 |
| --- | --- | --- |
| **USER** | 一个账号疯狂刷 | 用大量账号（每个账号只刷几次） |
| **IP** | 同一台机器控制大量账号 | 伪造 XFF；分布式代理池 |
| **两者都开** | 覆盖两类攻击的交集 | 分布式 IP + 大量账号（需要更复杂的风控） |

**生产环境的正确做法：**
1. **应用服务器只信任最后一跳代理的地址**。具体做法：在网关（Nginx / SLB）里**覆写** XFF（`proxy_set_header X-Forwarded-For $remote_addr`）而不是追加；应用只解析**网关写入的那个值**。
2. **或者用"从右往左数第 N 个"**（N = 可信代理层数）而不是"第一个"。
3. **更强的方案是设备指纹 / 风控系统**（但这超出了后端面试的范围）。

**`RateLimitAspect` 的注释主动指出了这个问题，这是很好的工程实践：知道自己的方案有缺陷，把它写在代码里，让后来的人不会误以为它是安全的。**

**追问：** `request.getRemoteAddr()` 是可信的吗？
**答：** **相对可信**——它是 TCP 连接的对方地址，无法通过 HTTP 头伪造。但在有反向代理的情况下，它是**代理的地址**（所有请求看起来都来自代理），所以按 IP 限流会把所有用户算成同一个 IP——**这才是必须用 XFF 的原因**。
**所以这里有一个两难：**
- 用 `getRemoteAddr()`：不可伪造，但所有请求都是代理 IP（限流失效）；
- 用 XFF：能拿到真实客户端 IP，但可伪造。

**这个两难没有完美解法，只能靠"信任边界"的设计**：应用要知道"我前面有几层代理、哪一层是可信的"，然后从 XFF 里取那个可信代理写入的值。**这是部署架构问题，不是代码问题。**

> 💡 **面试官意图：** XFF 伪造是安全基础题。但"用 USER 维度兜底"和"信任边界"的分析是深度体现。

---

### Q83. JWT 里为什么只放 `userId` / `username` / `jti`，不放权限？

**结论：** 因为 JWT 的 payload **只是 Base64，不是加密**；而且权限一旦写进 token，**改权限就必须等 token 过期才生效**。

`JwtTokenService` 的注释列了四个"刻意的设计选择"：

**① 只放 `userId` / `username` / `jti`，不放权限和敏感信息**
> "JWT 的 payload 只是 Base64，不是加密——**放手机号、角色列表等于明文泄露**；而且权限一旦写进 token，**改权限就必须等 token 过期才生效**。"

**② 必须带 `jti`**
> "没有 `jti` 就没法精确拉黑某一个令牌，主动退出只能退化成『改密钥让全站 token 失效』，代价太大。"

**③ 算法的显式指定（`Jwts.SIG.HS256`）**
> "JWT 最经典的漏洞是 **alg 混淆攻击**：攻击者把 header 改成 `{"alg":"none"}`，某些库的实现会直接跳过验签。解析侧用 `verifyWith(key)` 固定密钥，签发侧固定算法，**两端都不接受 header 里自称的算法**。"

**④ 校验 issuer 与时钟偏移**
> "`issuer` 防止别的系统用同一个 secret 签出来的 token 混进来；时钟偏移（默认 30s）避免多实例部署时机器时间差几秒就导致 token 被误判过期。"

**实现：**
```java
this.parser = Jwts.parser()
        .verifyWith(key)                                    // 固定密钥，不接受 header 自称的算法
        .requireIssuer(this.props.getIssuer())               // 校验 issuer
        .clockSkewSeconds(this.props.getClockSkew().toSeconds())  // 30s 时钟偏移
        .build();
```
```java
String token = Jwts.builder()
        .issuer(props.getIssuer())
        .subject(String.valueOf(userId))
        .id(jti)
        .claim(CLAIM_USERNAME, username)     // 只有这一个业务 claim
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .signWith(key, Jwts.SIG.HS256)       // 显式指定算法
        .compact();
```

**密钥强度的强制校验（很值得说）：**
```java
if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
    throw new IllegalStateException(
            "mall.jwt.secret 长度不足：HS256 要求密钥至少 256 位（32 字节），"
                    + "当前 secret 不满足要求，请通过环境变量 JWT_SECRET 注入");
}
```
**这是 fail-fast：不满足密钥强度就直接启动失败**，而不是"用一个弱密钥跑起来，上线后才发现签名可以被爆破"。HS256 的安全性直接依赖密钥长度——**短密钥可以被离线爆破，爆破成功就能伪造任意用户的 token。**

配置里也做了环境变量的强制注入提示：
```yaml
secret: ${JWT_SECRET:mall-dev-secret-key-please-override-in-production-0123456789}
# 生产必须通过 JWT_SECRET 注入；默认值仅供本地跑通
```

**取舍：JWT vs Session**
| 维度 | JWT（本项目） | Session（Redis 存储） |
| --- | --- | --- |
| 服务端状态 | 无状态（除黑名单） | 有状态（Redis 里存 session） |
| 水平扩展 | 天然支持 | 需要共享存储（已经有 Redis 了） |
| 主动失效 | **需要黑名单**（本项目实现） | **天然支持（删 key）** |
| 单次请求开销 | 验签（HMAC，微秒级） | 一次 Redis RTT |
| 信息泄露 | payload 明文（Base64） | 只在服务端 |
| 大小 | 几百字节（每个请求都带） | 只是一个 sessionId |

**本项目选 JWT 的理由：** ① 秒杀接口在热路径上，**JWT 验签不产生网络往返**（而 Session 每次都要查 Redis）；② 水平扩展不需要额外的 session 共享配置。
**代价：** 主动退出需要黑名单（额外一次 Redis 查询）——**所以在有黑名单的情况下，JWT 的"无网络往返"优势在登出后的 token 上就消失了**。这是一个真实的权衡：**大部分活跃 token 不在黑名单里（一次 Redis 查询，但可以不走？不行，必须查）**。

**等等，这里要精确：** `LoginInterceptor` 每次都查黑名单：
```java
String jti = claims.getId();
if (tokenBlacklist.isBlacklisted(jti)) {
    throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
}
```
**所以每个请求都会有一次 Redis 查询**（`isBlacklisted`）。这意味着**本项目的 JWT 方案在每个请求上都要访问 Redis**，和 Session 的开销接近。

**这是一个真实的取舍我会主动说：** "引入黑名单后，JWT 的无状态优势被削弱了——每个请求都要查一次 Redis。**如果目的是降低 Redis 压力，应该把黑名单改成进程内 Caffeine 缓存 + 短 TTL（比如 5 秒）**：绝大多数 jti 都不在黑名单里，本地缓存能挡住绝大部分查询。**代价是"退出登录后最多 5 秒内 token 仍可用"**——对于登出场景，5 秒的不一致通常可以接受。"

**追问：** 那如果不查黑名单，用短 TTL 的 access token + refresh token 呢？
**答：** 这是另一个主流方案：
- `access-token-ttl` 缩短到 **5~15 分钟**；
- 配一个长效的 refresh token（存 Redis，可以主动删除）；
- 登出时删掉 refresh token，**access token 最多 15 分钟后自然失效**。

**优点：** access token 的校验完全无状态（不查 Redis）。
**缺点：** ① 用户需要 refresh 流程（多一次接口调用）；② **"登出后 15 分钟内 token 仍然有效"这个窗口对安全要求高的场景不可接受**；③ refresh token 本身需要存储和撤销机制（就变成了 Session）。

**本项目的选择是"明确的代价"：** 用每个请求一次 Redis 查询，换取"登出立即生效"。**如果 Redis 的 QPS 成为瓶颈，再改成 Caffeine 缓存黑名单（5 秒 TTL）。** —— 这是一个"先做对，再根据监控做优化"的典型思路。

> 💡 **面试官意图：** JWT 的三个安全坑（alg 混淆、payload 明文、无法撤销）都能答到就是满分。能主动分析"黑名单削弱了无状态优势"是深度体现。

---

### Q84. 用户名枚举漏洞怎么防的？BCrypt 为什么比 MD5+盐 好？

**结论：** 用户不存在与密码错误**返回同一个错误码**；BCrypt 的核心差异在于**可调的计算成本**。

**用户名枚举：**
```java
User user = userMapper.selectOne(...eq(User::getUsername, request.getUsername()));

// 用户不存在与密码错误返回同一个错误码：
// 区分开来等于给攻击者一个免费的"用户名枚举"接口。
if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
    throw new BusinessException(ResultCode.PASSWORD_ERROR);
}
```
**如果分开返回**（`USER_NOT_FOUND` vs `PASSWORD_ERROR`），攻击者就可以用一份用户名字典**批量试出哪些用户名存在**。然后针对存在的用户名做定向的密码爆破（或者撞库）。
**统一错误码后，攻击者无法区分这两种情况**，枚举的成本大幅上升。

**注意 `||` 的短路：**
- 如果 `user == null`，`passwordEncoder.matches` **不会执行** → 这个请求的响应时间**远小于**"用户存在但密码错"的请求；
- **这是一个真实的时序侧信道！** 攻击者可以通过响应时间区分"用户不存在"（快，没做 BCrypt）和"密码错误"（慢，做了 BCrypt）。

**这个问题在项目里存在（我会主动承认）：** 
**正确的防御是"即使用户不存在也执行一次 BCrypt 校验"**（对一个固定的假 hash 做 `matches`），让两条路径的耗时一致。代码大致是：
```java
User user = userMapper.selectOne(...);
String hash = (user != null) ? user.getPassword() : FAKE_BCRYPT_HASH;
boolean ok = passwordEncoder.matches(request.getPassword(), hash);
if (user == null || !ok) throw new BusinessException(ResultCode.PASSWORD_ERROR);
```
**这是一个"我知道但没做"的安全加固点**，我会在面试里主动提出来。**相比"我不知道有这个问题"，主动指出会好得多。**

**BCrypt vs MD5+盐：**
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);   // cost = 10
}
```
`CommonConfig` 的注释：
> "选 BCrypt 而不是 MD5/SHA256 加盐，核心差异在于**计算成本**：BCrypt 的 `cost` 参数可以**指数级抬高单次哈希的耗时**（默认 10 ≈ 50~100ms），让离线暴力破解的代价高到不可接受。MD5 哪怕加了 salt，单次计算也是**微秒级**，GPU 集群下几亿次/秒。"

| 维度 | MD5 + 盐 | BCrypt（cost=10） |
| --- | --- | --- |
| 单次计算 | ~1 微秒 | **~50~100 毫秒** |
| GPU 爆破速度 | 几亿次/秒 | **几万次/秒**（慢 4 个数量级） |
| 加盐 | 需要自己实现 | **内置**（hash 里就包含 salt） |
| 自适应 | 不能 | **可以调 cost**（硬件变快就调大） |
| 彩虹表 | 加了盐就防住了 | 防住 |

**"cost 可以指数级抬高"的含义：** cost 每 +1，计算量翻倍。cost=10 是 2^10 = 1024 轮；cost=14 是 16384 轮（约 1 秒）。**所以可以在硬件升级后调整 cost 来保持破解成本。**

**BCrypt 的另一个优势：内置 salt。** `BCryptPasswordEncoder.encode()` 每次生成的结果都不同（`AuthService` 注释："BCrypt 自带随机盐，同一个密码每次加密结果都不同，**无法被彩虹表命中**"）。salt 存在 hash 字符串里（`$2a$10$...` 格式的 `$` 分段），所以校验时不需要额外存 salt。

**代价：登录接口变慢。**
> "代价是登录接口本身变慢（每次 50~100ms），**所以登录接口一定要限流**，否则这个『慢』会变成被利用的放大攻击面。"

**这是一个非常重要的连锁分析：** BCrypt 的慢**既是安全优势也是攻击面**——攻击者可以用大量登录请求让你的 CPU 全部花在 BCrypt 上（**计算型 DoS**）。
**所以登录限流是必须的：** `mall.rate-limit.rules.login: 10/60s, TOKEN_BUCKET, capacity 10, rate 0.5`。
**这个限流的阈值和 BCrypt 的成本是配套的**：0.5/s 的长期速率 × 100ms 的 CPU = 每秒只花 5% 的 CPU 在一个攻击源上。**如果限流是 100/s，那 100 × 100ms = 10 秒的 CPU 需求，单核直接打满。**

**"安全措施的性能代价必须被限流配套保护"——这个洞察很有价值。**

**追问：** 那如果攻击者用 10 万台机器、每台 0.5/s 呢？
**答：** 那就是分布式攻击（**50000 QPS 的 BCrypt 请求 = 5000 秒的 CPU 需求/秒**），任何单机方案都挡不住。防御要靠：
1. **前置的 DDoS 清洗 / WAF**；
2. **验证码**（人机识别，把"自动爆破"变成"人工"）；
3. **账号锁定**（连续 N 次失败后锁定一段时间）—— 但要注意**这本身是一个 DoS 面**（攻击者可以故意输错密码把别人的账号锁住）；
4. **风险评分**（异常 IP、异常时间、异常频率的风控系统）。

**这类问题的答案层次是"单机防护 → 集群防护 → 风控系统"，能说出这个层次就足够了。**

> 💡 **面试官意图：** "BCrypt 慢是安全优势也是攻击面"是高级洞察。能主动指出时序侧信道问题是非常好的安全素养。

---

### Q85. 黑名单用 `jti`，TTL 怎么定？`isHeldByCurrentThread` 式的检查在这里有吗？

**结论：** TTL 精确取 **token 的剩余有效期**，所以黑名单**永远不会无限膨胀**。

```java
public void logout(String token) {
    if (!StringUtils.hasText(token)) {
        // 没有 token 时用上下文里的 jti 兜底
        UserContext.LoginUser current = UserContext.get();
        if (current != null) {
            tokenBlacklist.blacklist(current.getJti(), properties.getJwt().getAccessTokenTtl());
        }
        return;
    }
    try {
        Claims claims = jwtTokenService.parse(token);
        Duration remaining = jwtTokenService.remainingTtl(claims);
        tokenBlacklist.blacklist(claims.getId(), remaining);
        log.info("用户退出登录 userId={} 黑名单TTL={}s", claims.getSubject(), remaining.toSeconds());
    } catch (Exception e) {
        // token 本来就非法/已过期，"退出"这个诉求已经天然满足了，
        // 不必也不应该报错——否则用户会遇到"退出失败"这种诡异提示。
        log.debug("退出登录时令牌已失效: {}", e.getMessage());
    }
}
```

**`remainingTtl` 的实现：**
```java
public Duration remainingTtl(Claims claims) {
    Date exp = claims.getExpiration();
    if (exp == null) return Duration.ZERO;
    Duration d = Duration.between(Instant.now(), exp.toInstant());
    return d.isNegative() ? Duration.ZERO : d;
}
```

**为什么 TTL 要精确等于剩余有效期（而不是固定的 2 小时）：**
- **token 一过期，黑名单条目就没有意义了**（验签时因为 `exp` 过期就会被拒绝，根本不会走到查黑名单）；
- **所以要精确**：一个还有 5 分钟过期的 token，黑名单条目只需要存在 5 分钟；
- **效果**：`AuthService` 的注释说："这样即使有人反复登录-登出几百万次，**黑名单的内存占用也有上界**。"
  上界 = `每用户活跃 token 数 × 黑名单条目大小`。因为 `access-token-ttl = 2h` 且每次登录生成新 jti，**理论上单个用户最多有"2 小时内登录次数"个黑名单条目**。如果限制登录频率（10/60s），**单个用户最多约 20 个条目**。加上 key 的 TTL，**总量是收敛的**。

**配置：**
```yaml
auth:
  blacklist-enabled: true
  blacklist-key-prefix: "auth:token:blacklist:"
```

**两个分支的设计（很值得说）：**
1. **有 token**：解析它，拿 `jti` + 剩余时间 → 精确拉黑；
2. **没有 token**：用 `UserContext.get().getJti()` 兜底，TTL 用配置的完整时长（`access-token-ttl = 2h`）。

**为什么"没有 token"也要处理？** 因为客户端可能没带 Authorization 头但仍然调了登出接口（比如前端清空了本地存储再调登出）。此时**用户已经在上下文里有 jti**（拦截器从 token 里解析出来的），可以用它。
**注意 TTL 用的是完整时长而不是剩余时间** —— 因为 `UserContext` 里没存 `exp`。**这是一个小的不精确**（黑名单条目会多活一会儿），但**方向是安全的**（宁可多黑名单一会儿，也不能漏）。

**关键：`logout` 里对解析失败的 token 不报错。**
```java
} catch (Exception e) {
    // token 本来就非法/已过期，"退出"这个诉求已经天然满足了，
    // 不必也不应该报错——否则用户会遇到"退出失败"这种诡异提示。
    log.debug("退出登录时令牌已失效: {}", e.getMessage());
}
```
**这是一个"幂等语义"的判断：** 用户的目标是"让这个 token 不能再被使用"。
- 如果 token 已经过期 → **目标已经达成**，不需要做任何事，返回成功；
- 如果 token 非法（签名错）→ 这个 token 本来就用不了，**目标也已经达成**，返回成功。

**"我要达成的目标"和"我要执行的操作"是两件事。** 如果接口的语义是"确保 token 失效"，那么在 token 已经失效时返回成功就是**最正确的行为**。
**反例（错误的实现）**：`if (!valid) throw new BusinessException("退出失败")` —— 用户会看到"退出失败"，但他明明已经退出了。**这是一个典型的"把实现细节当成了业务结果"的错误。**

**追问：** 黑名单用 Redis 的什么结构？
**答：** 从用法看是字符串 key + TTL：`auth:token:blacklist:{jti}` → 值任意（比如 `"1"`）。
**为什么不用 ZSET 或者 Set？** 因为每个 jti 的 TTL 不同（各自的 token 剩余时长），**只有"每个 key 独立 TTL"才能精确表达**。如果用 Set，整个 Set 只有一个 TTL，无法对不同的 jti 设置不同的过期时间（除非自己做定期清理，那就是重新实现了一遍 Redis 的 TTL）。
**"用独立 key + TTL"是利用 Redis 原生能力的最简方案。**

**如果黑名单 QPS 太高怎么办？**（回到 Q83 的讨论）可以用 Caffeine 本地缓存"不在黑名单"这个结论，TTL 设 5 秒。**但要注意"否定缓存"的风险**：如果一个 jti 在本地缓存了"不在黑名单"，那么它在这 5 秒内即使被拉黑也不会生效——**这是"登出后最多 5 秒仍有效"的代价。**

> 💡 **面试官意图：** "TTL 精确等于剩余时长"体现对内存的理解；"token 已失效时返回成功"体现接口语义设计的成熟度。

---

### Q86. 越权漏洞怎么防的？`queryVO` 里为什么有个 `userId != null` 的判断？

**结论：** 用参数做归属校验；`userId != null` 是为了区分"当前用户查询"和"系统内部调用"。

```java
public OrderVO queryVO(String orderNo, Long userId) {
    Order order = findByOrderNo(orderNo);
    if (order == null) {
        throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
    }
    // 越权校验：只能查自己的订单。
    // 这是最容易被忽略的漏洞之一——用订单号做查询参数却不校验归属，
    // 攻击者遍历订单号就能拿到别人的订单信息。
    if (userId != null && !userId.equals(order.getUserId())) {
        throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该订单");
    }
    return OrderVO.from(order);
}
```

**这个漏洞有多常见：** "用订单号做查询参数却不校验归属"是**电商/外卖类系统最常见的越权漏洞**。订单号如果是自增的，攻击者从 1 遍历到 100000 就能拿到所有订单（包括姓名、电话、地址、金额）。
**本项目用雪花 ID 做订单号（`"SO" + IdWorker.getIdStr()`）**，所以"遍历"的成本极高（雪花 ID 是 19 位数字，且时间戳部分每次不同）——**但这是"隐蔽性"，不是"安全性"**。**必须做归属校验。**

**`userId != null` 的判断意图：**
- **Controller 调用时**传的是 `UserContext.requireUserId()`（一定非 null）→ **会做归属校验**；
- **系统内部调用时**（比如 `OrderTimeoutFallbackJob`、对账任务）可能不需要归属校验 → 传 `null` 就跳过。

**评审这个设计（我会主动说）：**
**"用 `null` 表示'跳过鉴权'是一个危险的 API 设计"** —— 它的风险是：
1. **调用方忘记传 userId** → 静默跳过鉴权（**fail-open**）；
2. 代码审查时很难发现"这里漏了鉴权"。

**更安全的写法：**
- **拆成两个方法**：`queryOwn(orderNo, userId)`（强制校验）和 `queryAsSystem(orderNo)`（明确命名，表示这是系统调用）；
- **或者用注解**：`@RequiresOwnership`；
- **最少也要在 `null` 分支打一条 DEBUG/WARN 日志**，让"跳过鉴权"这件事**可见**。

**在秒杀结果的查询路径上，鉴权是被强制的：**
```java
// SeckillController.result
@GetMapping("/result/{orderNo}")
public Result<OrderVO> result(@PathVariable String orderNo) {
    Long userId = UserContext.requireUserId();
    try {
        return Result.ok(orderService.queryVO(orderNo, userId));   // ← userId 一定非 null
    } catch (BusinessException e) {
        SeckillResultVO vo = seckillService.queryResult(orderNo, userId);
        return Result.fail(ResultCode.SECKILL_QUEUEING, vo.getMessage());
    }
}
```
**注意这里有一个漏洞（我会主动指出）：`SeckillService.queryResult(orderNo, userId)` 的实现里根本没校验归属！**
```java
public SeckillResultVO queryResult(String orderNo, Long userId) {
    SeckillResultVO cached = resultService.get(orderNo);
    if (cached != null && cached.getStatus() != SeckillResultStatus.QUEUEING) {
        return cached;
    }
    return cached != null ? cached : SeckillResultVO.queueing(orderNo);
}
```
**`userId` 参数传进来了但没被使用** —— 这意味着攻击者可以遍历 orderNo 拿到**别人抢购结果的缓存**（包含订单状态）。
**严重程度评估：** 泄漏的信息是"某个订单号的状态是成功/失败/排队中"，**不含用户信息、不含金额**。而且订单号是雪花 ID（不可遍历）。**所以实际风险较低，但这确实是一个应该修的问题。** 修法很简单：在 `queryResult` 里加一次 `orderMapper.exists(orderNo AND userId = ?)` 校验，或者把归属校验下沉到 `resultService`。
**我会在面试里主动说这个发现** —— 因为它展示了"我能审查自己的代码并发现漏洞"，比"我的代码没有漏洞"强得多。

**追问：** 那如果 `userId == null` 的调用方忘记了传值，就静默跳过鉴权了——这个风险怎么防？
**答：** **这正是当前 API 设计的问题所在。** 我列三条防线，按强度排序：
1. **拆方法（最推荐）**：`queryOwn(orderNo, userId)` 强制校验 + `queryAsSystem(orderNo)` 明确表示系统调用。**"跳过鉴权"这件事从"参数为 null"变成了"方法名里写着 AsSystem"，代码审查时一眼可见。**
2. **最小改动**：在 `userId == null` 的分支打 `log.debug("跳过订单归属校验 orderNo={}", orderNo)` —— **至少让这件事在日志里有痕迹**，而不是完全静默。
3. **保持现状 + 约定**：当前只有 `SeckillController.result` 一处调用，且传的是 `UserContext.requireUserId()`（必非 null）。**但要靠在代码审查里保证"所有新调用方都必须传 userId"——这是最弱的一条。**

**判断依据：** 一个"跳过安全校验"的分支，它的**默认行为必须是安全的**（fail-close）。用 `null` 表示"跳过"是 **fail-open** 的语义，和这条原则相反。**所以我会选方案 1。**

> 💡 **面试官意图：** 越权是 OWASP Top 1。能主动发现自己代码里的越权缺口，是极强的信号。

---

### Q87. 拦截器里 `afterCompletion` 一定要清理 `ThreadLocal` 吗？

**结论：** 一定要，而且**必须放在 `afterCompletion`（不是 `postHandle`），因为它保证异常路径也会执行**。

```java
@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                            Object handler, Exception ex) {
    UserContext.clear();
}
```
`LoginInterceptor` 的注释：
> "Tomcat 线程池会复用工作线程，只在 `preHandle` 里 set 而不清理，下一个复用同一线程的请求就会带着上一个用户的身份进入业务逻辑——**这是一类真实发生过的越权漏洞**。放在 `finally` 里保证异常路径也会清理。"

**`HandlerInterceptor` 的三个回调时机：**
| 回调 | 时机 | 异常时是否执行 |
| --- | --- | --- |
| `preHandle` | 进入 Controller 前 | — |
| `postHandle` | Controller 返回后、视图渲染前 | **❌ 不执行**（有异常时跳过） |
| `afterCompletion` | **整个请求完成后** | **✅ 一定执行**（相当于 finally） |

**所以清理必须放在 `afterCompletion`。** 如果放在 `postHandle`：
- 业务抛异常 → `postHandle` 不执行 → **ThreadLocal 泄漏** → 下一个请求带着上一个用户的身份；
- 这**正是**注释里说的"真实发生过的越权漏洞"。

**ThreadLocal 泄漏的两层危害：**
1. **内存泄漏**：Tomcat 的线程是长期存活的（`max = 500`），ThreadLocal 的值会一直挂在 `ThreadLocalMap` 里不被回收。如果有大量请求在 set 之后没 clear，**用户对象会堆积在 500 个线程的 ThreadLocalMap 里**。
   本项目里 `LoginUser` 只有 3 个字段（userId、username、jti），泄漏的内存有限。**但如果存的是一个大对象，就是真实的内存泄漏。**
2. **身份串号（更严重）**：下一个请求如果**没有经过 `preHandle` 的 set**（比如访问了一个不拦截的路径，但业务代码里读了 `UserContext.get()`），会读到上一个用户的身份。**这是数据泄漏和越权。**

**`UserContext` 的实现应该用 `remove()` 而不是 `set(null)`：**
```java
public static void clear() {
    CURRENT.remove();   // ← 应该用 remove
}
```
**为什么：** `ThreadLocal.set(null)` 只是把 value 设为 null，但**key（弱引用）仍在 `ThreadLocalMap` 里**；而 `remove()` 会真正删除这个 entry，避免泄漏。**（项目里 `UserContext.clear()` 的具体实现我没在当前读取的文件里看到，这里说的是标准做法。）**

**追问：** 那 `preHandle` 每次都会 set，岂不是覆盖了？不清理行不行？
**答：** **覆盖是"值"的覆盖，不是"entry"的覆盖。** 每次 `set` 会复用同一个 entry（key 是同一个 `ThreadLocal` 对象），所以**值不会堆积**。但：
1. **如果某个请求没走 `preHandle`（比如静态资源、白名单路径），它读到的就是上一个用户的值** —— 这才是真正的危害；
2. **entry 本身不会被回收**（只要线程活着），虽然只有一个 entry，但它持有一个 `LoginUser` 对象的引用——**这个用户对象在线程池存活期间不会被 GC**。

**所以"清理"不只是为了内存，更是为了正确性。** 我个人认为**正确性（不串号）才是主要理由**，内存泄漏是次要的。

**"白名单路径"的隐患值得展开：** 如果 `WebMvcConfig` 里配置了 `excludePathPatterns`（比如 `/api/auth/login`、`/api/product/**` 不拦截），那么这些路径**不经过 `LoginInterceptor.preHandle`**。
- 它们的业务代码里**不应该**读 `UserContext.get()`；
- 但如果读了（比如某个 Service 方法同时被鉴权接口和公开接口调用），就会读到**上一个用户在线程里残留的值**。
**所以 `UserContext` 的正确用法是：只在明确经过鉴权的路径上读，并且优先用 `requireUserId()`（读不到就抛异常）而不是 `getUserId()`（返回 null，容易 NPE 或静默错误）。**

**注意项目里两种方法都有：**
- `SeckillController.seckill` 用 `UserContext.requireUserId()`（强校验）；
- `RateLimitAspect.resolveTarget` 用 `UserContext.getUserId()`（可空，取不到就跳过这一维）。

**这两种用法的区别是清楚的**：限流维度取不到值时"跳过该维度"是合理降级；秒杀下单取不到用户则必须拒绝。**"同一个 API 有两种用法"如果没有明确的语义区分就是设计问题——但这里两种用法都有明确理由。**

> 💡 **面试官意图：** `afterCompletion` vs `postHandle` 是 Spring MVC 的基础题。能进一步分析"白名单路径的残留值"是深度。

---

### Q88. 秒杀活动的预热接口为什么是高危的？

**结论：** 因为它**会覆盖 Redis 里的库存快照**。

```java
/**
 * 手动预热（演示/运维用）。
 *
 * <p>生产环境这类接口必须放在鉴权之后并限制为运维角色，
 * 因为它会覆盖 Redis 里的库存快照，属于高危操作。
 */
@PostMapping("/activities/{activityId}/preheat")
public Result<Boolean> preheat(@PathVariable Long activityId) {
    return Result.ok(preheatService.preheat(activityId));
}
```
**注意这个接口在 `SeckillController` 里，而 `SeckillController` 的路径是 `/api/seckill`。** 需要看 `WebMvcConfig` 的拦截器配置才能知道它是否需要登录——但**从注释看，作者明确意识到这个接口需要鉴权 + 角色限制**。

**为什么高危：** `preheatService.preheat(activityId)` 会：
```java
stockService.setActivitySnapshot(activityId, json, ttl);        // 覆盖活动快照
stockService.preheatStock(activityId, activity.getAvailableStock(), ttl);  // 覆盖库存！
stockService.setBoughtSetExpire(activityId, boughtTtl);
activityService.evictLocal(activityId);                          // 清本地缓存
```
**`preheatStock` 是 `redis.opsForValue().set(...)` —— 无条件覆盖。**

**攻击场景：** 如果攻击者能在活动进行中调用这个接口：
1. **库存被重置到 DB 的 `available_stock`** —— **这会把已经"在 Redis 里扣掉但还没落库"的库存还回来**！也就是把在途请求的扣减抹掉 → **超卖**；
2. **活动快照被重置** —— 如果攻击者能改变 `seckill_price`...（不行，价格来自 DB 的 `activity.getSeckillPrice()`，攻击者改不了）；
3. **本地售罄标记被清除** —— 攻击者可以让已售罄的活动"看起来还有货"，**制造大量无用请求打到 Redis**（一种放大攻击）。

**危害 1 是最严重的：** 
```
活动库存 100，已经卖出去 50 件（DB available_stock = 50），Redis stock = 50
此刻还有 10 个请求在途（Redis 扣了，DB 还没落库）
Redis stock = 40, inflight = 10, DB available_stock = 50
攻击者调用 preheat → preheatStock(activityId, 50)  ← 用了 DB 的值！
现在 Redis stock = 50，但还有 10 个在途请求会各自扣 1 次 DB
结果：Redis 放行了 10 个额外的请求 → 超卖 10 件（如果 DB 的 available_stock 也够的话）
```

**正确做法（我会在面试里给出的防御清单）：**
1. **鉴权 + 角色**：这个接口必须要求 `ADMIN` 角色，而不仅仅是"已登录"；
2. **只在活动未开始时允许预热**：`if (now.isAfter(activity.getStartTime())) throw new BusinessException("活动已开始，不允许重新预热")`；
3. **审计日志**：谁在什么时候预热了哪个活动；
4. **或者干脆在生产环境移除这个接口**（本地演示用 `PREHEAT_ENDPOINT_ENABLED` 开关控制）；
5. **预热逻辑本身要幂等且不倒退**（当前设计已经做到了：只在活动未开始时预热，且用 `available_stock` 而不是 `total_stock`）。

**注意第 2 条是根本解法：** 只要限制"活动开始后不能预热"，危害 1 就不存在了。**而当前的 `preheat` 没有这个检查**（`preheatUpcoming` 是在定时任务里按时间窗口筛的，但手动接口直接调 `preheat(activityId)` 没有时间限制）。

**这是一个真实的安全缺口，我会主动在面试里指出，并给出修复方案。** 面试官问"你的项目有什么安全问题"，能答出这个（而不只是"我用了 JWT"）会很加分。

**追问：** 那 `AdminController` 里的接口是怎么保护的？
**答：** 需要看 `WebMvcConfig` 的拦截器配置。**一般的做法是 `/api/admin/**` 全部要求登录 + 在拦截器里校验角色**。
**本项目的一个潜在问题：** JWT 里**没有放角色**（Q83：只放 userId/username/jti），**所以"管理员角色"必须每次查 DB 或者单独维护一张角色表**。这是一个"JWT 不放权限"设计带来的实际代价——**要么每次查 DB，要么把角色放到 Redis 里缓存。**
**这是一个很好的"设计决策的连锁后果"的例子：** "JWT 不放权限"（为了安全和即时生效）+ "管理接口需要角色校验" = **必须有额外的角色查询路径**。这个路径本项目里是缺失的。

> 💡 **面试官意图：** "你自己的项目里有什么安全问题"是最能测出安全意识的开放题。能答出预热接口的风险，比背 OWASP Top 10 有价值得多。

---

### Q89. 限流的 key 是怎么拼的？会不会有大 key 或者热点问题？

**结论：** key 格式是 `rate:limit:{rule}:{dimension}:{target}` 和 `rate:token:{rule}:{dimension}:{target}`。

```java
public static String rateLimit(String rule, String dimension, String target) {
    return "rate:limit:" + rule + ":" + dimension + ":" + target;
}
public static String tokenBucket(String rule, String dimension, String target) {
    return "rate:token:" + rule + ":" + dimension + ":" + target;
}
```
`dimension` 的取值：`global` / `api` / `user` / `ip`（`RateLimitDimension` 枚举）。
`rule` 的取值：`seckill` / `product-detail` / `login`（`mall.rate-limit.rules.<rule>` 的键）。
`target` 的取值由 `RateLimitAspect.resolveTarget` 决定：
```java
case GLOBAL -> "all";
case API    -> apiName;              // "SeckillController.seckill"
case USER   -> userId == null ? null : String.valueOf(userId);
case IP     -> clientIp();
```

**所以实际的 key 长这样：**
```
rate:limit:seckill:USER:10086
rate:limit:seckill:IP:203.0.113.45
rate:limit:product-detail:IP:203.0.113.45     ← 和上面完全独立
rate:limit:seckill:API:SeckillController.seckill
rate:token:login:IP:203.0.113.45
```

**⭐ `rule` 这一层是后加的 —— 它的缺失是一个真实的 Bug（见第十四节缺陷 ④）。**

**修改前的 key 格式是 `rate:limit:{dimension}:{target}`，不包含规则名。** 后果是**不同规则在同一个维度上会撞车、共享同一份额度**：

```
修改前（有 Bug）：
  product-detail 规则：IP=127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值 50/s）
  seckill       规则：IP=127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值  5/s）
                                    ↑ 同一个 key！同一个 ZSET！
```

**用户先浏览 8 次商品详情、紧接着点秒杀：**
1. 8 次商品详情请求被记进 `rate:limit:IP:127.0.0.1` 这个 ZSET（8 << 50，商品详情规则完全放行）；
2. 点秒杀时，秒杀规则的脚本读到 **`ZCARD = 8 >= 5`** → **正常用户被误判为"手速太快"**，返回 `42900` 限流错误。

**这个 Bug 最阴险的地方在于它的复现条件：**
> **只有"先访问某个大额度的接口、再访问小额度接口"才会触发。单独压测秒杀接口永远发现不了** —— 因为单独压测时，`rate:limit:IP:xxx` 这个 key 里只有秒杀自己的请求，计数是正确的。
> **它是靠"真实冒烟测试"（模拟用户的连续操作）才暴露出来的，而不是靠单接口压测。**

**修复后的语义也变得更清晰：**
| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| 不同规则、同一维度 | ❌ 共享一份额度（**误伤用户**） | ✅ 完全隔离，各自维护额度 |
| 同一个 rule 被多个接口引用 | 恰好共享（但和上面无法区分，语义是偶然的） | ✅ **有意共享** —— 这正是"一个规则多处复用、只维护一份配置"想要的效果 |

**代码层面的改动：**
```java
// RedisRateLimiter：方法签名增加 rule 参数
public boolean tryAcquireSlidingWindow(String rule, String dimension, String target,
                                       int limit, Duration window) {
    String key = RedisKeys.rateLimit(rule, dimension, target);
    ...
}
```
```java
// RateLimitAspect：把注解上的规则名传下去
boolean allowed = doAcquire(rateLimit.rule(), rule, dimension, target);
...
private boolean doAcquire(String ruleName, MallProperties.Rule rule,
                          RateLimitDimension dimension, String target) { ... }
```
**注意 `RateLimitAspect` 里用了 `rateLimit.rule()`（注解上的字符串常量）而不是从配置对象的引用推导** —— 因为配置对象本身不携带"自己叫什么名字"，**用注解的字符串是唯一可靠的名字来源**。

**配套的回归测试（修复闭环）：**
```java
@Test
@DisplayName("不同规则在同一维度上互不串额度（真实 Bug 的回归测试）")
void differentRulesDoNotShareQuota() { ... }
```
**这条测试的价值在于它复现的是"跨接口的交互"，而不是单接口的行为。** 我把它写成"先用大额度规则打若干次，再用小额度规则打一次，断言后者不被前者影响"——**这样任何一次"把 rule 从 key 里删掉"的重构都会被立刻拦下。**
**这条测试的教训值得单独记：** 单元测试/单接口压测的盲区是"**组件之间的隐式耦合**"。写完一个 Bug 的回归测试时，要问自己"**我测的是单点行为，还是那个真正导致 Bug 的交互？**" —— 如果只测"秒杀限流 5/s 生效"，这个 Bug 永远不会被发现。

**（下文的 key 规模分析基于修复后的格式 `rate:limit:{rule}:{dimension}:{target}`。）**

**key 的规模分析：**
| key 类型 | key 数量 | 单个 key 大小 | 热点风险 |
| --- | --- | --- | --- |
| `{rule}:GLOBAL` | **规则数（3 个）** | 该规则的全部流量在一个 ZSET 里 | ⚠️ **最高**：所有请求都操作同一个 key |
| `{rule}:API` | 规则数 × 接口数 | 每个接口一个 | 中 |
| `{rule}:USER` | 规则数 × 用户数量级 | 每个 key 最多 `limit` 个 member | 低 |
| `{rule}:IP` | 规则数 × IP 数量级（可被 DDoS 放大） | 同上 | 低（但 key 数量可能爆炸） |

**注意 `rule` 的引入让 key 数量乘了 3 倍** —— 这是一个**正确性优先于内存**的取舍：3 倍的 key 数量换来"规则之间绝不串额度"。**而且这些 key 都有 TTL（窗口过期即删），不会长期占用内存。**

**⚠️ `GLOBAL` 维度的问题是真实的：** 如果对秒杀接口开了 `GLOBAL` 维度（`limit = 5/1s`），那么**整个系统的秒杀请求总共只能 5/s** —— 这显然是错的。
**本项目在 `SeckillController` 上用的是 `{USER, IP}`，没有用 `GLOBAL`。** `GLOBAL` 维度存在但是**不建议对高频接口使用**。
**注意：修复缺陷 ④ 之后，`GLOBAL` 维度的危险程度降低了**——现在是 `{rule}:GLOBAL`，所以"秒杀规则的全局额度"和"商品详情规则的全局额度"是分开的。**但同一个规则内部的 GLOBAL 仍然是全局限流，所以依然不建议对高频接口开。**

**热点问题的本质：** Redis 是单线程处理命令的（集群模式下按 key 分片）。**一个高频操作的 key 会成为瓶颈**：
- `GLOBAL` 维度的单 key：所有请求都打到同一个分片 → 该分片的单线程成为瓶颈；
- 但 `RedisRateLimiter` 的注释说明了为什么这个开销是可接受的："全部判定逻辑下沉到 Lua，客户端只负责'发一条命令、读一个返回值'。这样做的直接收益是**每次限流判定只消耗一次 RTT**；如果用 `INCR + EXPIRE` 两条命令，中间的空隙就是竞态窗口，而且 RTT 翻倍——**在秒杀入口这种 QPS 极高的位置，RTT 就是命**。"

**`USER` 维度是最佳的（key 分散 + 有明确语义）：**
- key 分散到不同分片（按 userId 哈希）；
- 每个 key 的 ZSET 最多 `limit` 个 member（5 个）→ **极小**；
- 有 TTL（窗口过期后 key 自动删除）→ **不会堆积**。

**`IP` 维度的隐患：key 数量可能爆炸。** 一次 DDoS 可能产生几百万个不同的源 IP → 几百万个 `rate:limit:{rule}:IP:*` key（修复缺陷 ④ 之后还要乘以规则数）。
**缓解：**
1. **key 必须有 TTL**（滑动窗口的 Lua 里应该带 `PEXPIRE`）→ 窗口过期后自动清理；
2. **`maxmemory-policy noeviction`**（本项目的配置）→ **内存打满时写操作会失败**，`tryAcquireSlidingWindow` 会抛异常 → `RateLimitAspect` 的 `fail-open` 会**放行**（而不是拒绝）。
   **这是一个需要认真想的连锁：** DDoS → IP key 数量爆炸 → Redis 内存打满 → `noeviction` 导致写失败 → 限流组件异常 → **fail-open 放行所有请求** → 限流完全失效！
   **这正好说明了 `fail-open` 的风险**：它不是"无害的降级"，在 DDoS 场景下它会让限流彻底失效。
   **缓解方案：** ① IP 维度的限流可以有独立的 Redis 实例（隔离）；② 在应用层做一层"本地 IP 黑名单"（不依赖 Redis）；③ 用 `maxmemory-policy volatile-lru`（只淘汰带 TTL 的 key）——**但这会让没设 TTL 的库存 key 安全，同时允许淘汰限流 key**（限流 key 被淘汰 = 限流失效，但至少不会 OOM）。
   **本项目选 `noeviction` 是为了保护库存 key（`RedisKeys` 的注释："宁可写失败也不能让库存 key 被淘汰"）——这个选择是对的，但它和限流 fail-open 形成了一个叠加风险。这个风险我会在面试里主动指出。**

**⚠️ 这里还有一个真实的叠加风险（缺陷 ④ 修复前更严重）：** DDoS → IP key 数量爆炸 → Redis 内存打满 → `noeviction` 导致写失败 → 限流组件异常 → **`fail-open` 放行所有请求** → 限流完全失效！

**修复缺陷 ④ 之后，这个链条没有消失**，但因为不同规则的 key 已经隔离，**攻击面上限被"每个规则各自被 DDoS"取代了"一个 key 拖垮所有规则"** —— 至少不可能因为攻击商品详情接口而让登录限流一起失效。

**追问：** 你的滑动窗口 Lua 里是怎么做的？返回什么？
**答：** 逻辑分四步（都在 `sliding_window_rate_limit.lua` 里，由 `RedisRateLimiter` 通过 `redis.execute` 调用）：
1. `ZREMRANGEBYSCORE key 0 (now - window)` —— 清掉窗口外的过期成员（**这是"滑动"的实现**）；
2. `ZCARD key` —— 统计窗口内的请求数；
3. 若 `count >= limit` → **返回 -1**（超限，不加成员）；
4. 否则 `ZADD key now member` → **返回剩余额度**（`limit - count - 1`，`>= 0` 表示放行）。

```java
// 返回 -1 表示超限；>=0 表示放行并带上剩余额度
return result != null && result >= 0;
```

**两个关键细节我会主动说：**
- **必须给 key 设 TTL**（`PEXPIRE`），否则"窗口过期后没人访问的 key"会永久残留（Q89 讲的 IP 维度 key 爆炸问题）；
- **`member` 必须唯一**（`now + "-" + UUID`），否则同一毫秒的多个请求会被 `ZADD` 覆盖，`ZCARD` 计数偏低 → **限流形同虚设**（Q79）。

**"返回剩余额度而不是布尔值"也是一个设计选择：** 未来如果要在响应头里返回 `X-RateLimit-Remaining`，不需要改 Lua 脚本。

> 💡 **面试官意图：** 考"限流 key 本身的资源消耗"以及"key 的命名空间设计"。**能讲清"为什么 key 里必须有规则名"、并说出"这个 Bug 只在'先大额度接口、后小额度接口'时才复现，单接口压测发现不了"——这既证明了代码审查能力，也证明了测试设计的深度。**能进一步推出"noeviction + fail-open 的叠加风险"是更高分的答案。

---

### Q90. `RateLimitDimension` 的 `USER` 维度取不到值时为什么要 `continue` 而不是直接放行或拒绝？

**结论：** 因为"取不到值"意味着**这个维度对本次请求不适用**，应该交给其他维度兜底。

```java
for (RateLimitDimension dimension : rateLimit.dimensions()) {
    String target = resolveTarget(dimension, apiName);
    if (target == null) {
        // 该维度取不到值（例如未登录用户没有 userId），跳过这一维，
        // 由其它维度兜底，而不是直接放行或直接拒绝
        continue;
    }
    boolean allowed = doAcquire(rule, dimension, target);
    if (!allowed) { throw new RateLimitException(message); }
}
```

**为什么 `continue` 是对的：**
| 选择 | 后果 |
| --- | --- |
| **`continue`（本项目）** ✅ | 该维度不适用，用其他维度（比如 IP）继续判定 |
| `return proceed()`（放行） | **一旦有一个维度取不到值，整个限流就失效了** —— 未登录用户可以无限刷 |
| `throw`（拒绝） | 未登录用户会被一律拒绝（这可能是故意的，但应该用鉴权而不是限流来做） |

**具体场景：** 秒杀接口的维度是 `{USER, IP}`。
- 用户已登录 → `USER` 取到 userId，先判 USER，再判 IP；
- 用户未登录 → `USER` 返回 null → `continue` → **只判 IP**。

**但注意：秒杀接口本身有 `UserContext.requireUserId()`，未登录会在 Controller 里就抛 401。** 所以"未登录"这个场景在秒杀上不会真的发生。
**那 `continue` 还有意义吗？** 有——**`AuthController.login` 的限流是按 IP 的**，因为登录时用户还没有 userId：
```java
// 假设 login 的注解是 dimensions = {RateLimitDimension.IP}
```
或者如果同时配了 `{IP, USER}`，登录时 `USER` 取不到 → `continue` → 只判 IP。**这正是我们需要的行为。**

**更精细的一点：`IP` 维度也可能取不到值。** `clientIp()` 在 `RequestContextHolder.getRequestAttributes() == null` 时返回 null（**非 HTTP 上下文调用，比如内部定时任务调用带 `@RateLimit` 的方法**）。这时也会 `continue`。

**如果所有维度都取不到值呢？** 循环体一次都没执行 → `return joinPoint.proceed()` → **放行**。
**这意味着一个"维度配置错误"（比如配了一个总是取不到值的维度）会导致限流静默失效。**
**项目里的防御：** `resolveApiName` 的 `API` 维度总是能取到值（`SeckillController.seckill`），而 `GLOBAL` 维度总是 `"all"`。**所以只要配了 `API` 或 `GLOBAL` 中的任一个，就一定能生效。** 
**这仍然是一个"配置错误会静默失效"的风险**（对应 Q80 的"规则未配置时打 ERROR 但放行"）。我会在面试里说：**生产环境应该在启动时校验"每个 `@RateLimit` 注解引用的规则都存在，且至少有一个维度是 GLOBAL 或 API"**，把配置错误暴露在启动阶段。

**"配置错误静默失效"是一类反复出现的风险。** 本项目在另一个地方做了正确的事——`SeckillStockService.@PostConstruct assertSameSlot()`：**把配置错误暴露在启动阶段而不是运行时**。**同样的思路应该应用到限流配置上。**

**追问：** 那 `doAcquire` 里 `TOKEN_BUCKET` 的 `rate` 默认值是怎么定的？
**答：**
```java
private boolean doAcquire(MallProperties.Rule rule, RateLimitDimension dimension, String target) {
    if (rule.getAlgorithm() == RateLimitAlgorithm.TOKEN_BUCKET) {
        int capacity = rule.getCapacity() != null ? rule.getCapacity() : rule.getLimit();
        double rate = rule.getRate() != null ? rule.getRate() : Math.max(1.0, rule.getLimit() / 10.0);
        return redisRateLimiter.tryAcquireTokenBucket(dimension.name(), target, capacity, rate, 1);
    }
    return redisRateLimiter.tryAcquireSlidingWindow(dimension.name(), target, rule.getLimit(), rule.getWindow());
}
```
**两个默认值的设计：**
- `capacity` 缺省 = `limit`（桶满时能突发 limit 个）；
- `rate` 缺省 = `max(1.0, limit / 10.0)`：**把"窗口内限额"换算成每秒速率**。比如 `limit = 50, window = 1s` → `rate = 5/s`。
  **这个换算对 `window = 1s` 是准确的，但对 `window = 60s` 就不准了**（`limit = 10, window = 60s` → `rate = 1/s`，但正确的应该是 `10/60 = 0.167/s`）。
  **本项目显式配置了 `rate: 0.5`**，所以没走默认分支。**但"用 limit/10 作为缺省速率"是一个会在 `window != 1s` 时误导用户的默认值** —— 我会建议**去掉这个兜底，改成"缺省时必须显式配置 rate，否则启动失败"**，或者**按 `window` 换算：`rate = limit / window.toSeconds()`**。

**这是一个"看起来友好的默认值实际上是陷阱"的例子。** 好的默认值应该是"安全的"或者"强制显式的"，而不是"看起来合理但场景相关"的。

> 💡 **面试官意图：** 默认值的设计是个高级话题。"强制显式好过猜一个值"是成熟的设计判断。

---

## 九、系统设计与容量（5 题）

### Q91. Tomcat 线程池、Hikari 连接池、Redis 连接池，这三个数字之间有什么关系？

**结论：** 它们必须是**有层次、有约束**的，而不是各自独立地"配大一点"。

**本项目配置：**
```yaml
server.tomcat:   threads.max = 500, min-spare = 50, accept-count = 2000, max-connections = 20000
spring.datasource.hikari:  maximum-pool-size = 50, minimum-idle = 10, connection-timeout = 3000
spring.data.redis.lettuce.pool:  max-active = 64, max-idle = 32, min-idle = 8, max-wait = 1000ms
mall.redisson:  pool-size = 64, minimum-idle-size = 8
spring.rabbitmq.listener.simple:  concurrency = 4, max-concurrency = 16
```

**关键的推理链：**

**① Tomcat 500 个线程 vs Hikari 50 个连接，这不是矛盾吗？**
不矛盾，因为**不是每个请求都要访问数据库**：
- 秒杀接口：只访问 Redis + MQ，**不访问 DB**（DB 操作在消费端异步执行）；
- 商品详情：大部分命中 L1/L2 缓存，**不访问 DB**；
- 只有缓存 miss 或写操作才需要 DB 连接。

**500 个线程共享 50 个连接**，意味着**最多 50 个请求能同时做 DB 操作，其余排队（最多等 3 秒）**。这个比例是有意的：**如果 500 个线程都能同时拿连接，那么打到 MySQL 的并发就是 500** —— 单实例 MySQL 的写能力（特别是对同一行的更新）根本承受不了，结果是**大量行锁等待**，吞吐反而下降。

`application.yml` 的注释直接说明了这一点：
> "连接池不是越大越好：50 已经足够打满单实例 MySQL 的写能力，继续加大只会把压力转成 **MySQL 侧的锁等待与上下文切换**。"

**② `connection-timeout = 3000`（3 秒）的意图：**
连接池满了之后，请求最多等 3 秒。**超过 3 秒就抛 `SQLTransientConnectionException`** —— 这是一个**快速失败**的设计：
- 如果设成 30 秒，请求会长时间挂起，Tomcat 线程被占满（500 个线程都用光）→ **整个服务不可用**；
- 3 秒是一种"背压"：连接不够时快速失败，让上游（限流、负载均衡、客户端）感知到压力。

**③ `Tomcat.max-connections = 20000` vs `accept-count = 2000`：**
- `max-connections`：Tomcat 能同时接受的**连接数**（包括 keep-alive 的空闲连接）；
- `accept-count`：当所有线程都在忙时，**等待队列的长度**。队列满了之后新的连接会被**拒绝**（TCP 层面）。

**为什么 `max-connections` 要远大于 `threads.max`？** 因为 HTTP keep-alive 下，一个连接在不传输数据时也占着一个连接槽，但**不占线程**。20000 个连接、500 个线程是合理的比例（一个线程服务多个空闲连接）。

**④ Redis 连接池 64 vs Redisson 64：**
```yaml
# Redisson 只做分布式锁 / 布隆过滤器，数据读写一律走 StringRedisTemplate，
# 因此它的连接参数与 spring.data.redis 分开配置，互不影响。
mall.redisson:
  pool-size: 64
```
**这个"分开配置"很重要：** 如果 Redisson 和 Lettuce 共用一个连接池，那么**分布式锁的等待会阻塞数据读写**（比如缓存重建的锁等待 200ms，期间如果占着连接，其他 Redis 操作就受影响）。
**分开池 = 故障隔离。** Redisson 的锁操作（包括等待中的 `tryLock`）不会占用数据读写的连接。

**⑤ `prefetch = 50` + `max-concurrency = 16` 对连接池的影响：**
16 个消费线程每个在处理时都需要一个 DB 连接 → **最多占 16 个连接**（占 Hikari 50 个的 32%）。
**这给 Web 请求留了 34 个连接。** 如果秒杀后出现"消费者并发 16 全满 + Web 请求也需要 DB"，总需求是 `16 + N ≤ 50`，即 Web 请求最多 34 个并发。**这是一个需要监控的关系**：如果 Web 请求的 DB 等待时间上升，说明消费者抢占了连接。

**总结这张"资源层次表"：**
```
20000 Tomcat 连接
  └─ 2000 等待队列
      └─ 500 Tomcat 工作线程
          ├─ 大部分请求（缓存命中 / 秒杀）不占 DB 连接
          └─ 50 Hikari 连接 ──► MySQL（单实例写能力上限）
          └─ 64 Lettuce 连接 ──► Redis（数据读写）
          └─ 64 Redisson 连接 ──► Redis（锁 / 布隆过滤器）
          └─ 16 MQ 消费者线程（占最多 16 个 Hikari 连接）
```

**追问：** 那怎么知道这些数字配得对不对？
**答：** 靠监控，具体的指标：
- `hikaricp.connections.pending`（等待连接的线程数）：**持续 > 0 说明连接不够**；
- `hikaricp.connections.timeout`（连接超时次数）：**> 0 就是告警**；
- `lettuce` / `redisson` 的连接池指标；
- `tomcat.threads.busy`（忙碌线程数）：**接近 500 说明线程不够**；
- `mall.seckill.lua` 的 P99：反映 Redis 的压力；
- MySQL 侧的 `Innodb_row_lock_waits` / `Innodb_row_lock_time_avg`：**反映行锁竞争**——这是判断"连接池是否过大"的关键指标。

**如果 `Innodb_row_lock_time_avg` 很高而 `hikaricp.connections.pending` 持续为 0，说明连接池太大了**（连接够用但都在争锁）。**这时候应该调小连接池，而不是调大。**

> 💡 **面试官意图：** 这题考"资源层次的推理"。答"都把最大值设大一点"是致命的。能说出"连接池太大反而更慢"和具体的判断指标就是资深。

---

### Q92. 水平扩展时，哪些组件是有状态的？怎么处理？

**结论：** 本项目**所有实例都是无状态的**，这是刻意的设计。有状态的东西全部外置到了 Redis / MySQL / MQ。

**逐个检查：**

| 状态 | 位置 | 多实例问题 | 处理方式 |
| --- | --- | --- | --- |
| 秒杀库存 / 已购集合 / 在途计数 | **Redis** | 无（共享） | Hash Tag 同槽 |
| 商品详情缓存 L1 | **JVM** | 3 秒不一致 | TTL 兜底（Q25） |
| 商品详情缓存 L2 | **Redis** | 无 | — |
| 活动快照 L1 | **JVM** | 5 秒不一致 | TTL 兜底 |
| 本地售罄标记 | **JVM** | 不同步（设计如此） | TTL 10 秒 + 回补时清除 |
| `rebuilding` 集合 | **JVM** | 各实例独立（最多 N 个重建任务） | Redisson 锁 |
| `staleInflightCounter` | **JVM** | 各实例独立计数 | 修复操作幂等 |
| `MessageSendTracker.pending` | **JVM** | ⚠️ **崩溃即丢失** | 对账兜底（Q50） |
| 用户上下文 | **ThreadLocal** | 请求级，天然隔离 | `afterCompletion` 清理 |
| 雪花 ID workerId | **配置** | ⚠️ **必须每实例不同** | 见下 |
| Session | **无**（用 JWT） | 无 | — |

**唯一真正的"有状态"风险是两个：**

**① `MessageSendTracker.pending` 是内存台账，实例崩溃会丢。**
这是**已知并接受的代价**（Q44/Q50）。它的后果是"Redis 库存扣了、订单没生成"，靠对账在 15 分钟内修复。
**如果是本地消息表（Outbox），这个状态就在 DB 里，不会随实例崩溃丢失** —— 但代价是热路径多一次 DB 写。**这个取舍是有意的。**

**② 雪花 ID 的 `workerId` 必须每实例不同。**
MyBatis-Plus 的 `IdWorker` 默认从**机器 MAC 地址 / IP** 推导 `workerId`。多实例部署时：
- **物理机**：MAC 不同 → `workerId` 不同 ✅；
- **容器 / K8s**：如果多个 Pod 拿到相同的 IP（比如都用 host 网络）或者 MAC 相同 → **`workerId` 相同** → **同一毫秒内可能生成重复 ID**！

**这是雪花 ID 在容器环境下的经典陷阱。** 后果：
- 订单表 `PRIMARY KEY (id)` 冲突 → `DuplicateKeyException`；
- 但 `OrderCreationService` 的 catch 是针对 `orderMapper.insert(order)` 的，会抛 `DuplicateOrderException("订单唯一键冲突，疑似重复抢购")` —— **一个 ID 生成问题被误报成"重复抢购"**，排查方向完全错了！

**生产环境的正确做法：**
1. **显式配置 `workerId` / `datacenterId`**（比如从 K8s 的 `POD_NAME` 序号、或者 StatefulSet 的序号推导）；
2. **或者用注册中心分配 workerId**（启动时向 Redis/etcd 申请一个唯一的 workerId，通过心跳续期）；
3. **`IdWorker` 的 workerId 位只有 5 位（0~31）**，所以**单集群最多 32 个实例能安全地生成 ID**。超过 32 个实例必须用 datacenterId（也是 5 位，总共 1024 个组合）或者改用号段模式。

**本项目当前的实现是"依赖 MyBatis-Plus 的默认推导"** —— 我**没有显式配置 workerId**。我会在面试里主动说：
> "这是一个我在单机开发时没暴露、但多实例部署必须处理的问题。当前依赖 MP 的默认推导（基于 MAC/IP），在容器环境下不可靠。生产环境我会从 `POD_NAME` 或环境变量显式注入 `workerId`，并且用**启动时的唯一性校验**（向 Redis `SETNX worker:{id}` 注册，失败就退出）来防止重复。"

**这也解释了为什么 `uk_order_no` 这个唯一索引是有价值的**（Q66）——即使雪花 ID 撞车，唯一索引会拦住重复订单，而不会造成数据错乱。**"多层防御"的价值在故障场景下才体现出来。**

**追问：** 那"无状态"的好处具体是什么？
**答：**
1. **可以任意水平扩展**：加实例就能加吞吐（受限于 DB/Redis 的共享资源）；
2. **可以随意重启/发布**：不需要迁移状态（`kubectl rollout restart` 不会丢数据）；
3. **负载均衡可以任意策略**（轮询、随机、最少连接），不需要会话粘滞（**sticky session 会破坏负载均衡的效果，也会在实例故障时丢掉会话**）；
4. **故障恢复简单**：实例挂了，负载均衡把流量转到其他实例即可。

**代价是"所有共享状态都压到了 Redis/MySQL"** —— 这两个成了瓶颈和单点。**所以无状态架构的真正挑战是"如何让共享层能承受所有实例的聚合压力"** —— 这正是本项目做多级缓存（把压力从 Redis 卸到 JVM）和异步化（把压力从 MySQL 卸到 MQ）的原因。

> 💡 **面试官意图：** "雪花 ID 在容器环境下的 workerId 冲突"是一个真实且高含金量的坑。能主动指出自己没做这件事是极好的信号。

---

### Q93. Redis Cluster 下有哪些坑？你的 Hash Tag 方案有没有副作用？

**结论：** 三个主要的坑：CROSSSLOT、多 key 操作受限、Hash Tag 导致的数据倾斜。

**① CROSSSLOT（本项目已处理）**
所有涉及多 key 的 Lua 脚本，key 必须同槽。见 Q2。

**② 多 key 操作在集群模式下受限**
| 命令 | 集群模式 | 本项目的使用 |
| --- | --- | --- |
| `MGET` / `MSET` | 要求所有 key 同槽（否则 CROSSSLOT） | **未使用**（逐 key 操作） |
| `DEL key1 key2` | 要求同槽 | ✅ **`ProductCacheService.invalidate` 用了 `redis.delete(List.of(cacheKey, nullKey))`，已通过 `{p:N}` Hash Tag 保证同槽** |
| `EVAL`（多 key） | 要求同槽 | ✅ 已用 Hash Tag 保证 |
| `SCAN` | 支持（按节点迭代） | 未使用 |
| `KEYS` | 支持但**会阻塞** | **禁用**（`RedisKeys` 的注释推荐 `redis-cli --scan`） |

**⚠️ 这里踩过一个真实的 Bug（已修复，见第十四节缺陷 ①）：商品缓存的两个 key 曾经没有 Hash Tag。**

修改前的实现：
```java
// ❌ 修改前：两个 key 都没有 Hash Tag
public static String productCache(Long productId)     { return "cache:product:" + productId; }
public static String productNullCache(Long productId) { return "cache:product:null:" + productId; }
```
**在集群模式下：**
- `cache:product:123` 的槽 = `CRC16("cache:product:123") % 16384`；
- `cache:product:null:123` 的槽 = `CRC16("cache:product:null:123") % 16384`；
- **两者几乎肯定不同槽** → 一条 `DEL` 会报 **CROSSSLOT**。

**更麻烦的是"失败是静默的"**：`invalidate` 抛异常后，缓存没被删掉，用户会一直读到旧数据直到 TTL 到期（L2 物理 TTL 30 分钟），日志里只有一行异常。**这类"不报错但数据不对"的问题比直接 500 难排查得多。**

**修复：给商品维度加 Hash Tag，让两个 key 同槽。**
```java
// ✅ 修改后：共用同一个商品维度 Hash Tag
public static String productTag(Long productId) { return "{p:" + productId + "}"; }

public static String productCache(Long productId) {
    return "cache:product:" + productTag(productId);           // cache:product:{p:1001}
}
public static String productNullCache(Long productId) {
    return "cache:product:null:" + productTag(productId);      // cache:product:null:{p:1001}
}
```

**为什么最后选了"加 Hash Tag"而不是我最初倾向的"拆成两次 DEL"？**
| 方案 | 优点 | 缺点 | 最终结论 |
| --- | --- | --- | --- |
| **① 加 Hash Tag（采用）** | 保住一条 `DEL` 的原子性；**语义正确**——这两个 key 本来就应该被同时失效 | 两个 key 落到同一分片（**但商品缓存天然按商品维度分布，不存在"人为聚拢"的热点问题**） | ✅ |
| ② 拆成两次 `DEL` | 无热点顾虑 | 两次 RTT；**中间失败会导致"真实缓存删了、空值没删"的半失效状态**（Q20 讲过这个后果：请求先命中空值直接返回 null） | ✗ |

**关键推翻点：** 我最初判断"这两个 key 的删除不需要原子性"，但**这是错的**——如果只删掉了真实缓存而没删空值缓存，`getDetail` 会先命中 `cache:product:null:{p:N}` 直接 `return null`，**比不删更糟**。所以这里的原子性是**需要的**，Hash Tag 是正确选择。
**教训：判断"要不要原子性"时，必须把"部分成功"的后果推演完，而不是只看"最终会一致"。**

**两处配套的单测（这是修复闭环的关键）：**
```java
@Test
@DisplayName("商品缓存的两个 key 也必须同槽（否则多 key DEL 会静默失败）")
void productCacheKeysMustShareHashSlot() { ... }

@Test
@DisplayName("不同商品的缓存 key 应当分散到不同槽，避免数据倾斜")
void differentProductsSpreadAcrossSlots() { ... }
```
**第一条防回归，第二条防"矫枉过正"**——如果 tag 写成 `{p}`（不带 ID），所有商品会被塞进同一个槽。**两条断言一起，才把 Hash Tag 约束在"同商品同槽、跨商品分散"这个正确区间里。****

**③ Hash Tag 导致的数据倾斜（本项目已规避）**
```java
public static String activityTag(Long activityId) {
    return "{act:" + activityId + "}";
}
```
`RedisKeys` 的注释：
> "注意 tag 里刻意保留了业务语义（`act` 前缀），而不是裸的 `{2001}`：不同业务域的数字 ID 容易撞车（活动 2001 和商品 2001），加上前缀能避免『两个本不相关的 key 被强行放进同一个槽』造成数据倾斜。"

**Hash Tag 的本质副作用：同一个 tag 的所有 key 强制落在同一个节点上。**
- **好处**：多 key 操作、Lua 脚本可以工作；
- **代价**：这个 tag 的所有 key 共享该节点的 CPU/内存/带宽。**如果这个节点的 key 特别大或者 QPS 特别高，就形成热点。**

**本项目的评估：** 每个活动只有 3 个 key（`stock`/`bought`/`inflight`），其中 `bought` 是 Set（大小 = 销量），`stock`/`inflight` 是 String（几十字节）。
**最大的是 `bought` Set**：如果活动卖 10 万件，Set 有 10 万个 member，每个 member 是 userId 字符串（约 10 字节 + Redis 的编码开销）→ 约 **1~2MB**。
**在 512MB 的 Redis 实例、16384 个槽、假设 3 个主节点的集群里**，单个 slot 上 2MB 是完全可接受的。
**但如果做"百万级库存的爆款活动"，就必须考虑分片**（Q30 讨论过）。

**另一个必须注意的点：`RedisKeys.seckillActivity(activityId)` 和 `RedisKeys.seckillResult(orderNo)` 没有 Hash Tag。**
```java
public static String seckillActivity(Long activityId) { return "seckill:activity:" + activityId; }
public static String seckillResult(String orderNo)    { return "seckill:result:" + orderNo; }
```
**这是正确的**：它们都是单 key 操作（`GET` / `SET`），**不需要同槽**，所以不该加 Hash Tag（加了反而增加热点风险）。
**"只有需要多 key 原子操作的场景才加 Hash Tag"——这是一个清晰的判断标准。**

**为什么 `SeckillStockService` 要收敛所有秒杀操作？** 因为只有这 3 个 key 需要同槽，而它们的使用被限制在一个类里（Q11）。**"把 Hash Tag 的需求限制在最小范围内"是这个设计的真正价值。**

**追问：** 那 `RedisKeys.seckillActivity` 和 3 个秒杀 key 会不会需要一起操作？
**答：** 目前**没有**。`getActivitySnapshot` 是单独 `GET`，Lua 脚本里不含它。**如果将来要让 Lua 同时读写活动快照和库存，就必须给它们加同一个 Hash Tag** —— 那会改变 key 的分布，需要评估影响。**这也是为什么"提前想清楚哪些 key 需要在一起"很重要** —— 事后改 tag 意味着所有相关 key 的槽位变化，在集群模式下是**数据迁移**（需要 `CLUSTER SETSLOT` + `MIGRATE`）。

**"Hash Tag 一旦设计错了，改起来的成本很高"——这是需要在设计阶段就想清楚的原因。**

**修复这个 Bug 之后，本项目里的 Hash Tag 使用形成了一条清晰的判断线：**
| Key | 有 Hash Tag？ | 理由 |
| --- | --- | --- |
| `seckill:stock/bought/inflight:{act:N}` | ✅ `{act:N}` | 3 个 key 在同一个 Lua 里一起操作，**必须同槽** |
| `cache:product:{p:N}` / `cache:product:null:{p:N}` | ✅ `{p:N}`（**本次修复新增**） | 2 个 key 在一条 `DEL` 里一起操作，**必须同槽** |
| `seckill:activity:N` | ❌ | 单 key `GET`/`SET`，不需要同槽 |
| `seckill:result:{orderNo}` | ❌ | 单 key，不需要同槽 |
| `lock:product:rebuild:N` | ❌ | 单 key（Redisson 内部也用 Hash Tag 保证自己的多 key 脚本同槽，但那是在它自己的 key 空间里） |

**判断标准一句话：** **"这个 key 会不会和别的 key 出现在同一条多 key 命令里？会 → 加 Hash Tag；不会 → 坚决不加。"** —— 因为 Hash Tag 会强制同槽，**不加是一种性能保护**（让 key 自然地分散到 16384 个槽上）。

> 💡 **面试官意图：** 能发现自己代码里的 CROSSSLOT bug、推演"部分失败"的后果、并用两条互补的单测封住回归区间——这是完整的"代码审查 + 修复闭环"证据。**说清"什么时候不该加 Hash Tag"是高级判断。**

---

### Q94. 监控指标有哪些？每个指标对应哪个设计决策的验证？

**结论：** `MallMetrics` 的类注释直接回答了这个问题——**"这个项目的每一个关键决策都需要一个指标来验证"**。

| 设计决策 | 验证指标 | 怎么判断它有效 |
| --- | --- | --- |
| Lua 扣减没有超卖 | `mall.seckill.result`（按 reason 分桶） | `reason=deduct_success` 的数量 == 库存数 |
| 多级缓存真的提升了命中率 | `mall.cache.access`（按 level/result 分桶） | `level=l1, result=hit` 占请求的比例 |
| 缓存没有压垮数据库 | `mall.cache.access{level=db}` | `db.load` 计数 / 总请求数，理想值 < 1% |
| MQ 没有丢消息 | `mall.mq.event`（按 topic/action） | `send == confirm_ack`，`return == 0`，`consume_success` 有值 |
| 限流是保护还是误伤 | `mall.rate.limit.rejected`（按 api/dimension） | 按 dimension 分桶看是 USER 还是 IP 触发 |
| 限流组件自身故障了 | `mall.rate.limit.degraded` | **> 0 必须告警** |
| 库存对账发现漂移 | `mall.reconcile.drift` + `mall.reconcile.last.drift`（Gauge） | **> 0 必须告警** |
| Redis Lua 的延迟 | `mall.seckill.lua`（Timer，含 P99 直方图） | "衡量 Redis 侧压力的最直接指标" |
| 缓存重建的耗时 | `mall.cache.rebuild`（Timer） | 重建很慢时 `rebuild-lock-wait = 200ms` 会不够 |
| MQ 投递在途积压 | `MessageSendTracker.pendingCount()` | **"生产上必须对这个 Map 的大小做监控告警"** |

**关键的实现细节：**
```java
private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

private Counter counter(String name, String... tags) {
    String key = name + '|' + String.join(",", tags);
    return counters.computeIfAbsent(key, k -> Counter.builder(name).tags(tags).register(registry));
}
```
注释：
> "计数器用 `ConcurrentHashMap` 缓存句柄：Micrometer 的 `registry.counter(name, tags)` 每次调用都要做一次**标签拼接和 map 查找**，放在秒杀热路径上是纯粹的浪费。"

**这个优化在秒杀热路径上是必要的**：`mall.seckill.result` 的埋点在 `SeckillService` 的每个分支上（成功、售罄、重复、未预热、未开始、已结束、MQ失败），每次都要拼 `"mall.seckill.result|reason|sold_out"` 这样的字符串。**缓存在 map 里就变成一次哈希查找**。

**`distribution.percentiles-histogram` 的配置：**
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        mall.seckill.lua: true
```
**这个配置让 Micrometer 为 `mall.seckill.lua` 生成直方图桶**，从而 Prometheus 可以算出服务端的分位数（`histogram_quantile(0.99, ...)`）。
**不配的话只有客户端算的分位数**（`Timer.builder().publishPercentiles(0.99)`），而客户端分位数**无法跨实例聚合**（每个实例的 P99 不能简单平均）。**直方图可以在 Prometheus 侧聚合，这是正确计算全局 P99 的唯一方式。**

**暴露的端点：**
```yaml
management.endpoints.web.exposure.include: health,info,metrics,prometheus,threaddump,heapdump
```
**注意 `threaddump` 和 `heapdump` 被暴露了。** 这在生产环境**有安全风险**（`heapdump` 会下载整个堆内存，里面可能有敏感数据如密码、token）。
**必须配合**：① 管理端点绑定到内网端口（`management.server.port` 与业务端口分离）；② 加上认证/授权；③ 或者在生产 profile 里去掉这两个端点。
**这是我会主动指出的配置风险。**

**追问：** 那"业务指标"和"技术指标"应该怎么区分告警优先级？
**答：**
- **P0（立即告警，可能影响资金/数据）**：`mall.reconcile.drift > 0`、`mall.mq.event{action=consume_dlq}`、`mall.rate.limit.degraded > 0`、`hikaricp.connections.timeout > 0`；
- **P1（关注，可能演变成 P0）**：`mall.seckill.result{reason=mq_failed}` 有值、`mall.seckill.result{reason=not_prepared}` 有值（说明预热失效）、MQ 队列深度持续增长；
- **P2（趋势观察）**：缓存命中率、`mall.seckill.lua` P99、`hicaricp.connections.pending`。

**`reason=not_prepared` 特别值得注意**：它是"Redis 库存 key 不存在"的信号（Q14），意味着**预热失效或 Redis 数据丢失**——虽然是 P1，但它一旦出现就是系统性问题，应该立刻查。

**"每一个设计决策都需要一个指标来验证"这个思路，比"列一堆监控指标"有价值得多。** 它把"监控"从"运维的事"变成了"证明我的设计有效的手段"。

> 💡 **面试官意图：** "你怎么知道系统是健康的"和"你怎么实现的"一样重要（`MallMetrics` 的原话）。能说清"每个指标验证哪个决策"是资深的表现。

---

### Q95. 秒杀系统的容量怎么估算？单机能扛多少？

**结论：** 要分层估算，**瓶颈几乎永远在数据库，不在应用层**。

**分层估算：**

**① Tomcat 层：`threads.max = 500`、`accept-count = 2000`**
- 如果秒杀接口的 RT = 5ms（一次 Lua + 一次 MQ 发送，没有 DB），单线程每秒能处理 200 个请求；
- **500 线程 × 200 = 100,000 QPS**（理论值，实际受限于 Redis 和 MQ）。

**② Redis 层（真正的第一瓶颈）**
- 一次 `EVAL` 包含 5 个 O(1) 命令，单实例 Redis 的处理能力约 **10 万次命令/秒**（简单命令的基准值）；
- 5 个命令 + Lua 解析开销 ≈ 一次 EVAL 相当于 **5~10 个普通命令**的成本；
- 所以 **单实例 Redis 大约能承载 1~2 万次 EVAL/秒**；
- **这是秒杀接口的真正上限。**
- **注意：`mall.seckill.lua` 的 P99 指标就是用来盯这个的** —— P99 上升说明 Redis 接近饱和。

**③ 但实际打不到 Redis 的请求远少于总请求：**
- **本地售罄标记**挡住了售罄后的流量（可能占 90%+）；
- **活动快照 L1**（5 秒 TTL）挡住了"未开始/已结束"的请求，且这些请求完全不访问 Redis；
- **限流**（`5/1s` per USER + IP）挡住了脚本党。

**所以"10 万 QPS 进来，实际到 Redis 的可能只有 1~2 万"。** 这个漏斗是这样的：
```
100,000 QPS（总请求）
  ├─ 限流拒绝：~80%（脚本 + 重复点击）→ 20,000
  │    └─ 本地售罄标记拒绝：~90%（活动已售罄）→ 2,000
  │         └─ 活动时间校验拒绝（未开始/已结束）：大部分情况为 0
  │              └─ 到达 Redis Lua：2,000 QPS ✅ 远低于 Redis 上限
```
**注意这个漏斗的假设是"活动已售罄"** —— 在活动刚开始、库存充足的那一秒，**漏斗的第二层不生效**，此时到达 Redis 的就是 20,000 QPS（限流后的量）。**这仍然在 Redis 的 1~2 万上限附近，是系统的设计工作点。**

**④ MySQL 层（消费者侧）**
- 每次消费要执行：1 次 `INSERT t_mq_message` + 1 次 `UPDATE t_seckill_activity` + 1 次 `INSERT t_order` + 1 次 `INSERT t_order_item` + 1 次 `INSERT t_stock_flow` + 2 次 `SELECT`；
- **关键瓶颈是 `UPDATE t_seckill_activity` 的单行行锁** —— **所有消费者都在同一行上排队**；
- 单行 `UPDATE` 在 InnoDB 上的极限约 **1~2 万 TPS**（取决于 `innodb_flush_log_at_trx_commit`）；
- **但这是"事务"级别**：一个订单事务包含 5 次写，所以实际订单创建速度约 **2,000~5,000 TPS**（单实例 MySQL）。

**⑤ MQ 层**
- RabbitMQ 单节点的吞吐：持久化消息约 **1~5 万 msg/s**；
- 本项目的消息量 = 秒杀成功量 = 活动库存量（几百到几千）。**MQ 完全不是瓶颈。**

**所以最终的容量结论：单实例 MySQL 能创建 2,000~5,000 订单/秒，而秒杀活动通常只有几百到几千件库存，消费压力完全可承受。真正的瞬时压力在"入口"（Tomcat + Redis），而入口的压力被多级缓存和限流大幅削减了。**

**这才是"高并发"的正确理解：不是"每个组件都要扛 10 万 QPS"，而是"让每个请求尽量少经过组件"。** 秒杀接口的 RT 里**没有 DB**，这是设计的核心。

**追问：** 那如果活动库存是 100 万件呢？
**答：** 压力结构就完全变了：
1. **入口**：库存充足，本地售罄标记长时间不生效 → 到达 Redis 的就是限流后的 2 万 QPS，**Redis 满载**；
2. **消费端**：100 万次扣减，按 3,000 TPS 算需要 **333 秒（5.5 分钟）** 才能全部落库 —— **MQ 会积压 100 万条消息**；
3. **`bought` Set**：100 万个 member ≈ 10~20MB，单槽可接受但要注意；
4. **`t_stock_flow`**：100 万行，需要归档策略。

**应对方案：**
- **入口**：把限流调紧（减少无效请求）；用 CDN 或前端做"排队"（限制进入的请求数）；
- **消费端**：`max-concurrency` 调到 16（上限），**水平扩展消费实例**（多个实例消费同一队列）；
- **库存分片**：把一个活动的库存拆成 N 份（每份一行），**减少行锁竞争**，把 3,000 TPS 提升到 N×3,000（但要注意分片间库存不均的问题，需要"跨分片借还"机制）；
- **`bought` 集合分片**：按 `userId % 16` 分片（代价是一人一单的判定变成一次哈希定位）。

**"100 万件库存的秒杀"和"1000 件库存的秒杀"是两个不同的系统。** 我会在面试里强调：**"我做的是'瞬时高并发抢少量库存'的场景；如果是'海量库存的促销'，架构要重新设计（重点是消费侧的分片和削峰）。"**

> 💡 **面试官意图：** 考"能不能分层估算并识别真正的瓶颈"。答"我的系统能扛 10 万 QPS"是危险的；分层次说清"哪里是瓶颈"才是对的。

---

## 十、场景题 / 故障演练（8 题）

### Q96. Redis 挂了，整个系统会怎样？哪些功能还能用？

**结论：** **秒杀主链路不可用（fail-fast）**；缓存链路降级查库；限流降级放行；**对账和监控失效**。

| 功能 | 影响 | 代码依据 |
| --- | --- | --- |
| **秒杀下单** | ❌ **完全不可用**（500） | `redis.execute(seckillDeductScript, ...)` 抛异常 → 接口 5xx |
| 活动快照查询 | ⚠️ L1 命中可用，miss 则回源 DB | `SeckillActivityService.getSnapshot`：L1 → L2(Redis) → DB |
| 商品详情 | ⚠️ L1 命中可用；L2 不可用但**有降级** | `loadWithMutex` 的 `catch (Exception e)`：`"Redis/Redisson 故障时不能让读接口整体不可用，直接降级查库"` |
| 限流 | ⚠️ **降级放行**（fail-open） | `RateLimitAspect`：`metrics.rateLimitDegraded` |
| JWT 黑名单检查 | ❌ 抛异常 → 认证失败 | `LoginInterceptor` → `tokenBlacklist.isBlacklisted(jti)` |
| 登录/注册 | ⚠️ 注册可用（只写 DB）；登录可用（签 JWT）；**登出不可用**（写黑名单） | — |
| 订单查询 | ✅ 可用（走 DB） | `OrderService.queryVO` |
| MQ 消费 | ❌ 不可用（幂等记录写 Redis？不，写 DB） | 实际上 `OrderCreationService` 用 DB，**MQ 消费可用** ✅ |
| **对账任务** | ❌ 不可用（读 Redis 库存） | `SeckillReconcileService.reconcile` |
| **Confirm 超时补偿** | ❌ 不可用（补偿需要写 Redis） | `SeckillCompensationService` |

**注意一个重要的现象：MQ 消费仍然可用！** 因为 `OrderCreationService` 完全依赖 DB（`t_mq_message` / `t_seckill_activity` / `t_order`），不碰 Redis。**所以在秒杀接口已经挂了的情况下，之前投递的消息仍然能被正常消费落库。** 这是一个很好的性质——**Redis 挂掉不会导致"已经抢到的订单丢失"。**

**为什么秒杀接口选择 fail-fast 而不是降级？**
- 降级到 `UPDATE t_seckill_activity ... WHERE available_stock >= 1` 意味着**几万 QPS 直接打 MySQL**，会把 DB 打死，**连带影响订单查询、MQ 消费、对账**——把"秒杀不可用"放大成"全站不可用"；
- 而且语义会混乱：Redis 恢复后，两套库存来源同时生效（Q10 讨论过）；
- **秒杀活动通常有备份机制**：延期、下一场、客服补偿。**短暂不可用是可接受的，全站雪崩不可接受。**

**恢复后会发生什么？**
1. **Redis 数据丢失**（重启且没有持久化）→ 所有库存 key 消失 → Lua 返回 `3`（`NOT_PREPARED`）→ 打 ERROR 日志告警；
2. **但 Redis 的数据是"投影"** —— `SeckillPreheatJob` 会重新预热（从 DB 读 `available_stock`）；
3. **`SECKILL_PREHEATED_SET` 也在 Redis 里丢了** → 启动预热逻辑可能会漏掉一些活动 → 依赖定时预热任务兜底（`preheatUpcoming(ahead)` 会重新发现"即将开始/正在进行"的活动）；
4. **`bought` 集合丢了** → 一人一单的"准入闸门"失效 → **但 `uk_user_activity` 唯一索引会拦住重复下单**（Q66）→ 用户会看到"订单唯一键冲突"并收到补偿。**这是"多层防御"在真实故障中的价值。**

**这正是本项目为 Redis 配 `--appendonly yes` 的原因**：AOF 让 Redis 重启后能恢复大部分数据，大幅缩短"Redis 挂掉后需要重新预热"的窗口。

**追问：** 那怎么预防 Redis 单点故障？
**答：**
1. **Redis Sentinel**（主从 + 自动故障转移）：可以自动切主，但有**数据丢失窗口**（异步复制）；
2. **Redis Cluster**：分片 + 高可用，但**多 key 操作受限**（Q93）；
3. **应用层的韧性**：
   - **所有长生命周期 key 必须有 TTL**，避免"Redis 恢复后带着脏数据"；
   - **启动时校验**（`assertSameSlot`）；
   - **降级路径**要明确（本项目每个 Redis 调用点都想过"失败后怎么办"）；
4. **`WAIT` 命令或 `min-replicas-to-write`**：降低丢写窗口，代价是可用性（Q15 讨论过）。

**本项目用了单实例 Redis（`docker-compose.yml` 是 `redis:7.2-alpine` 单节点），这是演示环境的选择。生产必须是 Sentinel 或 Cluster。**

> 💡 **面试官意图：** 能逐功能列出影响，并说清"为什么秒杀 fail-fast 而缓存降级"是完整答案。能发现"MQ 消费仍然可用"是细节亮点。

---

### Q97. RabbitMQ 挂了，会怎样？秒杀还能下单吗？

**结论：** **秒杀下单会失败**（Redis 库存被扣但消息发不出去 → 补偿回补 → 返回 `SECKILL_SUBMIT_FAILED`）；**已有订单的超时关单暂时失效**，由兜底扫描接管。

**秒杀链路的完整行为：**
```java
try {
    messageSender.sendSeckillOrder(message);
} catch (Exception e) {
    // sendSeckillOrder 内部已经完成了库存补偿，这里只需要告诉用户失败。
    metrics.seckillResult("mq_failed");
    log.error("秒杀消息投递失败，已回补库存 orderNo={} activityId={} userId={}", ...);
    throw new BusinessException(ResultCode.SECKILL_SUBMIT_FAILED);
}
```
`ReliableMessageSender.sendSeckillOrder` 内部：
```java
} catch (Exception e) {
    // 发送本身抛异常（连接不可用、序列化失败等）：
    // 消息确定没有发出去，立刻补偿，不能等 Confirm 超时——
    // 用户等着结果，等 5 秒再失败体验太差。
    metrics.mqEvent("seckill", "send_exception");
    sendTracker.compensateByMessageId(msg.getMessageId(), "生产端发送异常: " + e.getMessage());
    throw new IllegalStateException("消息发送失败", e);
}
```
**注意这里是"立即补偿"而不是"等 Confirm 超时"** —— 因为连接不可用是一个**确定性的事实**（消息肯定没发出去），不需要等 5 秒去确认。**这个判断很重要："确定性失败"和"不确定的超时"要区别对待。**

**恢复后的行为：**
- 消息已经补偿掉了（库存回补、`resultService.markFailed`、`mqMessageService.markConsumeFailed`）；
- 用户可以重新抢（`releaseQualification = true`，资格被回收）；
- **`sendOrderTimeout` 的失败只记日志**（Q53），由 `OrderTimeoutFallbackJob` 兜底。

**已有的"待支付"订单：**
- 延迟消息（30 秒 TTL）**还在队列里**（broker 挂了消息不会丢，持久化在磁盘上）；
- 如果 MQ 挂的时间超过 30 秒，消息 TTL 到期后**投递到 DLX 会失败**（因为 DLX 也是 MQ 的一部分）→ 消息留在延迟队列里；
- MQ 恢复后，这些消息会被重新投递到关单队列；
- **同时 `OrderTimeoutFallbackJob` 每 60 秒扫描 `status = 0 AND expire_time <= now`** → 会先把这些订单关掉；
- MQ 恢复后，延迟消息才到达 → `cancelIfUnpaid` 返回 `notCanceled`（因为订单已经是 `status = 2`）→ 打 `skipped` 指标。**没有重复回补，因为条件更新 + `uk_order_change` 挡住了。**

**这就是"延迟队列"和"兜底扫描"双保险的价值：53 的注释说"消息负责快，扫描负责对，两者缺一不可"。**

**还有一个重要场景：MQ 部分可用（生产者连不上但消费者正常）。**
- 生产者 `convertAndSend` 抛异常 → 补偿 → **秒杀失败**；
- 消费者继续消费队列里的积压消息 → **正常落库**；
- **结果是"秒杀入口挂了但存量订单正常处理"**，这是一个相对健康的降级状态。

**反过来（消费者挂了但生产者正常）：**
- 秒杀接口正常返回"排队中"；
- 消息在队列里积压；
- **用户轮询结果时一直看到"排队中"**（`SeckillResultService` 会持续续 TTL）；
- **最坏情况：`seckill:result:{orderNo}` 的 30 分钟 TTL 到期**，用户查询回落到 DB → 查不到订单 → `SeckillResultVO.queueing`（因为 `queryResult` 在缓存未命中时返回 queueing）。
  **注意：这里有一个问题** —— 用户会永远看到"排队中"，无法知道真实结果。**如果没有 `queueResult` 的超时兜底，这就是一个"用户永远得不到答案"的场景。**
  实际上 `SeckillController.result` 是这样处理的：
  ```java
  try {
      return Result.ok(orderService.queryVO(orderNo, userId));   // 先查 DB
  } catch (BusinessException e) {
      SeckillResultVO vo = seckillService.queryResult(orderNo, userId);
      return Result.fail(ResultCode.SECKILL_QUEUEING, vo.getMessage());
  }
  ```
  **它先查 DB！** 如果订单已经落库（只是结果缓存丢了），`queryVO` 会成功返回。**所以"结果缓存丢失"不会导致查不到订单**（`queryResult` 的注释也说了："回落路径保证'缓存丢失不会导致查不到结果'，只是慢一点"）。
  **但如果消息还在队列里积压（订单真的还没落库）**，用户就只能看到"排队中"。
  **改进方向：** `SeckillResultService` 可以记录 `queueingStartTime`，**超过一定时间（比如 2 分钟）还是排队中，就返回"系统繁忙，请稍后查询"并触发告警**。**这是一个我会在面试里主动提的改进点。**

**追问：** 那怎么监控 MQ 的健康度？
**答：**
1. **`mall.mq.event` 的计数**：
   - `send` vs `confirm_ack`：应该基本相等，差值持续 > 0 说明有未确认的消息；
   - `send_exception` / `confirm_nack` / `return`：**任何 > 0 都要告警**；
   - `consume_dlq` / `consume_non_retryable`：> 0 需要人工介入；
2. **`MessageSendTracker.pendingCount()`**：内存台账的大小，**持续增长说明 broker 失联**；
3. **RabbitMQ 自身的指标**（通过 management API / Prometheus exporter）：`queue_messages_ready`（积压）、`queue_messages_unacknowledged`、`connection_count`；
4. **`requested-heartbeat: 30s`** 让连接故障能在 60 秒内被发现。

> 💡 **面试官意图：** 场景题考的是"能不能推演完整的时序"。能发现"MQ 部分可用"的两种子场景是深度。

---

### Q98. MySQL 挂了，会怎样？哪些请求会失败？

**结论：** **秒杀下单接口仍然可用**（不访问 DB！），但**消费端全部阻塞**，缓存 miss 时读请求失败。

**这是本项目架构最值得说的一个性质：**

| 功能 | 影响 | 原因 |
| --- | --- | --- |
| **秒杀下单（限流通过 + 库存充足）** | ✅ **完全不感知** | 只访问 Redis + MQ，**零 DB 操作** |
| 秒杀结果查询 | ⚠️ 缓存命中可用，miss 则失败 | `SeckillController.result` 先查 DB |
| 商品详情（缓存命中） | ✅ 可用 | L1/L2 |
| 商品详情（缓存 miss） | ❌ 失败 | `loadWithMutex` catch 后 `loadFromDb` 也失败 |
| MQ 消费 | ❌ **全部失败** | `OrderCreationService.createFromSeckill` 需要 DB |
| 活动快照（L1 命中） | ✅ 可用 | 5 秒 TTL 内 |
| 登录（查用户） | ❌ 失败 | `userMapper.selectOne` |
| 对账任务 | ❌ 失败 | 读 `t_seckill_activity` |

**关键推演：MySQL 挂掉时，秒杀接口会持续"接受"请求。**

用户拿到"排队中"（`SeckillResponse.accepted(orderNo)`），消息投递成功到 MQ。**然后 MQ 消费端全部失败**：
```java
} catch (Exception e) {
    if (payload == null) { ... }
    handleRetryable(message, deliveryTag, channel, e);
}
```
`handleRetryable` 会重试 3 次（每次间隔 5 秒，通过延迟队列），**每次都需要 DB** → 全部失败 → `retryCount` 达到 3 → `basicNack(requeue=false)` → **进死信队列**。

**最危险的部分在这里：死信消费者 `SeckillDlqConsumer` 去查订单状态：**
```java
boolean orderExists = orderMapper.exists(...);   // ← DB 挂了，这里也会抛异常
```

**⭐ 这段代码曾经有一个严重的 Bug（已修复，见第十四节缺陷 ②）。**

**修改前**的 catch 分支：
```java
// ❌ 修改前：处理死信失败就 ACK 掉
} catch (Exception e) {
    // 处理死信本身失败：确认掉并告警，由对账任务兜底。
    channel.basicAck(deliveryTag, false);
}
```
**消息被 ACK 掉了（从死信队列删除）！**

**当时的最终状态推演：**
- **Redis**：库存已经扣减（`stock - 1`、`bought` 加入用户、`inflight + 1`）；
- **MQ**：消息已被消费并丢弃（进过死信、被 ACK）；
- **DB**：没有订单；
- **`MessageSendTracker`**：`pending` 里的记录已经在 Confirm 时 `invalidate` 了（因为消息确实投递成功并且路由成功了）→ **内存台账里也没有**。

**所以这个订单会永久悬空** —— **只能靠 `SeckillReconcileJob` 的三个采样周期（15 分钟）发现在途泄漏并修复**，而且用户的购买资格还回不来。

**问题的本质：** `catch (Exception e)` 把两种**性质完全不同**的失败混在了一起：
| 失败类型 | 例子 | 该不该 ACK |
| --- | --- | --- |
| **消息是毒丸**（结构性问题，重投永远不会成功） | 消息体字段缺失、活动被永久删除 | 可以 ACK（或者送 parking queue），重投没意义 |
| **下游瞬时不可用**（重投就能成功） | **DB 连接池耗尽 / DB 主从切换** | **绝不能 ACK**，否则消息永久丢失 |

**原来的实现把两者都当成"毒丸"处理了。** 而实际上"查订单状态都失败"恰恰说明**我们根本没能力判断订单到底有没有落库**——此时丢弃消息是最坏的选择。

**修复后的实现（当前代码）：**
```java
} catch (Exception e) {
    // 处理死信本身失败（最典型的是"数据库不可用，连订单状态都查不了"）。
    //
    // 这里【绝不能】ACK —— ACK 意味着消息被永久丢弃，而这次失败
    // 说明我们根本没能力判断订单到底有没有落库。把它丢掉的结果是：
    // 订单永远不会创建、库存不会归还、用户拿不到任何结果，
    // 而且没有任何痕迹（日志里只有一行 ERROR）。
    //
    // 正确做法是 requeue，等下游恢复后重新处理。
    // 代价是：如果消息本身是"毒丸"（比如结构性非法），会反复重投。
    // 为了不给 broker 造成瞬时自旋，这里先短暂退避再 nack。
    // 生产环境更完善的做法是给死信消息也加 retryCount 头，
    // 超过阈值后投递到一个"人工处理队列"（parking queue）并告警。
    log.error("处理死信消息失败，将 requeue 等待下游恢复。orderNo={} messageId={}",
            payload.getOrderNo(), messageId, e);
    metrics.mqEvent("seckill", "dlq_requeue");
    try {
        Thread.sleep(1000);
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
    }
    channel.basicNack(deliveryTag, false, true);
}
```

**修复里的三个设计细节值得说：**
1. **`requeue = true` + `Thread.sleep(1000)` 退避**：单纯的 `requeue=true` 会让 broker **立刻**重投（零间隔自旋），在 DB 持续不可用时会把 broker 和消费线程都拖满。**1 秒退避把重投频率压到可接受范围**（这也是为什么 `RabbitMQConfig` 里说"立刻重投等于零间隔自旋，只会让下游更没机会恢复"）。
2. **`InterruptedException` 要恢复中断标志**（`Thread.currentThread().interrupt()`）——和 `ProductCacheService` 里的纪律一致（Q29）。
3. **诚实地把"毒丸消息"的代价写进注释**：这是这个方案的真实缺陷，**并且给出了生产环境的完整解法**（retryCount 头 + parking queue）。**知道自己的修复引入了什么新问题，比假装它完美更重要。**

**⭐ 正确的降级方向：** 原来的代码在"不确定"时选择了**丢弃**（破坏性），修复后在"不确定"时选择了**保留 + 重试**（保守）。**"不确定时选择可恢复的那一侧"是所有可靠性设计的通用原则**——和"宁可少卖绝不超卖"（Q4）、"先 DB 后 Redis"（Q64）是同一个思路的三个应用。

**改进 2：对账任务的频率。** 每 5 分钟一次、连续 3 次采样 → **15 分钟的修复延迟**。对于 MySQL 故障这种"大批量悬空"的场景，15 分钟是太久了。
**改进方案：** 加一个**基于量级的快速触发**——如果 `inflight` 超过某个阈值（比如 100），**立即判定为泄漏**，不必等 3 次采样（因为"正常在途 100 以上"的概率极低）。**这是一个"根据异常的量级调整检测灵敏度"的设计。**

**改进 3：MySQL 故障时的秒杀入口应该 fail-fast。**
秒杀接口虽然不需要 DB，但**它产生的消息无法被消费**。所以继续接受请求只是在**积累垃圾数据**。
**正确的做法是在秒杀接口里加一个"DB 健康检查"**（比如 Hikari 的 `getActiveConnections` 或者一个轻量的 `SELECT 1` 探针），**如果 DB 不可用就直接返回"系统繁忙"，不扣 Redis 库存。**
```java
// 伪代码
if (!dbHealthIndicator.isHealthy()) {
    throw new BusinessException(ResultCode.SYSTEM_BUSY);
}
```
**代价：** 每次秒杀多一次健康检查（可以是本地的、无网络开销的，比如定期由后台线程更新一个 `volatile boolean`）。
**收益：** 避免产生大量无法消费的消息和需要 15 分钟才能修复的悬空库存。
**（这一条我还没做** —— 因为缺陷 ② 的修复已经把"消息被丢弃"这个最严重的后果堵住了：现在消息会 requeue，DB 恢复后**能正常落库**，不再需要靠对账去修。**所以这个改进的优先级从 P0 降到了 P1。这是"修复一个缺陷后重新评估其他改进优先级"的例子。**）

**这就是"故障场景推演"的价值：它能暴露平时想不到的设计缺口。** 我会在面试里完整讲这条链路，因为它体现了**"我的接口很快"和"我的系统在故障时行为正确"是两件事**。

**追问：** 那 MySQL 挂了怎么恢复？
**答：**
1. **短期**：`OrderTimeoutFallbackJob` 会在 DB 恢复后立即扫描并关闭超时订单（**但那些订单根本不存在，所以扫不到**）；
2. **真正的修复靠对账**：`SeckillReconcileService` 在 DB 恢复后能读到 `available_stock`，发现 `drift ≠ 0`（Redis 库存比 DB 少），**把 Redis 对齐到 DB** → **库存回来了**；
3. **但用户的购买资格（`bought` 集合）没有被回收**（因为对账只重置库存和在途，不动 `bought`）→ **这些用户失去了购买资格**。
   **改进：** 对账修复时应该考虑"回收在途泄漏对应的用户资格"。**但问题是：对账不知道是哪些用户泄漏了。** 这需要更精细的机制（比如记录 `seckill:pending:{activityId}` 的 userId 列表）。
   **这说明了"内存台账丢失"这个设计选择的真实代价** —— 它会丢失"哪些用户在途"的信息。**如果一个都不丢（本地消息表），对账就能精确地按 messageId 回收资格。**
   **这是一个我需要诚实承认的架构局限。**

**⭐ 也就是说：修复之后，这个"15 分钟悬空"的故障模式已经被消灭了。**
修复前：消息进死信 → 查 DB 失败 → ACK 丢弃 → **永久悬空，只能等对账修 15 分钟**。
修复后：消息进死信 → 查 DB 失败 → **requeue（1 秒退避）** → DB 恢复后消息被重新消费 → **订单正常创建、`inflight` 正常归还**。

**这条链路的对比是"故障场景推演 + 修复闭环"最好的证据：** 我不是"设计时就规避了这个坑"，而是**推演出了它、定位到了具体代码、修掉了它、并且把权衡写进了注释。**

> 💡 **面试官意图：** 这题是本项目最能体现深度的场景题之一。**能推演出"消息进了死信被 ACK 然后永久悬空"、说出"毒丸 vs 瞬时故障"的分类、并给出带退避的 requeue 修复 + parking queue 的后续方案——这是一条完整的资深工程师思维链。**

---

### Q99. 消息重复了会怎样？会不会重复扣库存？

**结论：** **不会重复扣库存**——四层幂等从"消息"到"业务"层层拦截，而且**事务边界**保证了不会出现"消费记录写了但订单没写"。

**重复的来源（"至少一次"语义下都是正常的）：**
1. **broker 投递后没收到 ACK**（channel 断开）→ 重投；
2. **`republish` 成功但 `basicAck` 失败** → 原消息重投 + 重试队列里也有一份（Q38）；
3. **网络抖动导致 confirm 帧丢失** → 超时扫描补偿，但消息其实已经被消费；
4. **人为重放**（运维从死信队列重新投递）。

**拦截过程（以"同一条 `messageId` 被消费两次"为例）：**
```
第 1 次：alreadyConsumed(messageId) → false → insertConsumeRecord → deductStock → insertOrder → ... → COMMIT
第 2 次：alreadyConsumed(messageId) → true  → throw DuplicateMessageException
         → SeckillOrderConsumer catch → metrics "consume_duplicate" → basicAck（不补偿！）
```
**关键：`DuplicateMessageException` 分支不补偿库存** —— 因为库存早就扣对了（第一次成功了）。

**如果两次消费并发（不是串行）呢？**
```
T1: alreadyConsumed → false
T2: alreadyConsumed → false        ← 两个都读到"不存在"是必然的
T1: insertConsumeRecord → 成功
T2: insertConsumeRecord → 撞 uk_message_id → DuplicateKeyException
```

**⭐ 这里曾经有一个真实的 Bug（已修复，见第十四节缺陷 ③）。**

**修改前**的代码：
```java
// ❌ 修改前：insertConsumeRecord 抛出的 DuplicateKeyException 没有被翻译
if (mqMessageService.alreadyConsumed(msg.getMessageId())) {
    throw new DuplicateMessageException(msg.getMessageId());
}
mqMessageService.insertConsumeRecord(msg);   // ← 撞唯一键时抛的是 DuplicateKeyException
```
**`DuplicateKeyException` 会被 `SeckillOrderConsumer` 的 `catch (Exception e)` 捕获**（因为它既不是 `DuplicateMessageException`、也不是 `DuplicateOrderException`、也不是 `NonRetryableMessageException`）→ **落到通用的"可重试异常"分支 → 走 `handleRetryable` → 投递延迟重试队列 → 5 秒后重新消费。**

**后果比"浪费 3 次重试"更隐蔽：**
1. **白走两次延迟重试**（第 2 次重试时 `alreadyConsumed` 才返回 true 被识别为重复）→ 每次都多花 5 秒、多占一个消费者线程；
2. **监控里会出现"消费失败率上升"的假告警** —— 而这**掩盖了真正的问题**。`handleRetryable` 会打 `metrics.mqEvent("seckill", "consume_retry")`，运维看到这个指标上涨，会去查"为什么消费失败"，而实际上消息是重复的，**本来是完全正常的现象**；
3. **在最坏情况下（并发重复量大）会挤占延迟重试队列的容量**，让真正的瞬时故障重试排在后面。

**修复后**的代码（当前实现）：
```java
// ✅ 修改后：把并发冲突翻译成语义明确的 DuplicateMessageException
if (mqMessageService.alreadyConsumed(msg.getMessageId())) {
    throw new DuplicateMessageException(msg.getMessageId());
}
try {
    mqMessageService.insertConsumeRecord(msg);
} catch (DuplicateKeyException e) {
    // 并发场景：两个消费者同时处理同一条消息，都通过了上面的查询，
    // 然后一起走到 INSERT，其中一个撞上 uk_message_id。
    //
    // 必须在这里把它翻译成 DuplicateMessageException。
    // 如果放任 DuplicateKeyException 冒到消费端，它会落进通用的
    // "可重试异常"分支 → 投递延迟重试队列 → 几秒后重新消费 →
    // 这次 alreadyConsumed 返回 true 才被识别为重复。
    // 结果是白白多花两次重试和几秒延迟，还会在监控里制造
    // "消费失败率上升"的假告警，掩盖真正的问题。
    throw new DuplicateMessageException(msg.getMessageId());
}
```

**这个修复的核心认知：**
> **"操作在数据库层面失败了"和"这个操作应该失败"是两件事。** 前者是 `DuplicateKeyException`（一个基础设施异常），后者是 `DuplicateMessageException`（一个业务语义）。
> **异常类型的职责是把"底层发生了什么"翻译成"上层该怎么办"。** 如果翻译缺失，上层就只能靠 `catch (Exception)` 兜底——**而"兜底"通常意味着"按最坏的情况处理"（重试），这在语义上可能是完全错误的。**

**表格对比修复前后：**
| 维度 | 修复前 | 修复后 |
| --- | --- | --- |
| 并发重复消息的处理 | 走"可重试"分支 → 2 次延迟重试 → 进死信 | **一次 `catch` 直接识别为重复 → ACK** |
| 额外耗时 | ~10 秒（2 × 5 秒延迟） | 0 |
| 监控指标 | `consume_retry` 假上涨 + `consume_dlq` 假上涨 | `consume_duplicate`（正确的语义） |
| 死信队列 | 被重复消息污染 | 干净 |

**一个可选的进一步简化（我评估后没有做）：** 去掉 `alreadyConsumed` 预查，**只保留 `insert` + catch**——这样能少一次 `SELECT`（DB 往返），而且消除了 `check-then-act` 的窗口。
**为什么没做：** 预查的价值是**在绝大多数（非并发）重复场景下给出更清晰的日志上下文**，而且它命中时**不会产生任何写操作**（避免了"撞唯一键"带来的 InnoDB 唯一索引锁等待）。**这是"多一次轻量查询"换"更少的锁竞争和更清晰的日志"的取舍** —— 在消费端（非热路径）这个取舍是划算的。
**"简化 = 更正确"只在特定条件下成立**（当简化能消除真实的竞态窗口时）；这里 `insert + catch` 已经提供了正确性，预查只是优化。**所以我没有为了"少一次查询"去删掉它。**

**如果两次消费的是不同的 `messageId` 但同一个用户同一个活动呢？**（比如用户用两个设备抢，两次请求都通过了 Lua）
- 第 1 次：创建订单成功；
- 第 2 次：`alreadyConsumed` false（不同 messageId）→ `insertConsumeRecord` 成功 → `deductStock` 成功（DB 库存够）→ `insertOrder` **撞 `uk_user_activity`** → `DuplicateKeyException` → `DuplicateOrderException`；
- `SeckillOrderConsumer` catch → **补偿库存**（`compensateQuietly`）→ `basicAck`。
**注意：`insertConsumeRecord` 的那条记录不会被回滚！** 因为抛异常后整个事务回滚（`@Transactional(rollbackFor = Exception.class)`），**所以消费记录也被回滚了**。
**这是一个重要的连锁：** 消费记录回滚意味着"这条消息没有被消费过"，如果消息再次投递，会被当作新消息处理——但**它会再次撞 `uk_user_activity`**，再次补偿，**结果一致**（幂等）。
**而且 `SeckillCompensationService` 的第一层（订单存在性检查）会怎么判断？** 它会查 `orderNo = msg.getOrderNo()` —— **第二个消息的 orderNo 是新的（不同消息不同订单号），所以查不到订单** → 通过检查 → SETNX 守卫（新 messageId，所以通过）→ **回补 Redis 库存**。
**这是正确的！** 因为第二次抢购确实在 Redis 扣了一次库存，而它没能创建订单，所以应该回补。

**完整验证：** 用户抢了两次（两设备），Redis 扣了 2 次，DB 只创建了 1 个订单（唯一索引拦住），第 2 次被补偿回补。**最终：Redis 库存净扣 1，DB 库存净扣 1，订单 1 个。完全正确。**

**追问：** 那如果第 2 次的补偿失败了呢？
**答：** `compensateQuietly` 会 `try/catch` 并打 ERROR：
```java
} catch (Exception e) {
    // 补偿失败不能影响消息确认：否则会陷入
    // "补偿失败 → 不确认 → 重投 → 补偿失败" 的死循环。
    // 补偿兜底交给对账任务。
    log.error("补偿执行异常，交由对账任务处理 orderNo={}", payload.getOrderNo(), e);
}
```
**然后消息被 ACK。** 此时 Redis 库存少了 1（多扣的），DB 库存正常。→ **`drift = -1`** → 对账发现并修复 → **库存对齐到 DB**。✅

**"补偿失败不影响消息确认"这个选择非常关键**：如果这里不 ACK 而 `nack(requeue=true)`，会形成**无限重投环**（补偿一直失败、消息一直重投）。
**"让补偿失败，然后交给对账修复"** 比 **"无限重试补偿，把队列堵死"** 好得多。**这是"承认某些失败无法在当前层解决，交给更上层的机制"的成熟判断。**

> 💡 **面试官意图：** 这题能测出对"幂等 + 事务边界 + 补偿"三者交互的理解。**能讲清"`DuplicateKeyException` 是基础设施异常、`DuplicateMessageException` 是业务语义、异常翻译缺失会让上层按最坏情况处理"——并且指出这个缺陷已经被定位和修复——是极强的加分。**

---

### Q100. 缓存和 DB 不一致了，你怎么排查？

**结论：** 按"分层定位 → 量化 → 修复"的顺序，而且**有现成的指标可以直接用**。

**第一步：确认不一致的具体表现**
| 表现 | 可能的层 |
| --- | --- |
| 商品详情显示旧价格/旧标题 | L1（3 秒）或 L2（逻辑过期 3 分钟） |
| 商品下架了还能查到 | L2 物理 TTL 未到（30 分钟）+ 没有主动失效 |
| 明明不存在的商品返回了数据 | L2 里是脏数据（旧版本结构、人工写入） |
| 一段时间内查询全部变慢 | 缓存集体失效（雪崩）或 Redis 压力 |
| 某个商品一直查不到 | 空值缓存残留（Q20 的经典 bug） |

**第二步：用现有指标定位（这是本项目最好的地方——指标是按层设计的）**
```java
metrics.cacheAccess(CACHE_NAME, "l1", "hit" / "miss");
metrics.cacheAccess(CACHE_NAME, "l2", "hit" / "miss");
metrics.cacheAccess(CACHE_NAME, "l2", "null");
metrics.cacheAccess(CACHE_NAME, "bloom", "reject");
metrics.cacheAccess(CACHE_NAME, "lock", "contended" / "wait_success" / "wait_timeout" / "double_check_hit");
metrics.cacheAccess(CACHE_NAME, "db", "load");
metrics.cacheAccess(CACHE_NAME, "rebuild", "dedup" / "success" / "failed" / "rejected");
metrics.recordCacheRebuild(CACHE_NAME, nanos);
```
`loadFromDb` 里的注释："打点：这是判断'缓存到底有没有挡住数据库'的**唯一可信依据**。测试里也直接断言这个计数器——比'看起来变快了'这种主观判断可靠得多。"

**典型的排查路径：**
```
现象：DB QPS 飙升
  → 看 mall.cache.access{level=l1, result=hit} 是否下降
      → 是：L1 命中率掉了（可能是 TTL 太短、maximumSize 太小导致频繁淘汰）
      → 否：看 {level=l2, result=hit}
          → 掉了：L2 失效（可能是 Redis 内存满、key 被淘汰、逻辑过期未重建）
  → 看 {level=lock, result=wait_timeout} 是否上升
      → 是：重建太慢，大量请求降级直查 DB（重建变慢的原因要查 mall.cache.rebuild 的 P99）
  → 看 {level=rebuild, result=rejected}
      → 是：重建队列满了（2048），CallerRunsPolicy 把任务交给了调用线程
```

**第三步：具体的不一致怎么修**

**场景 A：某个商品一直显示旧数据**
1. 直接看 Redis：`GET cache:product:{p:1001}`（注意 key 名带 Hash Tag）→ 有数据？看 `expireAt` 字段（`CacheValue` 里的逻辑过期时间）；
2. 如果逻辑过期了但没重建 → 看 `mall.cache.access{result=rebuild_failed}` 和 `rebuild_rejected`；
3. 手动修：调 `invalidate(productId)`（一条 `DEL` 同时清 L2 的真实缓存和空值缓存，**两 key 同槽，集群下也安全** —— 见 Q93 缺陷 ①）→ 下次请求重建。
4. **如果"手动失效了但用户还是读到旧数据"** → 说明是本实例的 L1 还在（`l1-ttl = 3s` 内），或者集群下失效命令报过 CROSSSLOT。**后者正是缺陷 ① 的症状：缓存的"手动失效"静默失败** —— 排查时应该在日志里搜 `CROSSSLOT` / `RedisSystemException`，如果没有日志，就要怀疑 `redis.delete` 的返回值是否被忽略了。

**场景 B：缓存里有脏数据（结构不对）**
- `parse()` 会 `catch` 反序列化异常并打 WARN `"商品缓存反序列化失败，将删除该 key 以待重建"`；
- **但注意：`parse` 只返回 null，并没有真的删除 key！** 调用方会当作 miss 处理，走到 `loadWithMutex` 去查 DB 并 `writeCache`（覆盖）。
  **所以在"走互斥锁路径"的情况下会被覆盖。但如果是 L1 命中（脏数据在 L1 里），`unwrap` 会抛 `ClassCastException`！**
  **这是一个真实的缺陷**（我会主动指出）：L1 里如果放了 `NULL_SENTINEL` 以外的非 `ProductDetailVO` 对象（不可能）或者类型不匹配，`unwrap` 会抛异常。**但因为 L1 只能通过 `localCache.put(productId, cached.getData())` 写入（类型受控），实际上不会发生。**
  **更真实的隐患是：`parse` 失败后没有删除 key**，如果 Redis 里那条脏数据一直没有被覆盖（比如并发请求都走了 L2 miss 分支但都被限流/异常打断），它会一直存在到物理 TTL 到期。
  **改进：`parse` 失败时应该 `redis.delete(...)`**，明确清理脏数据而不是等着别人覆盖。

**场景 C：所有商品同时失效（雪崩）**
- 检查 `l2-ttl-jitter-ratio` 是否生效（`writeCache` 里的 `ThreadLocalRandom`）；
- 检查是不是"批量预热"导致的大量 key 在同一时刻写入（抖动能缓解但不能完全避免）；
- **根治办法是"预热时也加抖动"**（当前 `writeCache` 已经有抖动了，但如果预热是通过其他路径批量 SET 的就没有）。
  **本项目里商品缓存没有"批量预热"机制**（只有秒杀活动预热），所以这个问题主要在"Redis 重启后大量 key 同时过期"的场景。

**第四步：预防**
1. **主动失效优先于等 TTL**：写 DB 后调 `invalidate()`（Cache Aside 的标准动作）；
2. **延迟双删**：`double-delete-delay = 500ms` 兜住"读请求回填旧值"的竞态；
3. **监控 `mall.cache.access{level=db}`**：它是"缓存有没有工作"的直接证据；
4. **`localCacheStats()`**：暴露 Caffeine 的 `CacheStats`（`recordStats()` 开启），能看 L1 的命中率和淘汰情况。

**追问：** 那如果 L1（Caffeine）里有旧数据，怎么强制刷新？
**答：** `invalidateAllLocal()` 提供全量清空，但它是"大炮打蚊子"（清空所有商品）。
**更精细的做法：** 在管理端提供"按商品 ID 清 L1"的能力——但那需要把"清 L1"变成一个**跨实例的广播操作**（Q25 讨论过 Pub/Sub）。
**本项目的现状：** 单实例下 `invalidate(productId)` 就够；多实例下只能等 3 秒 TTL。
**如果业务真的需要"立即全实例生效"，就必须引入广播机制。** 这也是我会在面试里明确"当前方案的边界在哪"的地方。

> 💡 **面试官意图：** 排查类问题考"你的系统有没有可观测性"，以及"能不能把现象映射到具体的设计层"。本项目的指标设计恰好适合回答这题。

---

### Q101. 秒杀接口的 RT 突然从 5ms 涨到 500ms，你怎么排查？

**结论：** 按"分段计时 → 定位瓶颈层"的顺序，从最外到最内。

**秒杀接口的耗时构成：**
```
① 限流（1 次 Redis EVAL：sliding_window）        ~1ms
② 活动快照（L1 命中 ~0）                          ~0ms
③ 本地售罄标记（Caffeine）                        ~0ms
④ 订单号 + messageId 生成（雪花 ID + UUID）        ~0ms
⑤ Lua 扣减（1 次 Redis EVAL，5 个 O(1) 命令）      ~1ms
⑥ resultService.markQueueing（1 次 Redis SET）     ~1ms
⑦ ReliableMessageSender（1 次 MQ publish）         ~1ms
合计：~5ms（大部分是 3 次 Redis/MQ 的 RTT）
```

**排查步骤：**

**第一步：看已有的指标（本项目有直接证据）**
| 指标 | 如果它涨了，说明 |
| --- | --- |
| `mall.seckill.lua`（Timer，P99） | **Redis 侧慢**（Lua 执行慢 / 网络慢 / Redis 饱和） |
| `mall.rate.limit.degraded` | 限流降级了 → 说明 Redis 异常 → 每一项 Redis 操作都会慢/失败 |
| `mall.cache.access{level=l1, result=hit}` 下降 | 秒杀接口的 RT 不受影响（活动快照是另一个 Cache），但这说明缓存层有问题 |
| `mall.seckill.result` 的 reason 分布变化 | `not_prepared` 增多说明 Redis 数据丢了 |

**`mall.seckill.lua` 是最直接的指标** —— `MallMetrics` 的注释："记录一次 Lua 脚本耗时。**这是衡量 Redis 侧压力的最直接指标**。"

**第二步：如果不是 Redis 慢，看哪一层**
- **TCP/连接层**：`lettuce.pool.max-active = 64`，如果连接池满了，`max-wait = 1000ms` 会等待；
  → 检查 `lettuce` 连接池的 pending/wait 指标（或者通过 `max-wait` 是否频繁触达来判断）；
- **Tomcat 线程层**：`tomcat.threads.busy` 接近 500 → **线程不够**，请求在排队；
- **MQ 层**：`convertAndSend` 是否变慢？看 `mall.mq.event{action=send}` 的速率和 `MessageSendTracker.pendingCount()`；
  → **broker 慢/满时 `convertAndSend` 会阻塞**（AMQP 的写缓冲满了会阻塞，这是一个常见的"RT 暴涨但不报错"的原因）；
- **GC**：ZGC 的停顿；`-XX:MaxRAMPercentage=75` 说明堆是容器内存的 75%；
  → 如果堆快满了，GC 频率上升会导致所有操作变慢；`heapdump` 端点可用（**但生产要移除**）。

**第三步：确认"是不是所有人都慢"**
- **全部实例都慢** → 共享资源问题（Redis / MQ / 网络）；
- **只有一个实例慢** → 该实例的问题（GC、线程池、CPU 争抢、宿主机问题）。

**第四步：如果是 Redis 慢，具体是哪种慢**
| 现象 | 原因 | 对策 |
| --- | --- | --- |
| `mall.seckill.lua` P99 高但平均正常 | **偶发的慢命令阻塞**（有其他大 key 操作、`KEYS`、`SAVE`、AOF rewrite） | 关掉 `KEYS`；用 `SCAN`；检查 `redis-cli --bigkeys`；检查 `slowlog` |
| 所有 Redis 操作都慢 | 网络问题、Redis CPU 饱和、连接池等待 | 看 Redis 的 `INFO commandstats` / `latency` |
| 只有秒杀 Lua 慢 | **Lua 脚本本身变慢** | 检查是否有新的命令加进去了；检查 key 的大小（`bought` Set 是否过大） |
| 定期出现周期性慢 | **AOF rewrite / RDB 快照**（fork 时的 COW 拷贝） | 调整 `save` 策略；用 AOF with `everysec`；增加内存避免 swap |

**⚠️ 一个非常值得注意的原因：Redis 的 `maxmemory` 打满 + `noeviction`。**
```yaml
--maxmemory 512mb --maxmemory-policy noeviction
```
内存满了之后，**写操作会失败并返回 `OOM command not allowed when used memory > 'maxmemory'`**。如果应用没有正确处理，会表现为"偶发的异常 + RT 抖动"。
**检查方式：** Redis 的 `INFO memory` 里的 `used_memory` / `maxmemory`，以及 `evicted_keys`（`noeviction` 下应为 0）。

**第五步：如果是 MQ 慢**
- **broker 的磁盘满了/慢**（持久化消息要写磁盘）；
- **队列积压导致 publisher 被阻塞**（RabbitMQ 的 flow control：`connection.blocked`）；
- 检查 `RabbitMQ` 的 `rabbitmqctl status` 和 management UI。

**我会这样总结：**
> "排查 RT 问题的关键是**分段计时**。本项目的秒杀接口只有 5 个步骤，而且每一步都有对应的指标（限流有 `rate.limit.*`、Lua 有 `mall.seckill.lua`、MQ 有 `mall.mq.event` 和 `pendingCount`）。**没有指标的步骤就是排查盲区** —— 这也是我为什么在每个关键路径上都打了点。"

**如果连指标都没有呢（这是一个更常见的现实）：** 用 **`threaddump`**（项目已暴露）看线程在等什么；用 **`async-profiler`/Arthas** 做火焰图；在本地用 **`wrk`/`JMeter`** 复现。

**追问：** 如果是"平均 RT 正常但 P99 是 500ms"呢？
**答：** 这是完全不同的诊断方向：
- **平均正常 + P99 高** → 少数请求很慢，典型原因是 **GC 停顿 / 定期任务 / 偶发的 Redis 慢命令 / 连接池等待**；
- **平均值本身就高** → 系统性问题（资源不够）。
**诊断 P99 问题必须看分布的形态**：如果 P99 高而 P50/P90 正常，说明是**尾部延迟**，通常和"同步阻塞"有关（比如某个线程持锁、GC、定时的 AOF rewrite）。
**本项目的 `mall.seckill.lua` 配了 `percentiles-histogram: true`**（Q94），所以可以在 Prometheus 里直接查 `histogram_quantile(0.99, ...)`，这是定位 P99 问题的正确方式。

> 💡 **面试官意图：** 排查题的答案质量取决于"你有没有可观测性"以及"能不能分层推理"。能说出"没有指标的步骤就是盲区"是很好的收尾。

---

### Q102. 如果对账任务本身有 bug，会发生什么最坏的情况？

**结论：** **最坏情况是"持续的数据破坏"**——这正是"默认只告警不自动修"的原因。

`SeckillReconcileService` 的注释：
> "自动修复的前提是『对账逻辑本身是对的』。如果对账逻辑有 bug，自动修复会把一个数据错误**放大成持续的数据破坏**。所以生产环境建议先跑一段时间只告警，确认 `drift` 恒为 0 之后再开启 `auto-repair`。"

**最坏的 bug 会长什么样？**

**Bug 1：不变式算错了（比如忘记减在途）。**
```java
int expected = dbAvailable;          // ❌ 忘了 - inflight
int drift = redisStock - expected;
```
- 在有在途请求时，`drift = -inflight`（负数）→ **每次对账都会把 Redis 库存"修"成 `dbAvailable`**（把在途的扣减全部抹掉）；
- 结果：**Redis 库存被持续虚高** → 更多用户通过 Lua → 但 DB 库存不足 → 抛 `NonRetryableMessageException` → 大量消息进死信；
- **实际上不会超卖（DB 挡住了），但会产生大量死信、大量补偿、用户体验灾难。**

**Bug 2：修复方向反了。**
```java
stockService.resetStock(activityId, redisStock, ttl);   // ❌ 把 DB 对齐到 Redis
```
- 每次对账都会**用 Redis 的值覆盖 DB** → **Redis 的漂移被永久固化进 DB**；
- 而且因为 DB 被改了，下次对账 `drift = 0`，**看起来"修好了"**！
- **这是最可怕的情况**：一个错误的修复看起来是成功的。**没有任何指标会告警**（因为修复后 `drift = 0`）。

**Bug 3：修复操作不幂等，多实例同时执行。**
```java
stockService.resetStock(activityId, targetStock, ttl);   // 用的是加法而不是赋值
```
- 如果用 `INCRBY` 而不是 `SET`，多实例并发修复会导致**叠加**；
- `resetStock` 用的是 `SET`（幂等），所以**这类 bug 在当前实现里不存在**。

**Bug 4：在途泄漏的阈值太低（比如改成 1）。**
```java
private static final int STALE_INFLIGHT_THRESHOLD = 3;   // 改成 1
```
- **任何一次采样到"在途 > 0"都会触发修复**（把库存对齐到 DB、在途清零）；
- 在秒杀洪峰期间（在途 = 几百），这会造成**持续的、剧烈的数据震荡**；
- 每次修复都会把正在进行的在途扣减抹掉 → **Redis 库存虚高 → 更多请求通过 Lua → DB 挡住 → 大量补偿**。

**"连续 3 次"这个设计就是为了防这个**（Q48 已经分析过）。

**如何防御"对账自身有 bug"：**

| 手段 | 本项目状态 |
| --- | --- |
| **默认只告警不自动修** | ✅ `auto-repair` 可配置；`reconcileOne(activityId, autoRepair)` 支持"预演" |
| **观测期** | ⚠️ 没有强制的"冷启动期"机制，靠运维纪律 |
| **修复前的快照** | ❌ **缺失** —— 修复时没有记录"修复前的值" |
| **修复的审计日志** | ✅ `log.warn("【库存对账】{} activityId={} 目标库存={}", reason, activityId, targetStock)` |
| **修复的指标** | ⚠️ `reconcileDrift` 计数器有，但**没有"修复次数"的专门指标**（无法区分"发现漂移"和"执行了修复"） |
| **修复的熔断** | ❌ **缺失** —— 如果一个活动在短时间内被反复修复，应该熔断并告警 |

**"反复修复同一个活动"应该是一个强告警信号。** 如果对账在 1 小时内对同一个活动修了 10 次，说明**要么有持续的泄漏源，要么对账逻辑错了**。当前实现里只会每次都告警，**但没有"频率熔断"**。

**我会在面试里给出这个改进清单：**
1. **修复前记录快照**（把 `redisStock` / `inflight` / `dbAvailable` 三元组写进 `t_stock_flow` 的 `remark` 或者单独一张对账表）—— **这是事后追溯的唯一依据**；
2. **加"修复次数"指标 + 频率熔断**（同一活动 1 小时内修复 > N 次就停止自动修并告警）；
3. **强制观测期**（`auto-repair` 开启的第一次运行只记录不修，或者配置一个"生效时间"）；
4. **对账逻辑的单元测试**：用固定的输入（`redisStock=95, inflight=5, dbAvailable=100`）断言 `drift=0`。**这是最基础的防护，而且成本极低。**

**最后我会强调：**
> "**自动修复类的任务，它的风险等级和'直接改生产数据库的脚本'是一样的。** 所以它必须满足：可预演（dry-run）、有审计、有熔断、能回滚。当前实现做到了'可预演'和'有审计'，缺的是'熔断'和'修复前快照'。这是我列在重构清单上的第一项。"

**追问：** 如果修复本身失败了（比如 `resetStock` 抛异常），会怎样？
**答：** `repair` 方法内部有 `try/catch`：
```java
catch (Exception e) {
    log.error("对账修复失败 activityId={} targetStock={}", activityId, targetStock, e);
}
```
**注意：异常被吞掉了，`repaired` 仍然是 `false`，但 `reconcile` 返回的 `note` 可能已经写了"已修复"** —— 这是一个**真实的缺陷**：`repair` 返回 void，调用方无法知道修复是否成功，却把 `repaired = true` 和 `note = "已修复：Redis 对齐到 DB"` 写进了报告。
**后果：** 对账报告的"已修复"是**不可信的**。运维看到"已修复"但实际没修，会在下一轮对账时又看到同样的漂移 —— **这会让人误以为是"漂移反复出现"，而实际上是"修复一直失败"。**

**修法很直接：** 让 `repair` 返回 `boolean`，只有成功才设 `repaired = true`：
```java
repaired = repair(activityId, expected, activity, "库存漂移修复");
note = repaired ? "已修复：Redis 对齐到 DB" : "修复失败，见日志";
```
**同时应该加一个 `mall.reconcile.repair.failed` 计数器并告警** —— "修复失败"比"发现漂移"更严重（因为发现漂移说明检测在工作，修复失败说明自愈能力丧失了）。

**"报告里的字段必须是可信的"** —— 如果 `repaired` 可能是假的，那整个对账报告就失去了作为运维依据的价值。

> 💡 **面试官意图：** 这题考"你有没有想过自己的自动化机制出错的后果"。"最可怕的 bug 是看起来修好了的 bug"这个洞察很值钱。

---

### Q103. 如果秒杀活动只有 1 件库存，10000 个人抢，会发生什么？

**结论：** 这是一个极端场景，能验证系统的**边界行为**。

**逐层推演：**

**① 限流层：** `5/1s` per USER + IP。10000 人（假设都是真实用户、不同 IP）→ **限流不生效**（每人才 1 次请求）。**10000 个请求全部通过。**

**② 活动快照 L1：** 全部命中（预热过了）→ 0 次 Redis/DB。

**③ 本地售罄标记：** 活动开始时库存 = 1，**标记未设置** → 全部通过。

**④ Lua 扣减：** 10000 个请求打同一个 `seckill:stock:{act:N}` key。**Redis 是单线程的**，所以这 10000 个 EVAL 会**串行执行**：
- 第 1 个请求：`stock = 1 > 0` → `DECR` → `stock = 0` → `SADD` → 返回 `0`（成功）；
- 第 2~10000 个请求：`stock = 0` → `return 1`（售罄）→ **并触发 `localSoldOutCache.markSoldOut()`**；
- **一旦本地标记设置成功，后续请求在 ③ 就被挡住，不会到 Redis。**

**⚠️ 关键：本地标记是什么时候生效的？**
```java
if (deductResult == SeckillDeductResult.SOLD_OUT) {
    localSoldOutCache.markSoldOut(activityId);
}
```
第 2 个请求返回 SOLD_OUT 时标记才被设置。**所以前 N 个并发请求（在标记设置之前已经通过 ③ 检查的）仍然会到 Redis。**
**N 有多大？** 取决于"从 `isSoldOut` 检查到 `markSoldOut`"的时间窗口。10000 个请求在这个窗口内可能全部通过检查（如果它们同时到达）→ **10000 个 EVAL 打到 Redis。**

**⑤ Redis 的承载能力：** 10000 个 EVAL，每个约 5 个 O(1) 命令。按 1~2 万 EVAL/秒 计算，**这次洪峰在 0.5~1 秒内完成**。**Redis 能扛住，但会出现一次明显的延迟尖峰**（`mall.seckill.lua` 的 P99 会飙升）。

**⑥ 成功的那 1 个请求：** 投递 MQ → 消费端创建订单 → DB 库存 1 → 0。✅

**⑦ 9999 个失败请求：** 返回 `SECKILL_SOLD_OUT`（`ResultCode`）→ 指标 `mall.seckill.result{reason=sold_out}` 计数 9999。

**这个场景暴露的几个真实问题：**

**问题 1：本地售罄标记的"冷启动窗口"。**
在活动刚开始、还没人返回 SOLD_OUT 的时候，**标记没有设置**，所以那段时间的流量会**全部打到 Redis**。
**改进方案：** 在**预热阶段**如果 `available_stock == 0` 就直接设置标记（当前 `preheatStock` 是 `localSoldOutCache.clear(activityId)`，**无条件清除**）。
```java
public void preheatStock(Long activityId, int availableStock, Duration ttl) {
    redis.opsForValue().set(RedisKeys.seckillStock(activityId), String.valueOf(availableStock), ttl);
    localSoldOutCache.clear(activityId);   // ← 无条件清除！
}
```
**但注意：** `clear` 是必须的（Q14/Q5：预热后库存可能被重置，标记必须清）。**所以正确的逻辑是：**
```java
if (availableStock <= 0) {
    localSoldOutCache.markSoldOut(activityId);
} else {
    localSoldOutCache.clear(activityId);
}
```
**这是一个真实的小改进。** 它解决的问题是"库存为 0 的活动被预热后，所有请求仍然会走一次 Redis"。

**问题 2：1 件库存被 10000 人抢，99.99% 的请求是"注定失败但消耗了资源"的。**
**这是秒杀的本质**——**它就是一个"绝大多数人注定失败"的场景**。
**能做的优化：**
- **前端限流**（按钮置灰、验证码、答题）—— **这是最有效的手段**（把 10000 降到 1000）；
- **排队机制**（前端的队列、Redis 的 ZSET 排队）—— 让用户"有序进入"而不是"同时冲击"；
- **本地售罄标记**（本项目已做）—— 但只在"已经售罄"之后生效。

**问题 3：`SOLD_OUT` 的返回顺序和"公平性"。**
**Redis 单线程 + 请求到达顺序决定了谁能抢到。** 严格来说这不是"先到先得"（网络延迟、连接复用都会影响），但**是确定的**（不会有"两个人同时成功"）。
**如果要严格的公平性，需要序号机制**（Redis `INCR` 发号 + 按号排序）。**秒杀场景通常不需要这么严格**——抢不到就是抢不到。

**我会这样回答：**
> "这个场景在本项目里的表现是：Redis 会承受一次约 1 万次 EVAL 的洪峰（0.5~1 秒内），`mall.seckill.lua` 的 P99 会飙升但不会崩。第 1 个请求成功，第 2 个触发本地售罄标记，后续请求被挡在 Redis 之前。
> **但它暴露了两个可以改进的点**：① 预热时如果库存为 0 应该直接设置本地标记（当前是无条件 clear）；② 本地标记有"冷启动窗口"（前 N 个并发请求仍然会打到 Redis），如果要用更严格的保护可以在预热后先设置一个短时的"慢启动"标记。**这两个都是小改动，收益是削掉一次明显的延迟尖峰。**"

**追问：** 那"10000 个请求有多少真的到达了 Redis"你能算出来吗？
**答：** **不能精确算，因为它取决于"本地售罄标记被设置之前已经通过了 `isSoldOut()` 检查的请求数"** —— 这是一个典型的**竞态窗口大小**问题：
```
T0  第 1 个请求进入 → isSoldOut() = false → 到 Redis → 成功
T1  第 2 个请求进入 → isSoldOut() = false → 到 Redis → 返回 SOLD_OUT
                         ↓
                     markSoldOut()   ← 从这一刻起，新进入的请求被挡住
T2  第 3..N 个请求：如果它们在 T1 之前就已经通过了 isSoldOut() 检查，就都会到 Redis
```
**窗口长度 ≈ "一次 Redis EVAL 的往返时间"（约 1ms）**。在这 1ms 内能有多少请求通过 `isSoldOut()` 检查，取决于并发度：
- **如果 10000 个请求是被 500 个 Tomcat 线程"同时"处理的**，那么最多 **500 个**能在窗口内通过检查（线程数上限）；
- **所以最坏情况是 ~500 个 EVAL 打到 Redis**，而不是 10000 个。

**这个分析说明了一个重要的性质：`localSoldOutCache` 的效果受限于 Tomcat 的线程数** —— 因为"通过检查但还没到 Redis"的请求数不可能超过正在处理的线程数（500）。**这也是 `threads.max = 500` 这个参数在秒杀场景下的另一个作用：它同时限制了"售罄标记生效前的穿透流量"的上界。**

**"用线程数给并发穿透量封顶"——这不是设计时有意的，而是我在分析这个场景时才发现的。** 一个"并发度受限"的系统在很多地方都会意外地获得这种保护。

> 💡 **面试官意图：** 极端场景题考"边界行为"的推演。"本地售罄标记的冷启动窗口"是很少有人能想到的细节。

---

## 十一、项目追问（9 题）

### Q104. 为什么这么设计？有没有更简单的方案？

**结论：** 有，而且**更简单的方案在处理"普通下单"时是正确的**。秒杀这套架构只在"瞬时并发极高 + 可接受最终一致"的场景才划算。

**"更简单的方案"是什么：**
```
同步下单：Lua 扣库存 → 同步写 DB（订单 + 库存）→ 返回结果
```
| 维度 | 同步方案 | 本项目（异步方案） |
| --- | --- | --- |
| 代码量 | **少一半以上**（无 MQ、无补偿、无对账、无内存台账） | 多 |
| 一致性 | **强**（一个事务） | 最终一致（依赖对账） |
| 用户体验 | **直接返回结果** | "排队中" + 轮询 |
| 接口 RT | 受 DB 影响（几十 ~ 几百 ms） | **与 DB 无关**（~5ms） |
| 峰值承载 | 受 DB 行锁限制（**约 2000~5000 单/秒**） | 入口可扛 1~2 万 EVAL/秒 |
| 故障模式 | DB 挂了接口就挂 | **DB 挂了接口还能"接单"**（这是个双刃剑，Q98） |
| 排查难度 | 低（一个事务） | 高（跨 Redis/DB/MQ 三种状态） |

**所以决策的关键是"量级"：**
- **普通下单（每天几千单）**：同步方案，**秒杀这套是纯粹的过度设计**。`SeckillService` 的注释明确说了这一点：
  > "所以这个架构**只适用于秒杀这种『瞬时并发极高、可接受最终一致』的场景**。普通的下单流程应该老老实实同步落库，不要为了『高并发』而过度设计。"
- **秒杀（1 秒 1 万请求抢 200 库存）**：同步方案会在 DB 行锁上排队，**接口 RT 涨到几百 ms，Tomcat 线程被占满，整个服务不可用**（因为线程池被等待 DB 的请求占住了，连查订单的请求都进不来）。

**我要强调的核心判断：**
> **"秒杀的价值不是'更快'，而是'把本来会压垮系统的流量，改造成系统能承受的形态'。"**
> 10000 个请求抢 200 库存，**9900 个是注定失败的**。同步方案下，这 9900 个失败请求每一个都要访问数据库（先扣库存，扣失败才返回）——**用 9900 次无效的 DB 访问换来 1 个结果**。
> 异步方案下，这 9900 个失败请求**在 Redis 里就被拒绝了**（`if stock <= 0 then return 1`），**一次 DB 都不碰**。

**这个"把无效流量挡在 DB 之外"才是异步架构的真正价值。** 而不是"异步更快"。

**追问：** 那有没有介于两者之间的方案？
**答：** 有，几种中间形态：
1. **同步扣 Redis + 异步落库，但接口等待一小段时间**（比如 200ms）：
   - 大部分订单在 200ms 内落库 → **直接返回结果**（体验好）；
   - 超时就返回"排队中"（降级）；
   - **代价**：接口要等 MQ 消费完成，需要一个"结果通知"机制（Redis 的 `BLPOP` 或者轮询结果缓存）。**这是"长轮询"模式**，能覆盖 90% 的场景但复杂度不低。
2. **同步扣 Redis + 同步落库，但把 DB 写做成"批量提交"**：
   - 收集一小批（比如 50 个）订单后一次性批量插入；
   - **代价**：用户要等"批次攒满"（最多几十 ms），且事务边界变复杂（一个事务失败影响 50 个订单）。
3. **同步扣 Redis + 同步落库，但用"库存分片"降低行锁竞争**：
   - 把一个活动的库存拆成 N 行；
   - **代价**：分片间库存不均，需要"借还"机制。

**本项目的选择是"纯异步 + 结果轮询"**，因为：① 秒杀的用户**心理预期就是"抢完等通知"**（不是下单立刻看到结果）；② 实现最简单（不需要长轮询的信令机制）；③ 故障模式最清晰（库存权威在 DB，Redis 是投影）。
**如果产品要求"秒杀也要立刻返回结果"，我会选方案 1（长轮询），代码增量主要是"结果通知"和"超时降级"两块。**

> 💡 **面试官意图：** "有没有更简单的方案"是在测你会不会过度设计。**能明确说出"这套架构不该用在普通下单上"是决定性的一句话。**

---

### Q105. 如果重做，你会改什么？

**结论：** 我会改这些地方，按优先级排序。**其中 3 项已经从"待改"变成了"已改"**（见第十四节的缺陷 ①③④）—— 我会把已修的划掉、只讲剩下的。

**✅ 已经改完的（从"改进清单"升级成"缺陷修复闭环"，详见第十四节）**

| 原改进项 | 状态 | 怎么修的 |
| --- | --- | --- |
| 商品缓存两 key `DEL` 的 CROSSSLOT | ✅ **已修（缺陷 ①）** | 加 `{p:N}` Hash Tag 让两 key 同槽 + 2 个槽位单测 |
| 死信消费者在 DB 不可用时 ACK | ✅ **已修（缺陷 ②）** | 改为 1 秒退避 + `nack(requeue=true)` + `dlq_requeue` 指标 |
| `insertConsumeRecord` 并发冲突走错分支 | ✅ **已修（缺陷 ③）** | `catch (DuplicateKeyException) → DuplicateMessageException` |

**⭐ 注意我当时对第 1 项的判断是错的**：我以为"这两个 key 的删除不需要原子性，拆成两次 `DEL` 更简单"，**但推演"部分成功"的后果后发现——只删真实缓存而没删空值缓存，会让 `getDetail` 先命中空值标记直接返回 null，比不删更糟。** 所以最终选了 Hash Tag 而不是拆分。**这是"改进清单也需要推演后果"的例子。**

**P0（正确性问题，必须改）**

**1. 秒杀接口加 DB 健康检查，避免 MySQL 故障时积累垃圾。**（Q98）
**注意：这一条在缺陷 ② 修复后优先级已经从 P0 降到 P1。** 因为缺陷 ② 修好后，DB 不可用时消息会 `requeue` 而不是被丢弃，**DB 恢复后能正常落库** —— 不再需要靠对账修 15 分钟。所以现在加健康检查的收益是"减少无效的消息堆积和重投压力"，而不再是"避免永久悬空"。
**修法：** 秒杀入口加一个本地 `volatile boolean dbHealthy`（由后台线程定期探测），不健康就 fast-fail，**不扣 Redis 库存**。**代价是一次本地判断（纳秒级）。**

**2. 死信消息加 `retryCount` 头 + parking queue。**（Q98 修复时留下的 TODO）
缺陷 ② 的修复引入了"毒丸消息会反复重投"这个新代价（修复时已经写进了注释）。**完整的解法是**：给死信消息也加 `retryCount` 头，超过阈值后投递到一个"人工处理队列"（parking queue）并告警 —— **这样既保住了"瞬时故障可恢复"，又限制了"毒丸消息无限重投"。**
**代价：** 需要一个新的交换机和队列，以及一套"parking queue 的消息由谁看、怎么重放"的运维流程。**这是缺陷 ② 修复的自然延续，也是它没做完整的地方。**

**P1（性能/资源问题）**

**3. 黑名单查询加本地缓存。**（Q83）
当前每个请求都要查一次 Redis（`tokenBlacklist.isBlacklisted(jti)`），**这削弱了 JWT 的"无状态"优势**。
**修法：** 用 Caffeine 缓存"不在黑名单"的结论，TTL 5 秒。**代价：登出后最多 5 秒内 token 仍有效。**

**4. `t_mq_message` 需要归档策略。**（Q77）
每天几百万条消息，一个月上亿行。
**修法：** 定期归档 `status = 1 AND create_time < now - 7d` 的记录到历史表；或者按 `create_time` 分区。

**5. 把 3 个已修缺陷里的 2 个补上自动化回归测试。**（第十四节"测试盲区"）
缺陷 ① 和 ④ 已经有自动化回归测试，但：
- **缺陷 ② 只有手工演练** → 需要 Testcontainers / Toxiproxy 做"可编程停止 DB"的故障注入；
- **缺陷 ⑤ 只有人工观察日志** → 需要断言"两个调度器不是同一个 Bean"、"`@Scheduled` 用的是 4 线程那个"。

**P2（工程规范/可维护性）**

**6. `idx_user_create(user_id, create_time)` 改成 `idx_user_create(user_id, id)`。**（Q71）
因为查询是 `WHERE user_id = ? ORDER BY id DESC`，用 `id` 做第二列可以完全走索引（无 filesort）。

**7. 时间统一用注入的 `Clock`。**（Q76）
当前 `create_time` 走 DB 默认值，`expire_time` 走应用时间——**两个时钟源**。应该全部用 `Clock`。

**8. 雪花 ID 的 `workerId` 显式配置 + 启动时唯一性校验。**（Q92）
当前依赖 MyBatis-Plus 的默认推导（基于 MAC/IP），**在容器环境下不可靠**。

**9. 登录接口消除时序侧信道。**（Q84）
用户不存在时也执行一次 BCrypt 校验（对固定的假 hash），让两条路径耗时一致。

**我会这样总结：**
> "我把改动分成了三类：**P0 是'会造成数据错误或永久悬空'的，P1 是'在特定部署形态下会出问题'的，P2 是'工程规范'的。**
> **最值得说的是：原来列在 P0 的三项已经全部修掉了**（第十四节的缺陷 ①③④），而且**修完之后我重新评估了剩余项的优先级** —— 比如秒杀入口的 DB 健康检查从 P0 降到了 P1，因为缺陷 ② 的修复已经堵住了最严重的后果。
> **"修复一个缺陷之后重新评估其他改进的优先级"** 这件事本身，比清单上的项目更重要。"

**"按优先级分类 + 说清每一条的后果"比"列一堆改进点"有价值得多。** 而且**主动区分"性能问题"和"正确性问题"**、并说明"已修的和待修的"，是非常成熟的信号。

**追问：** 如果只能再改一个地方，你改哪个？为什么？
**答：** **死信消息的 `retryCount` 头 + parking queue。**
**理由：** 因为缺陷 ② 的修复是**用"保留 + 重试"换掉了"丢弃"**，方向是对的，但它引入了一个新的、明确的代价：**毒丸消息会无限重投**。我在修复时把这个代价写进了注释，但**没有把它解决掉** —— 所以它是当前系统里**唯一一个"我知道、写在注释里、但还没关闭"的风险**。
**实现：** 死信消息的 header 里加 `retryCount`，在 `SeckillDlqConsumer` 的 catch 分支判断：
- `retryCount < N`（比如 5）→ `nack(requeue=true)`（带退避）；
- `retryCount >= N` → 投递到 `mall.seckill.order.parking.queue` + 打 ERROR 告警。
**代价：** 一个新的交换机/队列 + 一套运维流程（谁看 parking queue、确认后怎么重放）。**收益：把"无限重投"变成"有限重投 + 人工兜底"。**

**⭐ 这条回答的额外价值：** 它展示了**"修复引入了新问题 → 我识别到了 → 我把它记在注释里 → 它成为下一优先级"** 这个完整的工程闭环。**能讲出"我的修复有什么副作用"，比讲"我修好了"更可信。**

> 💡 **面试官意图：** 这题几乎必问。**答"没什么要改的"是致命的；答"我会加 Redis 集群/分库分表"（无脑上技术）也是差的。** 最好的答案是"具体的、有优先级的、有后果分析的问题清单"——**而且能说出"哪些已经修完了、修完之后我怎么重新排优先级"。**

---

### Q106. 你怎么证明它是对的？

**结论：** 四个层面——**并发测试断言、指标验证、对账收敛、故障演练**。

**① 测试（最直接的证据）—— 共 43 个用例，8 个测试类**
`SeckillStockConcurrencyTest`：800 并发抢 200 库存，断言 4 条（Q13）。用 Java 21 虚拟线程真并发。
`ProductCacheTest`（6）、`OrderIdempotencyTest`（6）、`OrderTimeoutTest`（6）、`AuthFlowTest`（7）、`RedisRateLimiterTest`（6）、`RedisClusterSlotTest`（7）、`SeckillStockConcurrencyTest`（5），外加 `BaseIntegrationTest` 测试基类。
**关键：测试断言的是"数据结果"而不是"性能"。** `ProductCacheService.loadFromDb` 的注释："测试里也直接断言这个计数器——比'看起来变快了'这种主观判断可靠得多。"

**⭐ 而且其中 3 个用例是真实缺陷的回归测试：**
| 用例 | 防的缺陷 |
| --- | --- |
| `RedisClusterSlotTest#productCacheKeysMustShareHashSlot` | 缺陷 ①：商品缓存两 key 不同槽 → 集群下 `DEL` CROSSSLOT |
| `RedisClusterSlotTest#differentProductsSpreadAcrossSlots` | 缺陷 ① 的反面：tag 不带 ID 会导致全塞一槽 |
| `RedisRateLimiterTest#differentRulesDoNotShareQuota` | 缺陷 ④：限流 key 缺规则名 → 规则间串额度 |

**"43 个用例里有多少个是被真实 Bug 催生的"，比"总共有多少个用例"更能说明测试的有效性。** 完整清单和修复过程见第十四节。

**② 指标验证（运行时的证据）**
| 设计决策 | 指标 | 期望值 |
| --- | --- | --- |
| 不超卖 | `mall.seckill.result{reason=deduct_success}` | == 活动库存 |
| 缓存有效 | `mall.cache.access{level=db, result=load}` / 总请求 | < 1% |
| 消息不丢 | `mall.mq.event{action=send}` vs `{action=confirm_ack}` | 基本相等 |
| 对账一致 | `mall.reconcile.drift` | **恒为 0** |
| 限流有效 | `mall.rate.limit.rejected` 按 dimension | 能看到 USER 和 IP 都有 |

**③ 对账收敛（最终一致性的证据）**
`SeckillReconcileService` 的不变式 `Redis 库存 == DB available_stock - 在途` 是一个**可自动验证的性质**。
**在生产环境跑一段时间，`mall.reconcile.drift` 恒为 0，就证明了"秒杀扣减 / 消息投递 / 订单落库 / 超时关单 / 补偿"这五条路径的库存变化是自洽的。**
这是我觉得最有力的证据：**它不是一个测试用例，而是一个持续运行的不变式检查。**

**④ 故障演练（我列了但没全部做的）**
| 演练 | 预期行为 | 项目状态 |
| --- | --- | --- |
| 停 Redis | 秒杀 fail-fast；缓存降级查库；限流 fail-open | 可演练（`docker compose stop redis`） |
| 停 RabbitMQ | 秒杀返回 `SECKILL_SUBMIT_FAILED` + 库存补偿；关单由兜底扫描接管 | 可演练 |
| 停 MySQL | 秒杀仍可用；消费失败进死信；**死信消息 requeue 等待恢复**（缺陷 ② 修复后） | ✅ **手工演练过**（正是这次推演/演练发现了缺陷 ②） |
| 杀进程（模拟崩溃） | 对账在 15 分钟内修复在途泄漏 | 可演练 |
| 重复投递消息 | 幂等拦截，无重复订单 | 可用 MQ 控制台手动 republish |
| 并发抢购 | 800/200 断言 | ✅ **已自动化** |
| 先逛商品再点秒杀 | 秒杀限流不被商品详情的额度影响 | ✅ **已自动化**（缺陷 ④ 的回归测试） |

**我会诚实地说明现状：**
> "① ② ③ 我做了，④ 做了一部分：**并发正确性和缺陷 ①④ 的回归已经自动化**，但**中间件故障演练仍然是手工的**（缺陷 ② 就是靠一次手工的 MySQL 停机演练 + 时序推演发现的）。
> **如果要说这个项目最薄弱的证据环节，就是'缺陷 ② 和 ⑤ 没有自动化回归测试'。** 理想情况是用 **Testcontainers / Toxiproxy / Chaos Mesh** 做可编程的故障注入：停掉 DB、断言死信消息没有被 ACK、恢复后断言消息被正常消费。**这是我会在下一步补的第一件事**（列在 Q105 的 P1）。"

**"证明"的三个层次（这个框架很有价值）：**
1. **单元/集成测试**：证明"给定输入，输出正确"；
2. **监控指标**：证明"在真实流量下，行为符合预期"；
3. **对账不变式**：证明"系统状态始终自洽"；
4. **故障注入**：证明"在异常情况下，行为符合预期"。

**大部分候选人只能给出第 1 层。能给出第 3 层（把不变式变成持续运行的检查）是非常强的信号。**

**追问：** 那你怎么知道"对账逻辑本身是对的"？
**答：** **这是最根本的问题**（Q102 讨论过）：
- **用固定的输入做单元测试**：`redisStock=95, inflight=5, dbAvailable=100` → 断言 `drift = 0`；`redisStock=96` → 断言 `drift = 1`；
- **观测期**：先跑 `auto-repair=false`，观察 `drift` 是否恒为 0。**如果对账逻辑有 bug，那么在有真实流量的情况下它一定会持续报出漂移**（因为不变式是错的）→ 会被发现；
- **反向验证**：手动制造一次漂移（`redis-cli DECR seckill:stock:{act:N}`），看对账能不能发现并修复。**这是"验证验证器"的标准做法。**

**"验证验证器（verify the verifier）"是一个成熟的思路**：任何自动化的机制（对账、熔断、重试）都必须先被验证过，否则你只是在相信它。**项目里 `reconcileOne(activityId, autoRepair)` 支持"只观测不修复"，就是为这个设计的。**

> 💡 **面试官意图：** "你怎么证明它是对的"是区分"做过项目"和"抄过项目"的终极问题。**能说出"对账不变式是持续运行的证明"和"验证验证器"就是满分。**

---

### Q107. 这个项目的量级有多大？你压测过吗？

**结论：** 我会**如实说清楚"测试到了什么量级"和"没有压测到的部分"**，而不是编一个数字。

**我实际验证过的（自动化测试，43 个用例）：**
- `SeckillStockConcurrencyTest`：**800 并发虚拟线程抢 200 库存**，断言 4 条全部通过；
- `oneUserCanOnlyBuyOnce`：同一用户 **500 并发**，断言成功 1 次、重复 499 次；
- 另外还有 **3 个缺陷回归测试**（商品缓存同槽 × 2、限流规则隔离 × 1，见第十四节）；
- 这些是**正确性验证**，不是性能验证。

**我估算的量级（未压测，基于组件能力推导）：**
| 环节 | 估算能力 | 依据 |
| --- | --- | --- |
| 秒杀接口（Tomcat） | 500 线程 × (1000/5ms) = **100,000 QPS** | `threads.max = 500`，RT ≈ 5ms |
| Redis（Lua 扣减） | **10,000 ~ 20,000 EVAL/s** | 每次 EVAL 含 5 个 O(1) 命令 |
| MQ | **10,000 ~ 50,000 msg/s** | RabbitMQ 持久化消息的常规吞吐 |
| MySQL（订单创建） | **2,000 ~ 5,000 单/s** | 一个事务 5 次写 + 单行行锁竞争 |
| **系统整体** | **受 MySQL 消费能力约束** | 因为库存量通常 << 这个数，实际不受限 |

**我会明确说清楚"我没有做的事"：**
> "我**没有做过端到端的压力测试**（比如用 JMeter/wrk 打 1 万 QPS 跑 10 分钟，看 P99、错误率、资源水位）。
> 我做的验证是'**正确性**'而不是'**性能**'：并发测试断言了'800 并发抢 200 库存，成功恰好 200'，这证明了**不超卖**，但没有证明**在 1 万 QPS 下的 RT 分布**。
> **如果要补齐，我会这样做：** ① 用 `wrk` 或 `JMeter` 对秒杀接口做阶梯压测（1k → 5k → 10k QPS），记录每个量级下的 P50/P95/P99、错误率和 Redis/MySQL 的资源水位；② 观察 `mall.seckill.lua` 的 P99 作为"Redis 是否饱和"的信号；③ 找出真实的拐点（QPS 上升但吞吐不再增长的那个点），**这个拐点才是系统的真实容量**。"

**⚠️ 一个重要的诚实点：** 如果面试官问"QPS 是多少"，而我给的是**估算值**，我必须明确标注"这是基于组件能力的估算，不是实测值"。**编一个"实测 10 万 QPS"会在追问下立刻崩塌**（"你的压测环境是什么？几台机器？什么规格？P99 是多少？"）。

**"我知道我的系统能做什么，也知道我不知道什么"** —— 这比一个漂亮的数字有价值得多。

**追问：** 那你怎么知道 MySQL 的 2,000~5,000 单/s 这个估算？
**答：** 基于几个已知事实：
1. **单行 `UPDATE` 的 InnoDB 极限**：一次简单的主键 `UPDATE` + `COMMIT`（`innodb_flush_log_at_trx_commit=2` 时只写 OS cache，不 fsync）大约 **0.1~0.5ms**；
2. **但秒杀的事务包含 5 次写 + 2 次读**，所以一个事务约 **1~2ms**；
3. **单行行锁串行**：所有消费者都在 `t_seckill_activity` 的同一行上排队 → **理论极限 = 1/单事务耗时 ≈ 500~1000 TPS**；
4. **加上其他表的写是并行的**（不同行），所以实际可能在 **2,000~5,000 TPS**（取决于事务的临界区有多长）。

**这里最关键的认识是：`UPDATE t_seckill_activity ... WHERE id = ?` 是串行的**（所有订单都扣同一行）。**这是整个消费端的硬瓶颈。**
**所以 `max-concurrency = 16` 是过大的** —— 16 个消费者在同一行上排队，**除了增加锁等待和连接占用，不会提高吞吐**。**这是一个我会指出的配置问题：`max-concurrency` 应该根据"单行 UPDATE 的 TPS"来定，而不是拍一个 16。**
**真正的解法是"库存分片"**（把一行的库存拆成 N 行）——**但那是另一个架构了。**

**这个分析展示了"从原理推导容量"的能力，而不是背数字。**

> 💡 **面试官意图：** 压测是简历项目的常见虚报点。**诚实说明"没压测" + 给出推导方法 + 指出瓶颈在哪一层**，比编数字强太多。

---

### Q108. 这套架构的投入产出比怎么样？值得吗？

**结论：** 对于"秒杀"这个具体场景**值得**；如果换成"日均几千单的普通商城"，**完全不值得**。

**成本侧（这套架构比同步方案多出来的东西）：**
| 组件/逻辑 | 代码量 | 运维复杂度 |
| --- | --- | --- |
| MQ 拓扑（8 个交换机/队列 + Bindings） | ~150 行 | 需要运维 MQ |
| 可靠投递（Confirm/Return/超时扫描/内存台账） | ~350 行 | 需要监控 `pendingCount` |
| 消费端异常分类 + 死信 + 延迟重试 | ~200 行 | 需要监控死信队列 |
| 补偿服务（三层防重） | ~100 行 | — |
| 对账服务 + 定时任务 | ~180 行 | 需要监控漂移 |
| 在途计数（Lua + 归还 + 检测） | ~80 行 | — |
| **合计** | **~1,000+ 行** | **2 个额外的中间件 + 3 个新的故障模式** |

**收益侧：**
| 收益 | 量化 |
| --- | --- |
| 秒杀接口 RT | 从"受 DB 影响（几十~几百 ms）"降到 **~5ms** |
| 峰值承载 | 从 **2,000~5,000 单/s**（受 MySQL 行锁）提升到 **入口 1~2 万 EVAL/s** |
| 失败请求的 DB 消耗 | 从 **N 次**（每个失败请求都查 DB）降到 **~0**（Redis 拒绝） |
| 系统可用性 | 秒杀洪峰不再影响其他业务（库存/订单查询仍可用） |

**关键的量化对比（10000 请求抢 200 库存）：**
| 方案 | DB 操作次数 | 接口 RT | Tomcat 线程占用 |
| --- | --- | --- | --- |
| **同步** | 10000 次 `UPDATE`（成功 200 + 失败 9800） | 受行锁排队影响，**可能几秒** | **500 线程全部被占满** → 服务不可用 |
| **异步（本项目）** | 200 次（只有成功的订单落库） | **~5ms** | 快速释放，几乎不占用 |

**"9900 次无效的 DB 操作"就是这套架构存在的全部理由。** 它不是为了"更快"，而是为了"**不让注定失败的流量消耗关键资源**"。

**决定性的一句话：**
> "**秒杀这个场景的特点是'失败率极高'（10000 个人抢 200 件，98% 是失败的）。同步方案下，每个失败者都要花钱（访问 DB）才能知道自己失败了；异步方案下，失败者在 Redis 就被拒绝了，一分钱不花。**
> 所以这套架构的价值 ≈ **失败请求数 × 单次 DB 访问成本**。当这个乘积足够大（秒杀场景下就是足够大），就值得付那 1000 行代码和两个中间件的代价。
> **如果失败率很低（比如正常下单，99% 会成功），这个乘积接近 0，这套架构就纯粹是自找麻烦。**"

**这个"用失败率 × 成本来判断是否值得"的框架，比"高并发就要上 MQ 削峰"这种口号有价值得多。** 它让"要不要用这套架构"从一个技术偏好变成了一个可以计算的决策。

**追问：** 那维护成本呢（团队的运维负担）？
**答：** 这是一个**非技术的现实因素**，而且经常比技术选型更重要：
- **三个中间件（MySQL/Redis/MQ）意味着三份运维负担**：监控、备份、升级、容量规划、故障处理；
- **故障模式的组合爆炸**：Redis×MQ×DB 的组合故障有 8 种，每一种都要想清楚行为（Q96/Q97/Q98）；
- **排查复杂度**：一个"用户抢到了但没订单"的问题，需要同时看 Redis、MQ、DB 三处的状态。
**所以如果团队规模小、没有成熟的中间件运维能力，这套架构的"隐性成本"会远高于那 1000 行代码。**
**一个更务实的中间方案是：** 保留"Redis 预扣 + 异步落库"，但**不做 MQ，用线程池 + 本地队列 + DB 轮询兜底**。这样能保住"把失败流量挡在 DB 之外"的核心收益，同时少一个中间件。**代价是可靠性下降**（本地队列会随进程崩溃丢失），**但对"可以接受偶尔丢单并给用户补一张券"的业务是可接受的。**
**"技术选型要考虑团队的运维能力"——这是我从这个项目里得到的最实际的认知。**

> 💡 **面试官意图：** 考"工程经济学"的思维。能算出"9900 次无效 DB 操作"和"失败率 × 成本"这个框架，就跳出了"技术炫技"的层次。

---

### Q109. 简历上写这个项目，最容易被追问什么？

**结论：** 四个方向，每个都有"看起来很强但一追问就崩"的陷阱。

| 追问方向 | 面试官的意图 | 容易崩的答法 | 正确答法 |
| --- | --- | --- | --- |
| **① 为什么不用 XX 技术** | 测你的技术视野和判断 | "XX 更简单所以没用"（没有分析） | 说清"备选是什么 + 为什么没选 + 代价" |
| **② 并发下真的对吗** | 测你有没有真的验证过 | "Lua 是原子的所以不会超卖" | **给出测试：800 并发抢 200，断言 4 条** |
| **③ 故障了怎么办** | 测你有没有想过失败模式 | "加了重试和死信就解决了" | 逐场景推演（Q96/Q97/Q98） |
| **④ 你具体做了什么** | 测你是不是自己写的 | 含糊其辞、说不清代码细节 | **说出类名、方法名、配置项、数字** |

**四个高频陷阱（我会主动规避）：**

**陷阱 1：简历写"QPS 10 万"但没压测。**
→ **我会写"设计目标"或"估算能力"，并明确标注。** 追问我时直接说"这是基于 Tomcat 500 线程 × 5ms RT 的估算，实际压测我做的是 800 并发的正确性验证"。

**陷阱 2：简历写"Redis 集群"但代码是单实例。**
→ **我会写实际用到的技术。** 如果写了 Hash Tag + 启动同槽自检，就明确说"这是为集群模式做的准备，当前部署是单实例，启动自检保证改配置后不会在集群下 CROSSSLOT"。**这比假装跑了集群强。**

**陷阱 3：简历写"分布式事务"但用的是最终一致。**
→ **我会写"基于 MQ 的最终一致性方案"，并在面试里主动说"这不是分布式事务，我们没有用 2PC/TCC/Seata，因为秒杀场景下可用性优先于强一致"。** **主动澄清术语比被抓住强。**

**陷阱 4：简历写"三高架构"但说不清任何一个数字。**
→ **我会在简历里只写具体的、可验证的点和数字。**

**我会这样设计简历描述：**
> **高并发秒杀交易系统**（Spring Boot 3 / MySQL 8 / Redis 7 / RabbitMQ）
> - **秒杀扣减**：Redis Lua 脚本实现「库存校验 + 扣减 + 一人一单 + 在途计数」的原子操作（5 个 O(1) 命令，1 次 RTT）；Hash Tag 保证多 key 命令同槽 + `@PostConstruct` 启动自检 + 槽位单测；**800 并发抢 200 库存的集成测试断言成功数恰好 200、剩余恰好 0**
> - **多级缓存**：Caffeine(L1) + Redis(L2)；逻辑过期 + 异步重建解决热点击穿，Redisson 互斥锁 + 双重检查 + 自旋等待解决冷 key 击穿；布隆过滤器 + 空值缓存 + 参数校验三层防穿透；L2 物理 TTL 加 ±20% 抖动防雪崩
> - **消息可靠性**：Confirm/Return 双回调 + 内存台账 + 5s 超时扫描 + 对账兜底；手动 ACK 区分五种消费结果；TTL+DLX 延迟重试（3 次，间隔 5s）；死信消费者先查订单状态再决定是否回补，**下游不可用时带退避 requeue 而非丢弃**
> - **最终一致性**：对账任务每 5 分钟校验 `Redis 库存 == DB 剩余 - 在途`；在途泄漏通过「连续 3 次采样 > 0」检出；**默认只告警不自动修**（避免对账 bug 放大成数据破坏）
> - **可观测性**：Micrometer 指标覆盖秒杀结果分桶、缓存分层命中、MQ 事件、限流触发、对账漂移；并发测试断言指标计数而非主观判断
> - **⭐ 代码审查与缺陷修复**：自主发现并修复 4 个真实缺陷 —— 商品缓存多 key `DEL` 的集群 CROSSSLOT、死信消费者在 DB 不可用时 ACK 丢消息、并发重复消息落错异常分支、限流 key 缺规则名导致规则间串额度；**每个都补了回归测试（测试总数 43）**

**每一条都有"可被验证的细节"，而不是"用了 XX 技术"。** 面试官追问任何一条，我都能给出代码位置、配置值和测试断言。

**⭐ 最后那一条"代码审查与缺陷修复"是整个简历里最值钱的一行** —— 因为它是**唯一一条证明"我会审查自己的代码"的证据**，而且每条都有完整的"发现方式 → 根因 → 修法 → 回归测试"闭环（第十四节）。**大多数候选人的简历只能写出"我做了什么"，写不出"我发现我做错了什么"。**

**追问：** 你最怕面试官追问简历上的哪一句？
**答：** **"800 并发抢 200 库存的集成测试断言成功数恰好 200、剩余恰好 0"** 这一句。
**怕的不是这句话本身，而是它引出的三个追问：**
1. **"这个测试跑在什么环境？"** → 需要 `BaseIntegrationTest` 起真实的 MySQL + Redis（因为 `stockService.deduct` 是真实执行 Lua 的）。**如果环境依赖没准备好，测试跑不起来**——我必须能现场说清依赖。
2. **"你怎么保证 800 个虚拟线程是真的并发，而不是排队？"** → `CountDownLatch go = new CountDownLatch(1)`，所有线程 `go.await()` 就绪后统一 `go.countDown()` 放行。**代码里就有，我能指给你看。**
3. **"如果哪天测试红了呢？"** → **这才是真正的考点。** 我的答案：**断言 4 条对应的 bug 是不同的**（Q13）——
   - 断言 1 红（成功数 ≠ 库存）→ **超卖**，Lua 的原子性被破坏；
   - 断言 2 红（剩余 ≠ 0）→ **少卖**，有路径提前退出；
   - 断言 3 红（拒绝数 ≠ 总数 - 库存）→ **重复计数**；
   - 断言 4 红（集合大小 ≠ 成功数）→ **一人一单记录丢失**（`SADD` 漏了或 key 不同槽）。
   **"我知道哪条断言红意味着什么"——这比"我的测试通过"更能证明测试是有效的。**

**准备方式：** 我会把这个测试类作为**唯一一个必须能"当场打开并逐行解释"的文件**（另一个是 `seckill_deduct.lua`）。**能打开代码讲解，是对"这是你写的"最好的证明。**

> 💡 **面试官意图：** 这题是元问题——面试官想知道你**怎么包装项目**。**关键是"每一条都有可追问的细节"**，这样追问不会崩，反而会变成展示深度的机会。

---

### Q110. 你从这个项目里学到了什么？（开放题，但很可能被问）

**结论：** 我会讲三个认知的转变，而不是罗列技术栈。

**认知 1：从"选技术"到"算成本"。**

做这个项目之前，我会想"高并发要用 Redis + MQ + 缓存"。做完之后我发现**判断标准是可计算的**：
- 秒杀场景值不值得异步化？**算一下"失败请求数 × 单次 DB 访问成本"**（Q108）；
- 要不要加 L1 缓存？**算一下"能省下的 Redis RTT × 请求数" vs "3 秒的不一致窗口能不能接受"**（Q25）；
- 要不要用本地消息表？**算一下"热路径多一次 DB 写的 RT 增量" vs "对账能覆盖的丢失窗口"**（Q44）。

**"能算清楚的决策才有说服力。"** 我在这个项目里养成的习惯是：任何技术选择都要能说出**两个量**——收益是多少、代价是多少。

**认知 2：从"能跑通"到"故障时行为正确"。**

这个项目最花时间的部分**不是实现功能，而是想清楚"这里失败了会怎样"**：
- Lua 返回 3（未预热）不能降级查库（Q10）；
- 补偿前必须先查订单是否存在，否则超卖（Q46）；
- 回补 Redis 必须在 DB 事务提交之后（Q64）；
- 补偿失败不能影响消息 ACK，否则无限重投（Q99）；
- 对账默认只告警，因为对账 bug 会放大成数据破坏（Q102）。

**"happy path 谁都能写；区分水平的是 error path。"** 而且我在做完这个项目后，**主动发现自己的代码里还有缺口**（Q98 的死信消费者、Q105 的 DB 健康检查）——**这个"能审查自己"的能力比"写出没有 bug 的代码"更现实。**

**认知 3：从"用框架"到"理解框架的边界"。**

三个具体的例子：
- **Spring 容器重试和业务侧重试会叠加** → 必须显式关掉容器重试（Q38）；
- **Spring AMQP 会往消息头写 `__TypeId__`** → 类改名后历史消息全部反序列化失败（Q27）；
- **Caffeine 的 `refreshAfterWrite` 有自己的加载语义** → 会和我的"跨实例互斥重建"打架，所以选 `expireAfterWrite`（Q26）。

**"框架提供的便利，往往是它替你做了某个决定。当你的系统需要不同的决定时，必须显式地关掉它。"** —— 这是我理解框架的新方式。

**最后我会说一句总结：**
> "如果只用一句话总结这个项目教给我的东西：**分布式系统的核心不是'让正常流程更快'，而是'让异常流程不产生错误的数据'。** 我做的所有设计（多层防重、对账、告警不自动修、宁可少卖不超卖）都是围绕这一条的。"

**这个答案是"有反思、有具体例子、有可迁移的认知"** —— 它证明的不是"我会用这些技术"，而是"我能从实践中提炼出判断力"。

**追问：** 那如果让你现在给一个新人讲这个项目，你会先讲什么？
**答：** 我会先讲**一条数据的流动路径**，而不是先讲架构图：
> "用户点了一下秒杀按钮。**Redis 里的一行数字减了 1，一条消息被丢进 MQ，接口立刻回了"排队中"。几百毫秒后，一个消费者线程从 MQ 里取出这条消息，在一个数据库事务里写了 5 张表，然后往 Redis 里写了一个"成功"的结果。前端轮询到了这个结果，告诉用户"抢到了"。**
> **再看一遍：Redis 减 1 和数据库减 1 是两次操作，中间隔了几百毫秒。所以问题就来了——如果这中间任何一步失败了，会发生什么？**
> 这就是这个项目的全部内容：**用 MQ 的可靠性、用幂等、用补偿、用对账，把'两次操作之间的空隙'填上。**"

**为什么先讲这个：** 因为**所有的设计决策都是从这条路径派生出来的**——Lua 是为了让"减 1"原子，MQ 的 Confirm/Return 是为了让"丢消息"可发现，幂等是为了让"重复"无害，对账是为了让"失败"能被修复。**先讲路径，再讲每个环节怎么保证正确性，听众就能自己推导出后面的设计。**

**"先讲数据和它的流动，再讲保证机制"** —— 这是我讲任何系统时的顺序。**反过来（先讲技术栈再讲业务）会让听众一直在问"所以这是干嘛的"。**

> 💡 **面试官意图：** 开放题考"反思能力"。**答技术栈清单是最差的；答"踩了哪些坑"是及格；答"认知的转变"是优秀。**

---

## 十二、答不上来就诚实说不会的 5 个高频硬核问题

这 5 个问题**超出本项目的范围**（要么需要生产经验、要么需要源码级理解）。**面试官问这些是在探你的边界** —— 硬编答案比承认不会危险得多。

### H1. Redisson 的看门狗实现原理是什么？时间轮是怎么工作的？

**为什么答不上来：** 这需要读过 Redisson 源码（`RedissonLock` + `Timeout` + `TimerTask` + Netty 的 `HashedWheelTimer`）。
**诚实的答法：**
> "我知道**行为**：不指定 `leaseTime` 时默认 30 秒租约，后台每 10 秒续期到 30 秒，直到 `unlock` 或进程退出。**但内部是 Netty 的 `HashedWheelTimer` 时间轮实现**——时间轮的分层结构、每个 tick 怎么推进、任务在多层之间怎么降级，我**没有读过源码，说不了细节**。
> 项目中我**没有依赖看门狗**，而是显式指定了 `leaseTime = 10s`（Q56），所以我更需要的是'为什么不用它'而不是'它怎么实现的'。"

**✅ 加分点：** 说清"行为" + 明确划出"我不知道"的边界 + 解释"为什么我的项目不依赖它"。

**❌ 硬编会有多惨：** 一旦开始瞎编"时间轮有 5 层，每层 64 个槽"，面试官会追问"那为什么是 5 层"、"任务降级的条件是什么"——**第二个追问就崩了**，而且会连带怀疑你前面所有答案的真实性。

---

### H2. 你们线上真实的峰值 QPS 是多少？P99 是多少？压测怎么做？

**为什么答不上来：** 这是一个**个人项目/课程项目**，没有真实线上流量。
**诚实的答法：**
> "这是一个**个人项目，没有线上真实流量**。我做的验证是**并发正确性**（800 并发抢 200 库存的集成测试），不是**端到端压测**。所以：
> - **峰值 QPS：** 我没有实测值。我可以给出**基于组件能力的估算**（Tomcat 500 线程 × 5ms RT ≈ 10 万 QPS 的理论入口能力，Redis 侧约 1~2 万 EVAL/s），但**这是估算不是实测**。
> - **P99：** 没有数据。我埋了 `mall.seckill.lua` 的 Timer 和 percentile histogram 配置，**就是为了将来做压测时能直接看到 P99**。
> - **压测怎么做：** 我会用 `wrk` 或 `JMeter` 做阶梯压测（1k → 5k → 10k），每个量级记录 P50/P95/P99、错误率和 Redis/MySQL 的资源水位，**找出'QPS 上升但吞吐不再增长'的拐点，那才是真实容量**。"

**✅ 加分点：** **明确说"没测过"** + 给出估算方法和依据 + 说清"我会怎么补测"。面试官更在意的是你的**方法论**，而不是一个数字。

**❌ 硬编会有多惨：** 编了"峰值 5 万 QPS"，面试官下一句必然是"压测环境什么配置？几台机器？MySQL 是什么规格？P99 呢？瓶颈在哪个组件？"——**四个问题连环追问，一定崩**。

---

### H3. Redis 的 LRU/LFU 淘汰算法具体怎么实现的？近似采样的误差有多大？

**为什么答不上来：** 需要读 Redis 源码（`evict.c` 的 `performEvictions`、`evictionPool`、`maxmemory-samples`）。
**诚实的答法：**
> "我知道**结论层面**：Redis 用的是**近似** LRU/LFU，不是精确的，通过随机采样（`maxmemory-samples`，默认 5）从候选池里选淘汰对象，**采样数越大越接近精确但 CPU 开销越高**。LFU 还带**计数器衰减**（`lfu-decay-time`）。
> **但具体的数据结构（`evictionPool` 的插入/排序逻辑）、采样精度与 `maxmemory-samples` 的定量关系，我没有读过源码，说不了。**
> 更重要的是：**本项目用的是 `noeviction`**（`docker-compose.yml` 里显式配的），所以**淘汰算法在当前配置下不生效**。这是刻意的——`RedisKeys` 的注释解释了原因：**宁可写失败也不能让库存 key 被淘汰**，因为库存 key 被淘汰等于库存直接错乱。"

**✅ 加分点：** 把"我不知道算法细节"转化为"我的项目里这个算法不生效，而且这是有意的设计"——**这从"知识盲区"变成了"设计判断"**。

**❌ 硬编会有多惨：** 说"Redis 是精确 LRU"，面试官一句"你确定吗"就完了。

---

### H4. MySQL 在你们的数据量下，`t_order` 表怎么分库分表？分片键怎么选？

**为什么答不上来：** 本项目**没有做分库分表**，而且**秒杀订单的量级（几百到几千单/活动）不需要分表**。
**诚实的答法：**
> "**本项目没有分库分表**，因为秒杀订单的量级（单个活动几百到几千单）完全在单表能力范围内——几百万行的 `t_order` 表在 MySQL 上是轻松的。
> 但**设计上我做了两个为分片预留的准备**：① 订单主键用**雪花 ID**（全局唯一、趋势递增），可以直接做分片键（`id % N`），不会像自增主键那样在多库下冲突；② JWT 让应用层无状态，分片后不需要会话粘滞。
> **如果要分片，我的方案是：**
> - **分片键选 `user_id`**（不是 `order_no`），因为**用户的订单查询是最高频的读路径**（`listByUser`），按 `user_id` 分片能让"查我的订单"只落一个分片；代价是"按 `order_no` 查询"需要**全分片路由**（或者维护一个 `order_no → user_id` 的映射表/基因法）；
> - **分片算法**：`user_id % N`（范围分片会有热点，哈希分片更均匀）；
> - **不用 `order_no` 做分片键**，因为它是雪花 ID（含时间戳），按它分片会导致**新数据全部落在同一个分片**（热点写）。"

**✅ 加分点：** 承认"没做" + 给出**有理由的**分片方案 + 指出"分片键选 `user_id` 而不是 `order_no`，因为写热点"这个具体判断。

**❌ 硬编会有多惨：** 说"我们用了 ShardingSphere 分了 16 个库"，面试官追问"分片键是什么？跨分片查询怎么办？分布式事务怎么处理？扩容时怎么迁移？"——**必崩**。

---

### H5. 你怎么保证 MQ 消息和数据库写入的原子性？用过 Seata 之类的框架吗？

**为什么答不上来：** 这是一个**术语陷阱**。本项目的答案是"**我们不保证原子性，我们做的是最终一致**"。
**诚实的答法：**
> "**我们没有保证原子性，这是刻意的选择。** 具体说：
> - **没有用 Seata / 2PC / TCC**。秒杀场景下**可用性优先于强一致**——2PC 的同步阻塞会直接摧毁秒杀的吞吐，而且会引入一个新的单点（事务协调者）。
> - **我们做的是"最终一致"**：Redis 扣减（Lua 原子）→ MQ 投递（Confirm/Return/超时扫描保证可靠）→ DB 落库（`uk_message_id` 幂等）→ **对账任务每 5 分钟校验不变式并修复**。
> - **关键的取舍是"宁可少卖，绝不超卖"**（Q4）：所有的失败模式都被设计成"偏向少卖"（先 DB 后 Redis、补偿前先查订单、回补有总库存封顶），所以**不需要原子性也不会产生资损**。
> - **如果业务必须强一致**（比如资金账户之间的转账），那 Seata 的 AT/TCC 模式或者本地消息表是合适的方案——**但秒杀不适合，因为它不需要强一致，只需要'最终不出错'。**
> 
> **我没有在生产环境用过 Seata**，所以它的事务协调器实现、AT 模式的全局锁机制、回滚日志的存储方式，我**说不了细节**。"

**✅ 加分点：** 这是**最好的"不会"** —— 你不仅承认了不知道 Seata，**还纠正了问题背后的假设**（"原子性"不是必需的，"最终不出错"才是）。**面试官问这题往往是在设陷阱：如果你说"我用 MQ + 本地消息表保证了原子性"，他会追问"那 MQ 的发送和本地消息表的写入怎么保证原子？"——递归追问到崩。**

**❌ 硬编会有多惨：** 说"我用了 Seata 保证分布式事务的原子性"——追问"AT 模式怎么处理全局锁？"、"回滚日志存在哪？"——**两个问题就崩，而且会暴露你根本没用过。**

---

### 关于"诚实说不会"的三个技巧

**① 划清边界：说清"我知道什么"和"我不知道什么"。**
不要笼统地说"我不会"。要说："**行为层面我知道 A、B、C；实现/源码层面我不知道 D、E。**" —— 这证明你有**清晰的自我认知**。

**② 把"不会"转化为"为什么我的项目不需要它"。**
这 5 个问题里有 4 个（H1、H3、H4、H5）本项目的答案是"这个技术在我的场景里不适用/不生效"。**能把"不会"讲成"不适用"是最好的结果。**

**③ 给出"如果要做，我会怎么做"。**
不需要是完美的方案，但要有**方向**和**已知的难点**。这证明你不是"没想过"，只是"没做过"。

**一条底线：绝不要在技术细节上编造。** 面试官问到细节，通常是因为**他懂**。一旦编造被抓住，你前面所有真实的部分都会被打上问号。**承认边界只会损失一道题；编造被抓住会损失整场面试。**

---

## 十三、简历项目最容易被戳穿的 5 个点及防御方式

### 戳穿点 1：「QPS 10 万」但没有任何压测数据

**为什么会被戳穿：** 这是简历上最常见也最容易被追问的虚报。面试官问三连："压测环境是什么？瓶颈在哪个组件？P99 是多少？"——如果没有真实数据，第三问就崩。而且**即使有数据，"QPS 10 万"这个数字本身在单机架构下也不可信**（Q95：MySQL 单实例的写能力只有 2,000~5,000 TPS）。

**防御方式：**
1. **不要写"QPS 10 万"**。改写成具体的设计指标：**"800 并发抢 200 库存的集成测试断言成功数恰好 200、Redis 剩余恰好 0"** —— 这是**可验证的、真实做过的**。
2. 如果要提容量，**说清是"估算"并给出推导**："入口理论能力约 10 万 QPS（Tomcat 500 线程 × 5ms RT），但真正的瓶颈是 Redis 的 Lua 执行（约 1~2 万 EVAL/s）和 MySQL 的订单创建（约 2,000~5,000 单/s）。"
3. **主动说瓶颈在哪一层** —— 这比一个漂亮的数字有价值得多（Q107）。

**✅ 记住这句话：** "我没有做过端到端压测，所以我给不出实测 QPS。我能给的是**并发正确性的验证**和**基于组件能力的容量估算**。"

---

### 戳穿点 2：说"用了 Redis 集群 / 分库分表"但代码里没有

**为什么会被戳穿：** 面试官会问"集群几个节点？分片键是什么？跨分片查询怎么处理？扩容怎么迁移？"——如果只是配了 `cluster-nodes` 但没真的跑过，这些问题的答案会很虚。**而且"用了集群"会引来关于 Redis Cluster 的深入追问（CROSSSLOT、槽迁移、MOVED/ASK 重定向），这些没实际搭过很难答好。**

**防御方式：**
1. **如实说明部署形态**："**当前部署是单实例 Redis**（`docker-compose.yml` 里是 `redis:7.2-alpine` 单节点）。"
2. **把"为集群做的准备"讲成亮点** —— 这比假装跑了集群更强：
   - **Hash Tag 设计**：秒杀的三个 key 用 `{act:N}`、商品缓存的两个 key 用 `{p:N}`（**后者是修 Bug 时补上的**），保证多 key 命令同槽；
   - **`@PostConstruct` 启动自检 + 单测**：用 `RedisClusterSlot.sameSlot()` 验证同槽，**不同槽直接启动失败**（`SeckillStockService.assertSameSlot`）；`RedisClusterSlotTest` 里有 7 个用例覆盖槽位计算（含 CRC16 标准校验值对照、已知 key 的槽位与 Redis 文档一致性），其中包括"同商品两 key 同槽"和"跨商品分散"两条互补断言；
   - **⭐ 一个真实发现并修复的集群相关 Bug**：代码审查时发现 `ProductCacheService.invalidate` 的两 key `DEL` 在集群下会 CROSSSLOT（Q93 / 第十四节缺陷 ①）——**修法是加 `{p:N}` Hash Tag，并补了 2 个回归测试。这是单机环境永远测不出来的问题，靠的是"审查多 key 命令"这个动作。**
3. **主动指出集群下的坑**：CROSSSLOT、多 key 操作受限、Hash Tag 导致的数据倾斜。**能说清"集群下我的方案哪里会出问题、我怎么发现并修掉的"，比说"我用了集群"更能证明你懂集群。**

**✅ 最强的答法：** "我**没有跑过 Redis 集群**，但我的设计是**为集群准备好的**：Hash Tag 保证 Lua 的多 key 同槽，启动自检 + 7 个槽位单测把这个约束钉死。而且我在代码审查时发现了一个真实的集群 Bug —— `invalidate` 里两个 key 的 `DEL` 会 CROSSSLOT，**这个是单机永远不会暴露的**。我给它加了 `{p:N}` Hash Tag，并补了'同商品同槽'和'跨商品分散'两条互补的回归测试。"

---

### 戳穿点 3：说"保证了一致性"但说不清是什么级别的一致性

**为什么会被戳穿：** "一致性"是一个**程度词**，不是一个技术。面试官追问"强一致还是最终一致？不一致窗口多大？怎么保证的？"——如果答不上来，说明你没想过。**更糟的是如果声称"强一致"，那必然被打穿**（Q23：Cache Aside 本身做不到强一致）。

**防御方式：**
1. **永远说"最终一致"，并且给出窗口上界**（Q23 的表格）：
   - L1：**3 秒**（`l1-ttl = 3s`）；
   - L2 逻辑过期：**3 分钟**（`l2-logical-expire = 3m`）；
   - 延迟双删窗口：**500ms**（`double-delete-delay`）；
   - 库存对账收敛：**最长 15 分钟**（每 5 分钟采样 × 连续 3 次）。
2. **说清"哪一份数据是权威"**：
   - **库存**：DB 的 `available_stock` 是权威，Redis 是**可重建的投影**（Q47）；
   - **订单**：DB 是唯一权威（`uk_user_activity` / `uk_message_id` 保证）；
   - **商品详情**：DB 是权威，缓存允许陈旧。
3. **主动说清"强一致做不到，也不该做"**：跨资源事务需要 2PC/TCC，代价是可用性和性能，而秒杀场景可用性优先（Q110 H5）。

**✅ 记住：** "**最终一致 + 明确的窗口上界 + 谁是权威**" 是回答一致性问题的三段式。**能说出"窗口是 15 分钟"远好于说"最终会一致"。**

---

### 戳穿点 4：说"用了一堆技术"但说不清每个技术解决什么问题、替代方案是什么

**为什么会被戳穿：** 技术栈罗列（"Redis + RabbitMQ + Redisson + Caffeine + 布隆过滤器 + JWT + Micrometer"）让人感觉像抄的。面试官会随机挑一个问"为什么用它"——**如果答不出替代方案，就暴露了是在跟教程做。**

**防御方式：** 对简历上的每一个技术，都准备好"**四个问题**"：

| 问题 | 秒杀 Lua 的答案 | 本地售罄标记的答案 |
| --- | --- | --- |
| **解决什么问题？** | 多命令的原子性（分多次发送会超卖） | 售罄后的无用 Redis 流量 |
| **备选方案是什么？** | 分布式锁 / DB 乐观锁 / `MULTI-EXEC` | 不做 / Redis 侧标记 |
| **为什么没选它？** | 锁的 RTT 和复杂度高；DB 会被打穿 | 不做则浪费 Redis；Redis 标记多一次 RTT |
| **代价是什么？** | Lua 阻塞 Redis（靠只用 O(1) 命令规避） | 多实例不同步，靠 10 秒 TTL 兜底 |

**我会在简历里只写我真正理解的技术**，并且每条描述都带一个**具体的数字或行为**（"L1 TTL 3 秒"、"重试 3 次间隔 5 秒"、"连续 3 次采样判定泄漏"）。**数字是"我真的做过"的最强证据**——因为**只有真正配过、调过这些参数的人才会记得住。**

**✅ 一个自检方法：** 写完简历后，对每一条技术描述问自己"**如果面试官问'为什么不用另一个方案'，我能答出来吗？**"——答不出来的就删掉，或者去把那个替代方案想清楚再写。

**⭐ 但比"四个问题"更有说服力的是第五个问题："这个技术用错过吗？"**
> **"为什么选它"证明你判断过；"我用错过什么、怎么发现的、怎么修的"证明你**真的用过**。**
> 而这个项目里我能拿出**4 个真实缺陷的完整修复闭环**（第十四节），其中 3 个的根因恰好是"技术用得不够对"：
> | 技术 | 我用错在哪里 | 怎么发现的 | 怎么修的 |
> | --- | --- | --- | --- |
> | Redis Hash Tag | 商品缓存的两个 key 没加 tag，集群下多 key `DEL` 会 CROSSSLOT | 代码审查（审"哪些 key 出现在多 key 命令里"） | 加 `{p:N}` + 2 个槽位回归测试 |
> | RabbitMQ ACK 语义 | 死信消费者把"DB 不可用"当"毒丸"，`ACK` 丢消息 | 故障推演（"MySQL 挂了整条链路会怎样"） | 1 秒退避 + `nack(requeue=true)` + `dlq_requeue` 指标 |
> | Spring 异常语义 | `DuplicateKeyException` 没翻译成业务异常，落进"可重试"分支 | 异常语义审查（逐 catch 看上层怎么处理） | `catch (DuplicateKeyException) → DuplicateMessageException` |
> **这三个的根因都有一个共同的形态：不是"技术不会用"，而是"边界没想到"。** —— **这就是简历项目最值钱的部分：它证明了你有"审查自己代码"的习惯。**

---

### 戳穿点 5：说"处理了并发问题"但说不出任何一次具体的竞态

**为什么会被戳穿：** "处理并发"是一个结果，不是一个过程。面试官想知道的是**你识别出了哪个具体的竞态，怎么发现的，怎么解决的。** 泛泛而谈"我用了锁"会被追问到崩。

**防御方式：** 准备 **3~5 个具体的竞态故事**，每个都包含"**时序图 + 后果 + 解法**"。本项目有充足的素材：

**故事 1：支付回调与超时关单的竞争（Q70）**
```
T1: 支付回调 SELECT status → 0（待支付）✅ 通过检查
T2: 超时关单  SELECT status → 0（待支付）✅ 通过检查
T1: UPDATE status = 1（已支付）
T2: UPDATE status = 2（已取消）  ← 覆盖了 T1！
结果：订单是"已取消"，但钱已经收了；库存被错误回补 → 少卖
```
**解法：** `WHERE status = 0` 的条件更新。两个并发事务只有一个能改到行（InnoDB 行锁），`rows == 0` 的那个什么都不做。

**故事 2：Cache Aside 的"读回填旧值"竞态（Q22）**
```
写线程:  更新DB(v2) ─────────────── 删除缓存
读线程:      读DB(v1) ── 回填缓存(v1) ──────► 缓存残留 v1
```
**解法：** 延迟双删（`double-delete-delay = 500ms`）+ TTL 兜底。

**故事 3：`waitForCacheFilled` 的旧数据覆盖新数据（Q28）**
```
T0  线程 A 拿锁，读到 v1（DB 还没更新）
T1  线程 B 等 150ms 超时，读到 v2
T2  A 回写 v1
T3  B（无锁）回写 → 如果 B 回写，可能用 v1 覆盖 v2
```
**解法：** **没持锁就不写共享状态**。`waitForCacheFilled` 超时后直接查库返回，**不回写缓存**。

**故事 4：`republish` 成功但 `basicAck` 失败导致消息重复（Q38）**
```
消费者: republish 到重试队列 ✅
        basicAck ❌（channel 断开）
broker: 原消息重投 + 重试队列里也有一份 → 同一条消息被处理两次
```
**解法：** 幂等必须做到**数据库唯一键级别**（`uk_message_id`），而不是"应用层 `if` 判断"。

**故事 5：并发重复消息走错异常分支（Q99 / 缺陷 ③）**
```
T1: alreadyConsumed → false
T2: alreadyConsumed → false     ← 两个都读到"不存在"
T1: insertConsumeRecord ✅
T2: insertConsumeRecord → DuplicateKeyException（不是 DuplicateMessageException！）
    → 落到 catch (Exception) → 走"可重试"分支 → 重试 3 次 + 进死信
```
**解法：** 把 `insertConsumeRecord` 的 `DuplicateKeyException` 转换成语义明确的 `DuplicateMessageException`。

**"故事 5"是最有价值的一个** —— 因为它是我**主动发现自己代码里的一个缺陷，并且把它修掉了**（而不只是"识别了一个问题"）。**能讲出"我发现自己的代码有这个问题、定位到了具体位置、改了、并加了回归测试"，比讲十个正确答案都有说服力。**

**⭐ 如果你想把"并发故事"讲到最高分，加讲这个故事 6（它是我唯一一个靠"真实操作序列"才发现的）：**

**故事 6：限流规则之间串额度（Q89 / 第十四节缺陷 ④）**
```
用户操作序列（真实冒烟测试，不是压测）：
  1. 浏览 8 次商品详情 → 全部通过（product-detail 规则 50/s）
     但这 8 次被记进了 rate:limit:IP:127.0.0.1
  2. 点 1 次秒杀 → 秒杀规则读到 ZCARD = 8 >= 5
     → 正常用户被误判为"手速太快"，返回 42900
```
**根因：** 限流 key 里没有规则名，两个规则共用同一个 ZSET。
**解法：** key 改成 `rate:limit:{rule}:{dimension}:{target}`，规则之间彻底隔离。
**⭐ 这个故事的独特价值：** **它只在"先访问大额度接口、再访问小额度接口"时才复现——单独压测秒杀接口永远发现不了。**
**它的教训是：并发/交互类 Bug 的回归测试必须复现"触发它的操作序列"，而不是复现它所在的函数。** 我补的回归测试 `differentRulesDoNotShareQuota` 就是按"先大额度、后小额度"这个序列写的。

**✅ 记住：** 面试官问"你怎么处理并发"，**不要答"我用了锁/Lua/唯一索引"，要答"有一次我遇到了这个时序，会导致这个后果，所以我在这个位置加了这个约束"。** 时序图是最好的表达方式——**能在白板上画出时序图的人，一定真的想过这个问题。**
**如果能进一步说"我还发现了自己的两处错误（故事 5 和 6），这是怎么发现的、怎么修的"——那就从"想过"升到了"审查过"。**

---

### 最后一个提醒：区分"简历项目"和"生产系统"

面试官对个人项目/课程项目的期待是**"深度"而不是"规模"**。所以：
- **不要假装它是生产系统**（"我们线上……"会引起"你们团队多少人？你的角色是什么？"的追问）；
- **要强调"我做的决策和取舍"**（"我评估了两个方案，选了 A，因为……，代价是……"）；
- **要强调"我怎么验证的"**（测试断言、指标、对账不变式，43 个用例里 3 个是缺陷回归）；
- **要主动说清"我知道它的局限在哪"**（Q105 的改进清单 —— **注意要说清哪些已经修完了**）；
- **⭐ 最有力的：讲"我发现并修掉了 4 个真实缺陷"的完整闭环**（第十四节）—— 发现方式、根因、修复、回归测试各一句，每段 90 秒。

**一个诚实、有深度、能自知边界、并且能拿出"缺陷修复闭环"的个人项目，远胜过一个吹得很大但一问就崩的"生产系统"。** 面试官每天看几十份简历，**"知道自己在做什么"比"做过很多东西"更稀缺；而"知道自己的代码哪里错过、并且修好了"比"知道自己在做什么"更稀缺。**

---

## 十四、项目中发现并修复的 4 个真实缺陷

> **这一节是全篇最值得背的部分。**
> 面试里最难伪造的不是"我用了什么技术"，而是"**我发现过什么问题、怎么修的**"。
> 下面 4 个缺陷都是我在这个项目里**真实定位并修复**的（另外还顺带记录了一个启动期发现的调度器设计缺陷），每个都包含**发现方式 → 根因 → 修复 → 回归测试**四段。
> **注意一个共同点：这 4 个缺陷里，有 3 个是"不报错但行为错误"的类型** —— 它们不会让测试变红、不会抛 500、不会有异常日志，**只会让行为悄悄偏离预期**。这也是为什么它们比"编译不过"的 bug 有价值得多。

### 总览

| # | 缺陷 | 发现方式 | 类型 | 危害 | 回归测试 |
| --- | --- | --- | --- | --- | --- |
| ① | 商品缓存多 key `DEL` 在 Redis Cluster 下 CROSSSLOT | **代码审查**（审查"哪些 key 会出现在多 key 命令里"） | 部署形态相关 | 集群下**缓存失效静默失败**，用户读到旧数据直到 TTL 到期 | `RedisClusterSlotTest#productCacheKeysMustShareHashSlot`<br>`#differentProductsSpreadAcrossSlots` |
| ② | 死信消费者在 DB 不可用时 `ACK` 丢消息 | **故障场景推演**（推演"MySQL 挂了"的完整时序） | **数据永久丢失** | 订单永久悬空、库存不回补，**只能靠对账 15 分钟后修** | 手工演练（DB 停机 + 死信消息） |
| ③ | 并发重复消息落到"可重试"分支 | 异常语义审查（`DuplicateKeyException` 冒到了通用 catch） | 假告警 + 资源浪费 | 白走 2 次延迟重试，监控里出现"消费失败率上升"的**假告警** | 由 `OrderIdempotencyTest` 的幂等用例覆盖 |
| ④ | 限流 key 不含规则名，规则间串额度 | **真实冒烟测试**（模拟用户"先逛商品、再点秒杀"） | **功能错误** | 正常用户被误判为刷接口，**返回 42900** | `RedisRateLimiterTest#differentRulesDoNotShareQuota` |
| ⑤ | 单线程 `TaskScheduler` 抢走所有 `@Scheduled` 的调度权 | **看启动日志**（线程名和任务名对不上） | 可用性风险 | **一个定时任务卡住会让所有定时任务停摆** | 由 4 线程 `@Primary` 调度器 + 线程名前缀间接保证 |

**⭐ 从这张表能提炼出的一条经验：4 个缺陷里有 3 个（① ③ ④）的共同特征是"键/异常/语义的命名空间不够"** —— key 少了 Hash Tag、异常少了翻译、限流 key 少了规则名。**这类缺陷的共性是"局部看起来都对，组合起来才错"，而组合场景恰恰是单元测试最容易漏掉的。**

---

### 缺陷 ①：商品缓存多 key `DEL` 的 CROSSSLOT

**发现方式：** 代码审查。触发点是审视"本项目里有哪些操作会同时碰多个 Redis key"，然后逐个检查它们是否同槽。

**根因：**
```java
// ❌ 修改前
public static String productCache(Long productId)     { return "cache:product:" + productId; }
public static String productNullCache(Long productId) { return "cache:product:null:" + productId; }
```
`ProductCacheService.invalidate` 里的一条 `redis.delete(List.of(cacheKey, nullKey))` 是**多 key 命令**，在 Redis Cluster 下要求所有 key 同槽。这两个 key 没有 Hash Tag，`CRC16` 结果几乎必然不同槽 → **CROSSSLOT**。

**为什么这个缺陷特别危险：** **缓存失效的失败是静默的**。异常被上层吞掉（或者只是打了一行日志），用户会一直读到旧数据直到 L2 物理 TTL（30 分钟）到期。**这比接口 500 难排查得多，因为它没有任何"症状"。**

**修复：给商品维度加 Hash Tag，让两个 key 同槽。**
```java
// ✅ 修改后
public static String productTag(Long productId) { return "{p:" + productId + "}"; }

public static String productCache(Long productId) {
    return "cache:product:" + productTag(productId);           // cache:product:{p:1001}
}
public static String productNullCache(Long productId) {
    return "cache:product:null:" + productTag(productId);      // cache:product:null:{p:1001}
}
```

**为什么没有采用"拆成两次 `DEL`"这个更简单的方案：**
我最初的判断是"这两个 key 的删除不需要原子性"，但**这个判断是错的**。如果只删掉了真实缓存而没删空值缓存，`getDetail` 会先命中 `cache:product:null:{p:N}` **直接 `return null`** —— 比不删更糟（Q20 讲过这个经典 Bug）。
**教训：判断"要不要原子性"，必须把"部分成功"的后果推演完。** 部分成功比完全失败更难排查，因为它留下的是一个**语义上不自洽的状态**。

**回归测试（两条互补的断言）：**
```java
@Test @DisplayName("商品缓存的两个 key 也必须同槽（否则多 key DEL 会静默失败）")
void productCacheKeysMustShareHashSlot() { ... }

@Test @DisplayName("不同商品的缓存 key 应当分散到不同槽，避免数据倾斜")
void differentProductsSpreadAcrossSlots() { ... }
```
**为什么需要两条：** 第一条防"忘记加 Hash Tag"，第二条防"矫枉过正"（如果 tag 写成 `{p}` 不带 ID，所有商品的缓存会被塞进同一个槽 → 单点热点）。**两条一起，才把 Hash Tag 约束在"同商品同槽、跨商品分散"这个正确的区间里。**

---

### 缺陷 ②：死信消费者在 DB 不可用时 `ACK` 丢消息

**发现方式：** 故障场景推演。追问自己"如果 MySQL 挂了，从秒杀请求到消息进死信，整条链路的最终状态是什么？"，然后逐跳推演。

**根因：**
```java
// ❌ 修改前
} catch (Exception e) {
    // 处理死信本身失败：确认掉并告警，由对账任务兜底。
    channel.basicAck(deliveryTag, false);
}
```
`catch (Exception e)` 把**两种性质完全不同**的失败混在了一起：

| 失败类型 | 例子 | 该不该 ACK |
| --- | --- | --- |
| **消息是毒丸**（结构性问题，重投永远不会成功） | 消息体字段缺失、活动被永久删除 | 可以 ACK（或送 parking queue） |
| **下游瞬时不可用**（重投就能成功） | **DB 连接池耗尽 / DB 主从切换** | **绝不能 ACK**，否则消息永久丢失 |

**而"查订单状态都失败"恰恰说明我们根本没能力判断订单到底有没有落库** —— 此时丢弃消息是最坏的选择。

**当时的最终状态（推演结论）：**
```
Redis：stock -1，bought 加入用户，inflight +1
MQ   ：消息已被消费并丢弃（进过死信、被 ACK）
DB   ：没有订单
内存台账：pending 记录已在 Confirm 时 invalidate → 也没有
⇒ 订单永久悬空，只能等对账在 15 分钟后发现并修复
```

**修复后的实现：**
```java
} catch (Exception e) {
    // 处理死信本身失败（最典型的是"数据库不可用，连订单状态都查不了"）。
    // 这里【绝不能】ACK —— ACK 意味着消息被永久丢弃，而这次失败
    // 说明我们根本没能力判断订单到底有没有落库。
    //
    // 正确做法是 requeue，等下游恢复后重新处理。
    // 代价是：如果消息本身是"毒丸"，会反复重投。
    // 为了不给 broker 造成瞬时自旋，这里先短暂退避再 nack。
    // 生产环境更完善的做法是给死信消息也加 retryCount 头，
    // 超过阈值后投递到一个"人工处理队列"（parking queue）并告警。
    log.error("处理死信消息失败，将 requeue 等待下游恢复。orderNo={} messageId={}",
            payload.getOrderNo(), messageId, e);
    metrics.mqEvent("seckill", "dlq_requeue");
    try {
        Thread.sleep(1000);                  // ← 退避：requeue 会立刻重投
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();  // ← 恢复中断标志（与 Q29 同一条纪律）
    }
    channel.basicNack(deliveryTag, false, true);
}
```

**三个设计细节：**
1. **退避 1 秒**：单纯的 `requeue=true` 会让 broker **立刻**重投（零间隔自旋），DB 持续不可用时会拖满 broker 和消费线程。这和 `RabbitMQConfig` 里"立刻重投等于零间隔自旋"的判断是同一条原则。
2. **恢复中断标志**：和 `ProductCacheService` 里的纪律一致（Q29）。
3. **把"毒丸消息"的代价写进注释，并给出生产解法**（retryCount 头 + parking queue）。**知道自己引入的新问题，比假装方案完美更重要。**

**修复后的行为对比：**
| | 修复前 | 修复后 |
| --- | --- | --- |
| DB 不可用时的死信消息 | ACK → **永久丢失** | requeue（1 秒退避）→ DB 恢复后**正常落库** |
| 需要人工介入 | 需要（对账修 15 分钟 + 用户的资格丢了） | 不需要 |
| 新增指标 | — | `mall.mq.event{action=dlq_requeue}`（> 0 即告警） |

**⭐ 修复背后的通用原则：** 原来的代码在"不确定"时选择了**丢弃**（破坏性），修复后在"不确定"时选择了**保留 + 重试**（保守）。
**"不确定时选择可恢复的那一侧"** —— 这和"宁可少卖绝不超卖"（Q4）、"先 DB 后 Redis"（Q64）是同一个思路的第三次应用。

---

### 缺陷 ③：并发重复消息落到"可重试"分支

**发现方式：** 异常语义审查。逐个检查"每个 `catch` 分支到底在捕获什么、上层会怎么处理"。

**根因：**
```java
// ❌ 修改前
if (mqMessageService.alreadyConsumed(msg.getMessageId())) {
    throw new DuplicateMessageException(msg.getMessageId());
}
mqMessageService.insertConsumeRecord(msg);   // ← 并发重复时抛的是 DuplicateKeyException
```
`DuplicateKeyException` 既不是 `DuplicateMessageException`、也不是 `DuplicateOrderException`、也不是 `NonRetryableMessageException` → **落到 `SeckillOrderConsumer` 的 `catch (Exception e)` → 走 `handleRetryable` → 投递延迟重试队列 → 5 秒后重新消费。**

**危害比"浪费重试"更隐蔽：**
1. **白走两次延迟重试**（第 2 次重试时 `alreadyConsumed` 才返回 true 被识别为重复）→ 每次多花 5 秒、多占一个消费者线程；
2. **监控里出现"消费失败率上升"的假告警**（`mall.mq.event{action=consume_retry}` 假上涨）—— 而它会**掩盖真正的问题**：运维看到消费重试上涨会去查下游，但实际上是消息重复，**这本来是完全正常的现象**；
3. 并发重复量大时会**挤占延迟重试队列**，让真正的瞬时故障重试排在后面。

**修复后的实现（当前代码）：**
```java
// ✅ 修改后
if (mqMessageService.alreadyConsumed(msg.getMessageId())) {
    throw new DuplicateMessageException(msg.getMessageId());
}
try {
    mqMessageService.insertConsumeRecord(msg);
} catch (DuplicateKeyException e) {
    // 并发场景：两个消费者同时处理同一条消息，都通过了上面的查询，
    // 然后一起走到 INSERT，其中一个撞上 uk_message_id。
    //
    // 必须在这里把它翻译成 DuplicateMessageException。
    // 如果放任 DuplicateKeyException 冒到消费端，它会落进通用的
    // "可重试异常"分支 → 投递延迟重试队列 → 几秒后重新消费 →
    // 这次 alreadyConsumed 返回 true 才被识别为重复。
    // 结果是白白多花两次重试和几秒延迟，还会在监控里制造
    // "消费失败率上升"的假告警，掩盖真正的问题。
    throw new DuplicateMessageException(msg.getMessageId());
}
```

**核心认知：**
> **"操作在数据库层面失败了"和"这个操作应该失败"是两件事。** 前者是 `DuplicateKeyException`（基础设施异常），后者是 `DuplicateMessageException`（业务语义）。
> **异常类型的职责是把"底层发生了什么"翻译成"上层该怎么办"。** 翻译缺失时，上层只能靠 `catch (Exception)` 兜底 —— 而**"兜底"通常意味着"按最坏情况处理"（重试），这在语义上可能是完全错误的。**

**一个需要说明的"没做的优化"：** 可以去掉 `alreadyConsumed` 预查，只保留 `insert` + catch，这样能少一次 `SELECT`。**我评估后没有做**，因为：
- 预查在非并发的重复场景下**不产生任何写操作**（避免了撞唯一键带来的 InnoDB 唯一索引锁等待）；
- 它给了更清晰的日志上下文；
- 在消费端（非热路径）这个取舍是划算的。

**"简化 = 更正确"只在"简化能消除真实竞态"时成立**；这里的正确性已经由 `insert + catch` 提供了，预查只是优化。**不为"少一次查询"删掉它，是一个刻意的判断。**

---

### 缺陷 ④：限流 key 不含规则名，规则之间串额度

**发现方式：** **真实冒烟测试**（这一步是决定性的 —— 单接口压测永远发现不了这个 Bug）。

**现象：** 用户**先浏览 8 次商品详情，再点秒杀**，秒杀请求被拒绝，返回 **42900**（限流）。

**根因：** 修改前的 key 格式是 `rate:limit:{dimension}:{target}`，**不包含规则名**：
```
product-detail 规则：IP=127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值 50/s）
seckill       规则：IP=127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值  5/s）
                                  ↑ 同一个 key！同一个 ZSET！
```
8 次商品详情请求（远低于 50/s 的阈值，完全放行）被记进了这个 ZSET；紧接着的秒杀请求读到 **`ZCARD = 8 >= 5`** → **正常用户被误判为刷接口**。

**⭐ 为什么这个 Bug 特别值得讲：它的复现条件极为苛刻。**
> **只有"先访问某个大额度的接口、再访问小额度接口"才会触发。单独压测秒杀接口永远发现不了** —— 因为单独压测时这个 key 里只有秒杀自己的请求，计数是正确的。
> **它是靠模拟真实用户连续操作（先逛商品、再点秒杀）的冒烟测试才暴露的，而不是靠单接口压测。**

**修复：把规则名加进 key。**
```java
// ✅ 修改后
public static String rateLimit(String rule, String dimension, String target) {
    return "rate:limit:" + rule + ":" + dimension + ":" + target;
}
public static String tokenBucket(String rule, String dimension, String target) {
    return "rate:token:" + rule + ":" + dimension + ":" + target;
}
```
```java
// RedisRateLimiter：方法签名增加 rule 参数
public boolean tryAcquireSlidingWindow(String rule, String dimension, String target,
                                       int limit, Duration window) {
    String key = RedisKeys.rateLimit(rule, dimension, target);
    ...
}
```
```java
// RateLimitAspect：把注解上的规则名传下去
boolean allowed = doAcquire(rateLimit.rule(), rule, dimension, target);
...
private boolean doAcquire(String ruleName, MallProperties.Rule rule,
                          RateLimitDimension dimension, String target) { ... }
```
**注意 `RateLimitAspect` 用的是 `rateLimit.rule()`（注解上的字符串）而不是从配置对象推导** —— 因为配置对象本身不携带"自己叫什么名字"，**注解字符串是唯一可靠的名字来源。**

**修复后的语义更清晰：**
| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| 不同规则、同一维度 | ❌ 共享一份额度（**误伤用户**） | ✅ 完全隔离，各自维护额度 |
| 同一 rule 被多个接口引用 | 恰好共享（但和上面无法区分，**共享是偶然的**） | ✅ **有意共享** —— 正是"一个规则多处复用、只维护一份配置"想要的效果 |

**回归测试：**
```java
@Test @DisplayName("不同规则在同一维度上互不串额度（真实 Bug 的回归测试）")
void differentRulesDoNotShareQuota() { ... }
```
**这条测试的价值在于它复现的是"跨接口的交互"，而不是单接口的行为。** 我把它写成"先用大额度规则打若干次，再用小额度规则打一次，断言后者不受前者影响" —— 这样任何一次"把 rule 从 key 里删掉"的重构都会被立刻拦下。

**⭐ 从这条测试提炼出的教训（这一条我会在面试里专门讲）：**
> **单元测试和单接口压测的盲区是"组件之间的隐式耦合"。**
> 写完一个 Bug 的回归测试时，要问自己："**我测的是单点行为，还是那个真正导致 Bug 的交互？**" —— 如果测试写的是"秒杀限流 5/s 生效"，这个 Bug **永远不会被发现**。
> **一个 Bug 的回归测试，必须复现它当初的触发路径，而不是复现它所在的函数。**

**代价：** key 数量乘以规则数（3 倍）。**这是一个"正确性优先于内存"的取舍**，而且这些 key 都有 TTL（窗口过期即删），不会长期占用内存。

---

### 缺陷 ⑤（顺带记录）：单线程 `TaskScheduler` 抢走了所有 `@Scheduled` 的调度权

**发现方式：** 看启动日志。在日志里看到 `mall-cache-double-delete-1` 这个线程正在执行**「订单超时兜底扫描」** —— **线程名和任务名完全对不上**，这就是问题的入口。

**根因：** Spring Boot 的 `TaskSchedulingAutoConfiguration` **只会在容器里不存在 `TaskScheduler` Bean 时才自动装配一个**。一旦我们自己定义了任何一个 `ThreadPoolTaskScheduler`，自动装配就被抑制了，而 `@EnableScheduling` 会把那个 Bean **当成所有定时任务的调度器**。

本项目原本只定义了一个「延迟双删专用」的**单线程**调度器，于是 **对账、预热、超时兜底、投递补偿这 4 个 `@Scheduled` 任务全部挤在同一根线程上排队执行**，而且和延迟双删互相阻塞。

**危害：一个任务卡住会让所有定时任务停摆。** 比如对账任务扫到一张大表卡了 3 分钟，期间的**超时关单兜底也跟着停了** → 订单长时间停留在"待支付"、库存被占用。

**修复：拆成两个职责清晰的调度器。**
| Bean | 规格 | 用途 |
| --- | --- | --- |
| **`taskScheduler`** | **4 线程 + `@Primary`** | 所有 `@Scheduled` 任务 |
| `cacheDoubleDeleteScheduler` | 1 线程（`@Qualifier` 显式注入） | 只服务延迟双删 |

```java
@Bean(name = "taskScheduler")
@Primary
public ThreadPoolTaskScheduler mallTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("mall-sched-");
    scheduler.setRemoveOnCancelPolicy(true);
    // 全局兜底异常处理：@Scheduled 方法抛出的异常如果没人接，
    // Spring 只会打一行日志，而且默认行为是【停止后续调度】——
    // 一个偶发异常就会让这个任务永远不再执行。这是最容易埋雷的地方。
    scheduler.setErrorHandler(t -> log.error("定时任务执行失败（已捕获，后续调度不受影响）", t));
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(10);
    scheduler.initialize();
    return scheduler;
}
```
**三个关键点：**
1. **Bean 名字取成 `taskScheduler`** —— `ScheduledAnnotationBeanPostProcessor` 会**优先按这个名字查找**；加 `@Primary` 双保险，避免类型注入歧义；
2. **独立的线程名前缀 `mall-sched-`** —— 让 `jstack` 和日志能一眼区分"定时任务"和"延迟双删"。**原来的 Bug 之所以能被发现，就是因为线程名和任务名对不上；修复后这个信号被保留了。**
3. **`setErrorHandler(...)` 是修复过程中发现的额外收获** —— `@Scheduled` 方法抛出的未捕获异常，Spring 默认会**停止后续调度**。项目里每个 `@Scheduled` 方法体已经包在 try-catch 里（`PendingMessageRetryJob` 的注释明确写了"定时任务抛异常会导致后续调度被抑制（Spring 默认行为），所以必须自己兜住"），**加上这个全局 `ErrorHandler` 就是双保险。**

**⭐ 这个缺陷的教训：框架的"自动装配"是有条件的，而条件不满足时是静默的。**
`TaskSchedulingAutoConfiguration` 的 `@ConditionalOnMissingBean(TaskScheduler.class)` 意味着"**你只要定义了一个，就接管了全部**"。
**"我定义这个 Bean 只是给我自己用的"这个想法，在 Spring 的自动装配机制下是不成立的。** 这也是 Q26（`refreshAfterWrite` 会和自己的分布式逻辑打架）、Q38（容器重试会和业务重试叠加）的同一个主题：**框架提供的便利，往往是它替你做了某个决定；当你的系统需要不同的决定时，必须显式地接管它。**

---

### 测试数量与覆盖面的演进

这些缺陷的修复一共新增了 **3 个测试用例**，测试总数从 **40 个增加到 43 个**：

| 新增用例 | 所属测试类 | 防的是什么缺陷 |
| --- | --- | --- |
| `productCacheKeysMustShareHashSlot` | `RedisClusterSlotTest` | 缺陷 ① 的回归 |
| `differentProductsSpreadAcrossSlots` | `RedisClusterSlotTest` | 缺陷 ① 的"矫枉过正"（tag 不带 ID 导致全塞一槽） |
| `differentRulesDoNotShareQuota` | `RedisRateLimiterTest` | 缺陷 ④ 的回归 |

**当前测试类清单（8 个）：**
`SeckillStockConcurrencyTest`（5 个用例）、`ProductCacheTest`（6）、`OrderIdempotencyTest`（6）、`OrderTimeoutTest`（6）、`AuthFlowTest`（7）、`RedisRateLimiterTest`（6）、`RedisClusterSlotTest`（7）、以及 `BaseIntegrationTest`（测试基类，提供 `prepareActivity` 等公共设施）。**合计 43 个 `@Test`。**

**⭐ 关于"测试数量"这个数字，我会在面试里主动加一句限定：**
> "43 个用例听起来不多，但**其中 3 个是这 4 个缺陷的回归测试** —— 我认为'测试用例里有多少个是被真实 Bug 催生的'比'总共有多少个用例'更能说明测试的有效性。
> **一个好的测试套件不是由覆盖率驱动的，而是由故障驱动的。** 我现在的套件里，并发正确性（`SeckillStockConcurrencyTest`）和故障回归（这 3 个新用例）是我最看重的部分，其余的接口测试更像是防重构的护栏。"

**如果要继续提高测试的有效性，我会补的方向（诚实地列出没做的）：**
1. **缺陷 ② 的自动化回归**：用 Testcontainers 或 Toxiproxy **真正把 DB 停掉**，然后断言"死信消息没有被 ACK，且 DB 恢复后被正常消费"。**当前这个修复只有手工演练，没有自动化测试** —— 因为它需要一个"可编程停止的 DB"，超出了当前集成测试基座的能力。
2. **缺陷 ⑤ 的自动化回归**：断言"两个调度器不是同一个 Bean"、"`@Scheduled` 用的是 4 线程的那个"。**这类"配置/Boot 装配"类的缺陷最容易在重构中被重新引入，而单测最难覆盖。**

**"我知道我的测试盲区在哪里"** —— 这比"我有 43 个测试"更值得说。

---

## 十五、秒杀扩展与新中间件（7 题）

> 这一节覆盖后来扩展出来的两种抢购形态（预约抽签、设备风控）、库存分桶，以及搜索/网关/分片。
> **每题的"证据"都指向仓库里真实存在的测试或代码**，面试时可以直接说"这有测试守着"。

### Q106. 预约抽签是怎么削峰的？资格码为什么要放 Redis？

**结论：** 抽签把「抢购」拆成两段，让进入扣减链路的请求量从**人群量级**降到**库存量级**。

```text
① 预约（活动开始前，请求可分散到几小时/几天）
② 开奖（后台批处理，与用户请求完全解耦）
③ 中签者凭资格码下单 —— 只有中签者能进入 Lua 扣减
```

十万人的活动、两百件库存：直接抢购是 10 万次 `EVAL`，抽签后是 **200 次扣减 + 1 次批处理**。

**资格码为什么放 Redis 而不是只放数据库：** 下单是热路径。如果每次下单都回查
`t_seckill_reservation`，等于把数据库又放回了秒杀链路。码同步到 Redis 集合后，
扣减脚本可以在**同一个原子操作**里完成"校验 + 消费 + 扣库存"。

**证据：** `ReservationLotteryTest#drawSelectsWinnerCountEqualToStock`（中签人数=库存）、
`#qualifyCodeIsSingleUse`（同一个码用两次必失败）。

---

### Q107. 资格码怎么保证不会被用两次？

**结论：** 用 `SREM` 的返回值判断，校验和消费是**同一个原子操作**。

```lua
if mode == '1' then
    if redis.call('SREM', codesKey, qualifyCode) == 0 then
        return 4   -- 不存在 / 已被消费
    end
end
```

**为什么不能用 `SISMEMBER` 判断后再在 Java 里标记已用：** 两步之间有窗口，
两个并发请求会**同时**判定"码有效"。用 `SREM` 返回 1/0 判断，天然互斥。

**为什么按用户分桶存码：** 分桶后扣减脚本只能操作同槽的 key，把所有人的码放一个全局集合会
CROSSSLOT；放各自桶里还有一个好处——每个码只存在一份，不可能"两个桶各一份、被用两次"。

---

### Q108. 为什么"库存检查"要放在"消费资格码"之前？

**结论：** 否则会出现**中签者手慢一步，库存没了，资格码却被吃掉**。

```lua
if tonumber(stock) <= 0 then return 1 end          -- 先判库存
if mode == '1' then ... SREM ... end               -- 再消费资格码
```

反过来写，用户看到的是"您没有中签资格"而不是"已售罄"——**投诉都解释不清**，
而且他确实中签了。这个顺序有专门的测试守着：`#soldOutDoesNotConsumeCode`
（先把库存抢空，再让中签者下单 → 返回 SOLD_OUT 且码仍在集合里）。

---

### Q109. 限流挡不住黄牛吗？设备风控怎么做？

**结论：** 限流按 IP/用户限速，挡不住「**一台设备、100 个账号、轮流点**」——
每个账号都合法、每次请求都在限额内。

**做法：** 按设备指纹收敛，同一设备在同一活动内只能中一次。

两个关键取舍：

1. **检查只读，成功后才占用名额**：如果检查时就写入标记，"已售罄"的失败请求也会把设备烧掉，
   等于**没抢到还把你拉黑**；
2. **设备号可选，没有就放行**：宁可漏防，也不能因为客户端老旧误杀正常用户（IP 限流兜底）。

**证据：** `SeckillRiskGuardTest` 的 6 个用例，包括"同一设备换大小写不能绕过"、
"活动维度隔离（A 活动用过不影响 B 活动）"。

---

### Q116. ES 搜索默认的 `OR` 语义会有什么问题？

**结论：** `multi_match` 默认 `operator=or`，而 standard 分词器把中文**按字切分**，
于是"不存在的商品"被拆成单字，`商`、`品` 几乎命中所有数据——**用户搜一个不存在的词，返回一堆无关结果**。

修复是显式 `"operator": "and"`：对商品搜索来说，**召回宁少勿滥**——
空结果用户能接受，全是噪声用户会直接离开。

**这个 bug 是 CI 抓到的**，而且抓到它的不是"能搜到"的正向用例，而是
`#noMatchReturnsEmpty` 这条**反向断言**。（记录在 [12-加固记录](./12-hardening.md) 第 10 条。）

---

### Q117. 搜索降级为什么要区分"ES 不可用"和"没搜到"？

**结论：** 因为这是两件完全不同的事：

```java
search() 返回 null     → ES 不可用，走数据库 LIKE（记 WARN）
search() 返回空列表     → 确实没有结果
```

混在一起（比如都返回空列表），故障就**不可观测**了：ES 挂了会被当成"没搜到"，
监控上完全看不出来。

---

### Q118. 库存分桶为什么在单机上没有收益？代价是什么？

**结论：** 因为 Redis 是**单线程**的——16 个桶仍然排在同一个线程后面。
收益只在 **Cluster** 下出现：不同桶因为 Hash Tag 不同，落在不同分片。

三个必须说清楚的代价：

1. **桶间负载由哈希决定**，不可控（同一用户永远落同一桶，否则一人一单失效）；
2. **不做跨桶回退**：你的桶卖完就售罄，即使别的桶还有货——**会少卖，不会超卖**；
3. 资格码只能写在用户自己的桶里（否则跨槽）。

**证据：** `BucketedSeckillTest`（4 桶、800 并发抢 200 件、成功数恰好 200）。
默认 `bucket-count=1`，行为与早期版本完全一致。

---

### Q119. 如果要做分库分表，分片键为什么必须包含 `user_id`？

**结论：** 因为 `uk_user_activity(user_id, activity_id, active_flag)` 是"一人一单"的**最后一道防线**。

分片键含 `user_id` → 同一用户的订单永远在同一分片 → **分片内的唯一索引 = 全局唯一索引**。
如果按 `order_no` 分片，这条约束就失去全局性，数据库兜底直接失效——
而它恰恰是 Redis 判定被绕过时的最后一道闸门。

**代价：** 按订单号查询要广播到所有分片（详情页、支付回调），
按活动统计要另建异构索引表。完整方案见 [13-扩容与演进](./13-scaling-and-evolution.md)。

---

## 附录：快速索引

### 按模块速查

| 模块 | 题号 | 数量 |
| --- | --- | --- |
| 秒杀与库存 | Q1–Q15 | 15 |
| 缓存 | Q16–Q31 | 16 |
| 消息队列与最终一致性 | Q32–Q55 | 24 |
| 分布式锁与并发 | Q56–Q65 | 10 |
| MySQL 与索引 | Q66–Q78 | 13 |
| 限流与安全 | Q79–Q90 | 12 |
| 系统设计与容量 | Q91–Q95 | 5 |
| 场景题 / 故障演练 | Q96–Q103 | 8 |
| 项目追问 | Q104–Q110 | 7 |
| **合计** | | **110** |

### 五个必须记准的"金句"

1. **"宁可少卖，绝不超卖。"** —— 交易系统的第一原则（Q4）
2. **"数据库是权威，Redis 是可重建的投影。"** —— 决定所有一致性修复的方向（Q47）
3. **"消息负责快，扫描负责对，两者缺一不可。"** —— 任何消息触发的关键动作都必须有扫描兜底（Q53）
4. **"用对账代替同步写库来保证最终一致。"** —— 秒杀热路径零 DB 写的前提（Q44）
5. **"不持锁就不写共享状态。"** —— 缓存并发读写的纪律（Q28）

### 面试里最该主动讲的"缺陷修复闭环"（第十四节）

**不要讲"我的代码有什么缺陷"，要讲"我怎么发现并修掉它们的"。** 按下面这个顺序讲，一条 90 秒就能讲完：

| 讲什么 | 缺陷 ① | 缺陷 ② | 缺陷 ③ | 缺陷 ④ |
| --- | --- | --- | --- | --- |
| **怎么发现的** | 代码审查（审"哪些 key 出现在多 key 命令里"） | 故障推演（"MySQL 挂了整条链路会怎样"） | 异常语义审查（逐 catch 看上层怎么处理） | **真实冒烟测试**（先逛商品再点秒杀） |
| **根因一句话** | 两个 key 没有 Hash Tag → 集群下 `DEL` CROSSSLOT | `catch (Exception)` 把毒丸和瞬时故障混为一类 → ACK 丢消息 | `DuplicateKeyException` 没翻译 → 落进"可重试"分支 | key 里没有规则名 → 不同规则共享同一份额度 |
| **修法一句话** | 加 `{p:N}` Hash Tag，两 key 同槽 | 带 1 秒退避的 `nack(requeue=true)` | 翻译成 `DuplicateMessageException` | key 加上 `{rule}` 一层 |
| **回归测试** | 同槽 + 跨商品分散（两条互补） | 手工演练（**自动化未做**，要主动说明） | 由幂等用例覆盖 | `differentRulesDoNotShareQuota` |
| **最值钱的一句** | "我原以为不需要原子性，推演'部分成功'的后果后发现判断错了" | "不确定时选择可恢复的那一侧" | "异常类型的职责是把底层发生了什么翻译成上层该怎么办" | "单接口压测永远发现不了它" |

**⭐ 最后一句总结（我会这样收尾）：**
> "这 4 个缺陷里，**有 3 个不会让任何测试变红、不会抛异常、不会有错误日志** —— 它们只会让行为悄悄偏离预期。
> 我的收获是：**"代码审查"和"故障推演"能发现的缺陷，和"跑测试"能发现的缺陷，是两个几乎不重叠的集合。**
> 所以我现在会把这两种活动当成和写测试同等的日常工作，而不是'有空再说'。"

### 一句话版本（如果面试官只给你 30 秒）

> "这个项目里我发现了 4 个真实缺陷并全部修复了：**① 商品缓存的多 key `DEL` 在 Redis Cluster 下会 CROSSSLOT**（代码审查发现，加 Hash Tag 修复）；**② 死信消费者在 DB 不可用时会把消息 ACK 丢弃**（故障推演发现，改成带退避的 requeue）；**③ 并发重复消息会落到"可重试"分支而不是幂等分支**（异常语义审查发现，补上异常翻译）；**④ 限流 key 不含规则名导致不同规则串额度**（真实冒烟测试发现，key 里加上规则名）。
> **④ 是最有意思的 —— 它只在"先逛商品、再点秒杀"时才复现，单独压测秒杀接口永远发现不了。**
> 另外还顺带修了一个启动期的调度器缺陷：**单线程 `TaskScheduler` 被 `@EnableScheduling` 当成了所有定时任务的调度器**，一个任务卡住会让全部停摆。"
>
> **四句话讲完，每一句都有"发现方式 + 根因 + 修法"，这就是"代码审查能力 + 修复闭环"最直接的证据。**
