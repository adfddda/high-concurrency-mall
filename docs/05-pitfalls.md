# 05 · 踩坑记录（真实发生过的）

> 这份文档记录的是**本次开发过程中真实踩到、并且真的修掉了的坑**，不是从网上抄的"常见问题清单"。
> 面试里讲这些比背八股有效得多——它证明你真的动手跑过，而不是"看过架构图"。
>
> 每一条都按 `现象 → 定位过程 → 根因 → 修复 → 反思` 组织。

---

## 坑 1 · Maven 的 `-Dmaven.repo.local` 在命令行上不生效

**现象**
本地 Maven 仓库（`~/.m2`）因为环境限制不可写，于是想用
`mvn -Dmaven.repo.local=D:\work\.m2repo package` 把仓库改到别处。
结果 Maven 依然去写 `C:\Users\xxx\.m2\repository`，报 `AccessDeniedException`，
而且报错发生在**插件解析阶段**（`DefaultLifecycleTaskSegmentCalculator`），比正常构建早得多。

**定位过程**
1. 先怀疑是路径没传进去 → 用 `mvn help:evaluate -Dexpression=settings.localRepository` 打印，仍然指向旧路径。
2. 换成 `MAVEN_OPTS="-Dmaven.repo.local=..."` 后，`mvn -X` 输出 `Using local repository at D:\work\.m2repo`，**生效了**。

**根因**
Maven 读取本地仓库位置用的是 **JVM 系统属性**（system property），
而 `mvn -Dxxx=yyy` 传进去的是 **Maven 用户属性**（user property）。
两者在大多数场景下会互相回退，但**本地仓库的解析发生得非常早**，
早到用户属性还没被合并进来，于是就回落成了默认值。

**修复**
```powershell
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8 -Dmaven.repo.local=D:\work\.m2repo'
mvn -B package
```

**反思**
"命令行 `-D` 和 `MAVEN_OPTS` 里的 `-D` 不等价"这件事在 Maven 里是真实存在的。
判断一个 `-D` 到底有没有生效，正确做法是 `mvn -X` 看启动日志，而不是想当然。

---

## 坑 2 · MyBatis-Plus 3.5.9 升级后，分页插件直接编译不过

**现象**
```java
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
// → 找不到符号
```

**根因**
MyBatis-Plus 从 **3.5.9** 开始，把所有**依赖 JSqlParser 的插件**（分页、防全表更新删除、多租户、动态表名）
从 `mybatis-plus-extension` 拆到了独立模块 `mybatis-plus-jsqlparser`，starter **不再传递依赖**。

设计意图是好的：不依赖 JSqlParser 的项目可以少一个几十 KB 的传递依赖，也避免了 JSqlParser 大版本升级带来的不兼容。

**修复**
```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
    <version>${mybatis-plus.version}</version>
</dependency>
```

**反思**
这类"依赖被拆模块"的变更通常**不写进主版本号**（3.5.8 → 3.5.9 是小版本），
所以升级时必须读 release notes，不能只看能不能编译过——**这次是编译期就报错，算是运气好的**。
如果拆的是运行时才加载的东西，就会变成"编译过、启动炸"。

---

## 坑 3 · `SET key value KEEPTTL` 在 Redis 5.x 上直接报语法错误

**现象**
本地 Redis 是 **5.0.14**。回补库存的 Lua 脚本用了：
```lua
redis.call('SET', stockKey, newStock, 'KEEPTTL')   -- 想把库存加一但保留 TTL
```
在 6.0+ 上没问题，在 5.x 上会报错。

**根因**
`KEEPTTL` 选项是 **Redis 6.0** 才引入的。我的 docker-compose 用的是 `redis:7.2-alpine`，
但开发机上是 5.0——**"容器里是 7、本机是 5"是极其常见的环境错配**。

**修复**
```lua
-- 改用 INCR：天然保留 TTL，且全版本兼容
if tonumber(stock) >= totalStock then return tonumber(stock) end
local newStock = redis.call('INCR', stockKey)
```

**反思**
三个层面的收获：
1. **写法上**：`INCR` 本来就比"读出来加一再 `SET` 回去"更准确——它是原子自增，语义上就没有竞态。
2. **工程上**：写 Lua 脚本时要考虑**目标环境的最低 Redis 版本**，不能按自己容器里的版本写。
3. **流程上**：这是"本地验证"抓到的。如果只跑单测不打真实 Redis，或者只用 docker 里的 7.x 验证，
   这个坑会一直潜伏到某个客户的 5.x 环境才爆出来。**验证环境要覆盖真实环境的版本下限。**

---

## 坑 4 · L1 本地缓存让集成测试之间产生隐式耦合

**现象**
`ProductCacheTest` 里有一批用例**随机失败**：

```
expected: "DB"
 but was: "L1"
```

断言的是"第一次读商品详情应该回源数据库"，实际却拿到了 L1。
更诡异的是：**单独跑这个用例永远通过，全量跑就挂**。

**定位过程**
1. 第一反应是缓存没清干净 → 检查 `@BeforeEach`，发现已经 `flushDb` 清空了 Redis。
2. 那为什么还会命中？打印链路发现请求压根没走到 Redis。
3. 恍然大悟：**L1 是 Caffeine，活在 JVM 里；而 Spring 上下文在测试类之间是复用的。**
   上一个用例把商品塞进了 Caffeine，下一个用例即使清空 Redis 也照样在 L1 命中。

**根因**
**内存缓存也是测试状态的一部分**，但它的生命周期跟着 Spring 上下文走，
而数据库和 Redis 的生命周期跟着"进程/实例"走。清理逻辑只覆盖了后两者，漏掉了前者。

这类 Bug 的可怕之处在于它是**顺序相关**的：挂的用例会随执行顺序变化，看起来像"偶发"。

**修复**
在 `BaseIntegrationTest#resetState` 里把 L1 一并清掉：
```java
productCacheService.invalidateAllLocal();
seckillActivityService.evictAllLocal();
localSoldOutCache.clearAll();
```

**反思**
写集成测试时要问自己一句："**这个系统有几个存储层？我清了几个？**"
本项目有三层（MySQL / Redis / Caffeine），就必须清三层。
顺带一个配套的隔离措施：测试用 **Redis 15 号库**，避免 `flushDb` 把开发环境的缓存一起清掉。

---

## 坑 5 · 子串断言的前缀陷阱：`"logicalExpireAt":1` 匹配上了 `"logicalExpireAt":1790765596303`

**现象**
验证"逻辑过期后缓存被异步重建"的用例失败：

```
Expecting actual:
  "{"data":{...},"logicalExpireAt":1790765596303}"
not to contain:
  ""logicalExpireAt":1"
```

**根因**
重建**成功了**——新写入的时间戳 `1790765596303` 恰好**以 `1` 开头**。
我用的是 `doesNotContain("\"logicalExpireAt\":1")`，这是**子串匹配**，
而 `"logicalExpireAt":1790765596303` 确实包含子串 `"logicalExpireAt":1`。

**修复**
解析成字段再断言，而不是匹配字符串片段：
```java
long expireAt = JsonUtils.mapper().readTree(rebuilt).path("logicalExpireAt").asLong();
assertThat(expireAt).isGreaterThan(System.currentTimeMillis());
```

**反思**
**凡是结构化数据，就应该解析成字段再断言。**
子串匹配在"值恰好是另一个值的前缀"时会给出完全相反的结论，
而且失败信息看起来非常像"功能没生效"，会把人往错误方向带。

---

## 坑 6 · Mockito 的 inline mock maker 在受限环境里起不来

**现象**
35 个用例全部报同一个错，但项目代码里**一行 Mockito 都没写**：

```
Could not initialize plugin: interface org.mockito.plugins.MockMaker
Caused by: Could not self-attach to current VM using external process
```

**定位过程**
1. 全量报错但单类通过 → 先怀疑是 Spring 上下文缓存问题，排除。
2. 堆栈指向 `ResetMocksTestExecutionListener` → 这是 **Spring Boot Test 自动注册的**监听器，
   它在每个测试方法前后调用 `Mockito.isMock(bean)`，从而触发 Mockito 插件初始化。
3. 也就是说：**只要用 `@SpringBootTest`，就会用到 Mockito，哪怕你没写一个 mock。**

**根因**
Mockito 5 默认使用 **inline mock maker**，它需要通过 **ByteBuddy 动态 attach agent** 到当前 JVM。
在受限的执行环境里，进程内 attach 被禁用，ByteBuddy 退化成"启动外部进程 attach"，而外部进程方式也被阻断。

**修复**
```xml
<argLine>-Djdk.attach.allowAttachSelf=true</argLine>
```

**反思**
1. 依赖的"隐式使用"很容易被忽略——你的代码没 import，不代表运行时没加载。
2. 排查这类问题要看**所有错误的公共根因**：35 个错误信息完全一样，那它一定不在业务代码里，
   而在测试基础设施里。盯着第一个堆栈的**最深层**（`Caused by`）比看表层有用得多。

---

## 坑 7 · Redis 5.x 不支持和 `flushDb` 无关，但 Redisson 版本需要留意

**现象**
无。这是**预防性记录**：Redisson 3.37 的部分功能依赖较新的 Redis 命令。

**处理**
- 数据读写全部走 `StringRedisTemplate`（只用到 SISMEMBER / SADD / DECR / ZADD / HSET 等 Redis 5 就有的命令）；
- Redisson **只用** `RLock` 和 `RBloomFilter`，它们的底层是 `SET NX` 与 `SETBIT/GETBIT`，兼容性最好；
- docker-compose 里锁定 `redis:7.2-alpine`，README 明确写出最低版本要求。

**反思**
引入中间件客户端时，要明确"**我用到了它的哪些能力、这些能力依赖服务端的什么版本**"。
把版本要求写进文档，比出事之后再排查便宜得多。

---

## 坑 8 · 消息已成功消费，但 Confirm 超时导致库存被错误回补（设计缺陷，非运行时报错）

**现象**
这是一个**在写代码时通过推演发现的设计缺陷**，没有等到线上才暴露：

```
broker 已把消息路由给消费者 → 消费者成功创建订单 → 事务提交
但 Confirm 帧丢失 / 因网络延迟超过 confirmTimeout
→ 超时扫描任务判定"投递失败"，触发库存回补
→ 结果：订单在、库存也还回去了 = 超卖
```

**根因**
把 Confirm 超时**等价于**"消息没送达"。这两件事在有损网络下并不等价。

**修复**
在补偿入口增加**第一层判断：先查订单是否已存在**。

```java
boolean orderExists = orderMapper.exists(...eq(Order::getOrderNo, msg.getOrderNo()));
if (orderExists) {
    log.warn("订单已存在，跳过库存补偿（避免超卖）");
    return false;
}
```

这也正是死信消费者必须做的同一件事——**判断依据必须是数据库里的真实结果，而不是"消息进了死信/超时"这种间接信号**。

**反思**
设计补偿逻辑时，永远要问："**如果这个补偿判断错了，后果是什么？**"
- 判断成"要补偿"但其实不该补 → **超卖**（资损）
- 判断成"不用补偿"但其实该补 → **少卖**（可通过对账修）

两种错误的代价完全不对称，所以判断应该**偏向"不补偿"**，并用对账作为最后的安全网。

---

## 坑 9 · 用 `VALUES()` 在 MySQL 8 里会有 deprecation 警告

**现象**
`INSERT ... ON DUPLICATE KEY UPDATE col = VALUES(col)` 在 MySQL 8.0.20+ 会产生废弃警告。

**处理**
保留 `VALUES()` 写法（8.0.19+ 都支持，兼容性最好），并在 SQL 里加注释说明。
如果要彻底消除警告，可以改用行别名语法：

```sql
INSERT INTO t (...) VALUES (...) AS new
ON DUPLICATE KEY UPDATE col = new.col
```

**反思**
小版本升级带来的废弃警告要在 CI 里被看到，否则两年后升级大版本时就是一片红。

---

## 坑 10 · 限流 key 不含规则名，导致不同规则之间"串额度"

**现象**
冒烟测试里，用户只是先浏览了几次商品详情，紧接着点秒杀就被判"请求过于频繁"（返回 42900）。
日志里能定位到具体维度：

```
WARN c.l.mall.ratelimit.RateLimitAspect - 触发限流 api=SeckillController.seckill
     dimension=IP target=127.0.0.1 rule=seckill
```

**但这是本次运行第一次调用秒杀接口**，IP 维度的计数怎么可能会满？

**定位过程**
1. 查 Redis：`keys rate:*` 只看到一个令牌桶 key，没有滑动窗口的 ZSET。
2. 一开始以为是"key 过期了"（窗口 TTL 只有 2 秒），无法证伪。
3. 转头去看**规则配置**，才意识到问题所在：

```
product-detail 规则：IP + 127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值 50/s）
seckill        规则：IP + 127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值  5/s）
```

**两个规则的 key 完全一样。** 冒烟测试前面调了 8 次商品详情接口，
这 8 个请求被记进同一个 ZSET；轮到秒杀时脚本读到 `ZCARD = 8 >= 5`，
于是把正常用户误判成了刷接口。

**根因**
`RedisKeys.rateLimit(dimension, target)` 生成 key 时**漏掉了规则名**。
只要两个规则使用同一个维度（本项目里 IP 维度被 `product-detail` 和 `seckill` 共用），
它们的计数就会混在一起，**阈值小的那个规则实际上在替阈值大的规则"背锅"**。

**修复**
```java
public static String rateLimit(String rule, String dimension, String target) {
    return "rate:limit:" + rule + ":" + dimension + ":" + target;
}
```
`RedisRateLimiter` 与 `RateLimitAspect` 的方法签名相应增加 `rule` 参数，
并补上回归测试 `RedisRateLimiterTest#differentRulesDoNotShareQuota`。

**反思**
1. **这个 Bug 只在"先访问大额度接口、再访问小额度接口"时才复现。**
   单独压测秒杀接口永远发现不了它——因为单测时那个 ZSET 里只有秒杀自己的请求。
   **它需要一次真实的、跨接口的端到端操作序列才能被触发**，这也是冒烟测试的价值所在。
2. 事后看，把 `rule` 放进 key 不仅修了 Bug，语义也更清晰了：
   同一个 rule 被多个接口引用时它们**有意共享**一份额度——这正是"一个规则多处复用"的设计意图。
3. 教训是通用的：**凡是参与"资源配额"的 key，都必须包含配额的定义者（这里是规则名），
   而不是只包含被限流的对象。**

---

## 坑 11 · 多 key `DEL` 在 Redis Cluster 下会 CROSSSLOT（缓存删不掉，且不报错）

**现象**
无运行时报错。这是**代码审查时发现的设计缺陷**：

```java
// ProductCacheService.invalidate
redis.delete(List.of(
        RedisKeys.productCache(productId),      // cache:product:1001
        RedisKeys.productNullCache(productId))); // cache:product:null:1001
```

**根因**
`DEL` 是**多 key 命令**，Redis Cluster 要求所有 key 同槽。
而这两个 key 都没有 Hash Tag：

```
cache:product:1001        → 槽 A
cache:product:null:1001   → 槽 B（大概率不同）
DEL <A> <B>               → CROSSSLOT Keys in request don't hash to the same slot
```

**这个 Bug 最阴险的地方在于它的失败是静默的**：
缓存删不掉不会抛异常到业务层（异常在异步的 AFTER_COMMIT 监听器里被吞掉了），
表现只是"用户一直读到旧数据"，直到 TTL 到期才自愈。
**"不报错但数据不对"远比"直接抛异常"难排查。**

**更值得警惕的是**：我在秒杀的三件套 key 上专门做了 Hash Tag 并写了测试，
却在商品缓存上原封不动地踩了同一个坑。
说明"一次性设计正确"是不可靠的，**必须有一个覆盖所有多 key 操作的检查机制**。

**修复**
```java
public static String productTag(Long productId) { return "{p:" + productId + "}"; }
public static String productCache(Long id)     { return "cache:product:" + productTag(id); }
public static String productNullCache(Long id) { return "cache:product:null:" + productTag(id); }
```
两个 key 共用 `{p:1001}` → 同槽 → 一条 `DEL` 安全。
并新增单测 `RedisClusterSlotTest#productCacheKeysMustShareHashSlot`
（用 `sameSlot()` 直接断言）与 `#differentProductsSpreadAcrossSlots`（防数据倾斜）。

**反思**
把"集群可用性"做成**测试断言**而不是**代码习惯**，是唯一可靠的做法。
只要断言在，下次有人重构时去掉了 Hash Tag，CI 立刻变红。

---

## 坑 12 · 单线程 `ThreadPoolTaskScheduler` 被 Spring 当成所有 `@Scheduled` 的调度器

**现象**
应用启动后看日志里的线程名，发现了诡异的一幕：

```
[mall-cache-double-delete-1] OrderMapper.selectList - SELECT ... FROM t_order
                             WHERE (status = ? AND expire_time <= ?) LIMIT 200
```

**一个名字叫「缓存延迟双删」的线程，正在跑「订单超时兜底扫描」。**

**定位过程**
1. `ThreadPoolConfig` 里只定义了一个 `ThreadPoolTaskScheduler` Bean，
   本意是给 `ProductCacheInvalidator` 的延迟双删专用（单线程足够）。
2. 去查 Spring Boot 的 `TaskSchedulingAutoConfiguration`，发现它提供的
   `ThreadPoolTaskScheduler` 带 `@ConditionalOnMissingBean(TaskScheduler.class)`
   —— **只要容器里已经有一个 `TaskScheduler`，自动装配就被抑制了**。
3. 而 `@EnableScheduling` 需要一个调度器，于是它把容器里唯一的那一个拿来用了。

**根因**
一个"职责单一的单线程调度器"被隐式提升成了"全局调度器"。
后果是：**对账、预热、超时兜底、投递补偿这 4 个 `@Scheduled` 任务
全部挤在同一个线程上排队，而且和延迟双删互相阻塞。**

危害在于**一个任务卡住会让所有任务停摆**：比如对账任务扫到一张大表卡了 3 分钟，
期间的超时关单兜底也跟着停了——订单会长时间停留在待支付状态，
而日志里看不出任何异常（任务只是在"等"）。

**修复**
```java
@Bean(name = "taskScheduler")     // 名字刻意叫 taskScheduler，Spring 会优先按名字查找
@Primary
public ThreadPoolTaskScheduler mallTaskScheduler() { /* poolSize=4, 全局异常处理器 */ }

@Bean(name = "cacheDoubleDeleteScheduler")
public ThreadPoolTaskScheduler cacheDoubleDeleteScheduler() { /* poolSize=1 */ }
```
延迟双删那个通过 `@Qualifier("cacheDoubleDeleteScheduler")` 显式注入。

**顺带修了第二个雷**：`@Scheduled` 方法抛异常时，Spring 的默认行为是
**停止该任务的后续调度**——一个偶发异常就会让这个任务永远不再执行。
所以给调度器配了 `setErrorHandler`，把异常兜住，保证任务永远活着。

**反思**
1. **"自动配置的条件"必须被理解成"我的 Bean 会不会顶掉它"。**
   `@ConditionalOnMissingBean` 是一把双刃剑：方便扩展，也容易在不经意间关掉默认行为。
2. **看线程名是排查调度问题最快的手段。** 如果当初线程名是 `pool-3-thread-1`，
   这个 Bug 可能要等到线上某个任务莫名停止才会被发现。

---

## 坑 13 · 死信消费者 ACK 掉消息 + 并发重复消息落到"可重试"分支

这两个是同一轮代码审查里发现的，都属于**异常语义不够精确**导致的错误决策。

### 13.1 死信消费者在数据库不可用时把消息 ACK 掉了

**问题代码**
```java
} catch (Exception e) {
    log.error("处理死信消息异常", e);
    channel.basicAck(deliveryTag, false);   // ← 直接丢弃
}
```

**为什么错**
死信消费者要做的第一件事是**查订单是否已落库**。如果这时数据库不可用，
查不了 → 抛异常 → 被 ACK 掉。结果是：**订单永远不会创建、库存不会归还、
用户拿不到任何结果，而系统里只剩一行 ERROR 日志。**
"用一个 ACK 把问题关掉"看起来干净，实际是把一次可恢复的故障变成了永久的数据不一致。

**修复**
```java
log.error("处理死信消息失败，将 requeue 等待下游恢复 ...", e);
metrics.mqEvent("seckill", "dlq_requeue");
Thread.sleep(1000);                        // 退避，避免 broker 瞬时自旋
channel.basicNack(deliveryTag, false, true); // requeue
```
并在注释里写清了权衡：毒丸消息会反复重投，
生产环境应给死信消息加 `retryCount` 头，超阈值后进"人工处理队列"（parking queue）并告警。

### 13.2 并发重复消息落到了"可重试"分支，白白多走一次重试

**问题代码**
```java
if (mqMessageService.alreadyConsumed(msg.getMessageId())) {
    throw new DuplicateMessageException(msg.getMessageId());
}
mqMessageService.insertConsumeRecord(msg);   // ← 并发时这里会抛 DuplicateKeyException
```

**为什么错**
`DuplicateMessageException` 会被消费端识别为"幂等命中，直接 ACK"；
但 `DuplicateKeyException` 是通用数据访问异常，会被识别为"瞬时故障，走延迟重试"。
两个消费者同时处理同一条消息时（都通过了上面的查询），
后插入的那个会抛出 `DuplicateKeyException`，于是：

```
重试队列 → 几秒后重新消费 → 这次 alreadyConsumed 返回 true → 才被识别为重复 → ACK
```

**结果是白白多花两次重试和几秒延迟，还会在监控上制造"消费失败率上升"的假告警，
把真正的问题淹没掉。**

**修复**
```java
try {
    mqMessageService.insertConsumeRecord(msg);
} catch (DuplicateKeyException e) {
    // 并发窗口：另一个消费者抢先插入了同一条消息 → 这就是幂等命中
    throw new DuplicateMessageException(msg.getMessageId());
}
```

**反思**
这两个 bug 的共同点是：**"捕获了什么异常"直接决定了"系统做出什么决策"**。
在可靠消息系统里，异常的**语义分类**（可重试 / 不可重试 / 幂等命中 / 需要人工介入）
比异常本身的信息量更重要，必须在抛出点就分清楚，而不是让消费端去猜。

---

## 排查方法论总结

从这 13 个坑里能提炼出几条可复用的经验：

| 经验 | 对应坑 |
|---|---|
| **"命令行参数没生效"要去看启动日志，不要靠推理** | 坑 1 |
| **小版本升级必须读 release notes，依赖被拆模块是常见操作** | 坑 2 |
| **验证环境要覆盖真实环境的版本下限，而不是"我容器里是多少"** | 坑 3 |
| **问"这个系统有几个存储层，我清了几个"** | 坑 4 |
| **结构化数据解析后断言，不要匹配字符串片段** | 坑 5 |
| **N 个用例报同一个错 → 问题在测试基础设施，不在业务代码；看 `Caused by` 的最深层** | 坑 6 |
| **明确"我用到的能力依赖服务端什么版本"，并写进文档** | 坑 7 |
| **设计补偿逻辑时先问"判断错了会怎样"，让错误偏向可修复的那一侧** | 坑 8 |
| **顺序相关的偶发失败 = 状态泄漏，优先怀疑缓存与静态变量** | 坑 4 |
| **配额类 key 必须包含"配额的定义者"，否则规则之间会串额度** | 坑 10 |
| **凡涉及多 key 的命令，都要检查是否同槽；把检查写成测试而不是习惯** | 坑 11 |
| **`@ConditionalOnMissingBean` 是双刃剑：你的 Bean 可能悄悄顶掉了默认行为** | 坑 12 |
| **异常的分类（可重试/不可重试/幂等命中）必须在抛出点就定清楚** | 坑 13 |
| **"看线程名"是排查调度问题最快的手段** | 坑 12 |
| **需要真实操作序列才能触发的 Bug（如跨接口串额度），只能靠端到端冒烟测试兜住** | 坑 10 |
