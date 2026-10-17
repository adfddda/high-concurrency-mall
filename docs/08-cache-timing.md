# 各模块缓存读写时序

> 本文档按照「用户请求 → 各步骤 → 缓存读写」的时序写法，梳理高并发商城项目中各个核心模块的缓存交互细节。
> 每个步骤标注：读/写哪个缓存（Caffeine L1 / Redis L2 / MySQL）、key 格式、内容、TTL。

---

## 目录

- [一、认证模块](#一认证模块)
  - [1.1 用户注册](#11-用户注册)
  - [1.2 用户登录](#12-用户登录)
  - [1.3 已登录请求鉴权（拦截器）](#13-已登录请求鉴权拦截器)
  - [1.4 用户登出](#14-用户登出)
- [二、限流模块](#二限流模块)
  - [2.1 令牌桶限流（登录接口）](#21-令牌桶限流登录接口)
  - [2.2 滑动窗口限流（商品详情/秒杀接口）](#22-滑动窗口限流商品详情秒杀接口)
- [三、商品多级缓存模块](#三商品多级缓存模块)
  - [3.1 商品详情读取（多级缓存 + 逻辑过期 + 异步重建）](#31-商品详情读取多级缓存--逻辑过期--异步重建)
  - [3.2 新增商品（布隆过滤器 + 延迟双删）](#32-新增商品布隆过滤器--延迟双删)
  - [3.3 修改商品（Cache Aside + 延迟双删）](#33-修改商品cache-aside--延迟双删)
  - [3.4 缓存击穿互斥锁回源](#34-缓存击穿互斥锁回源)
- [四、秒杀模块](#四秒杀模块)
  - [4.1 活动预热](#41-活动预热)
  - [4.2 秒杀下单（全链路）](#42-秒杀下单全链路)
  - [4.3 秒杀结果查询](#43-秒杀结果查询)
- [五、线程池与定时任务](#五线程池与定时任务)
  - [5.1 秒杀活动预热定时任务](#51-秒杀活动预热定时任务)
  - [5.2 缓存延迟双删调度](#52-缓存延迟双删调度)
- [六、MQ 可靠性设计](#六mq-可靠性设计)
  - [6.1 整体架构：两条 MQ 链路](#61-整体架构两条-mq-链路)
  - [6.2 配置类：交换机、队列、绑定](#62-配置类交换机队列绑定)
  - [6.3 延迟重试队列](#63-延迟重试队列)
  - [6.4 死信队列（DLQ）](#64-死信队列dlq)
  - [6.5 生产者可靠发送](#65-生产者可靠发送)
  - [6.6 发送追踪：Confirm/Return/超时](#66-发送追踪confirmreturn超时)
  - [6.7 消费者：手动 ACK 与五种结果](#67-消费者手动-ack-与五种结果)
  - [6.8 超时判断机制](#68-超时判断机制)
  - [6.9 死信队列触发条件](#69-死信队列触发条件)
  - [6.10 补偿机制：三层防重](#610-补偿机制三层防重)
  - [6.11 MQ 完整调用链](#611-mq-完整调用链)
  - [6.12 秒杀下单 MQ 发送与消费时序（带缓存标注）](#612-秒杀下单-mq-发送与消费时序带缓存标注)
  - [6.13 订单超时关单链路时序（带缓存标注）](#613-订单超时关单链路时序带缓存标注)
  - [6.14 支付流程（模拟支付+条件更新）](#614-支付流程模拟支付条件更新)
  - [6.15 MQ 模块涉及的所有缓存汇总](#615-mq-模块涉及的所有缓存汇总)
- [七、用户上下文模块（ThreadLocal）](#七用户上下文模块threadlocal)
  - [7.1 已登录请求完整生命周期](#71-已登录请求完整生命周期)
- [八、订单模块](#八订单模块)
  - [8.1 秒杀订单创建（消费端落库）](#81-秒杀订单创建消费端落库)
  - [8.2 订单详情查询](#82-订单详情查询)
  - [8.3 订单列表查询](#83-订单列表查询)
  - [8.4 用户主动取消订单](#84-用户主动取消订单)
- [九、对账模块](#九对账模块)
  - [9.1 对账任务执行时序](#91-对账任务执行时序)
  - [9.2 漂移检测与在途泄漏检测](#92-漂移检测与在途泄漏检测)
- [十、MyBatis-Plus 配置](#十mybatis-plus-配置)
  - [10.1 三个拦截器](#101-三个拦截器)

---

## 一、认证模块

### 1.1 用户注册

```
用户提交注册表单（username + password）
    ↓
【第1步】参数校验（Bean Validation）
    不涉及缓存
    ↓
【第2步】BCrypt 加盐哈希密码
    不涉及缓存
    ↓
【第3步】INSERT 用户到 MySQL
    写 MySQL: user 表
    依赖唯一索引 uk_username 防并发重复
    ↓
【第4步】唯一索引冲突处理
    如果 uk_username 冲突 → 捕获 DuplicateKeyException → 返回"用户名已存在"
    不先查再插（先查再插有竞态窗口）
    ↓
【第5步】注册成功，返回
    不写缓存（用户数据登录时才加载）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| MySQL | 写 | `user` 表 | 用户记录（username, password_hash, ...） |
| MySQL 索引 | 依赖 | `uk_username` | 唯一索引，防并发重复注册 |

**关键设计：** 不做「先查用户名是否存在，再插入」，而是直接 INSERT 靠唯一索引兜底。因为先查再插在高并发下有竞态窗口，两个请求同时查到不存在，然后都插入，第二个会撞唯一索引——与其撞，不如直接插靠索引判断。

---

### 1.2 用户登录

```
用户提交登录表单（username + password）
    ↓
【第1步】参数校验
    不涉及缓存
    ↓
【第2步】查 MySQL 用户
    读 MySQL: user 表 WHERE username = ?
    用户不存在 → 返回"用户名或密码错误"（与密码错误同一错误码，防枚举）
    ↓
【第3步】BCrypt 校验密码
    不涉及缓存
    密码错误 → 返回"用户名或密码错误"
    ↓
【第4步】生成 JWT Token
    不涉及缓存（JWT 是无状态的，token 本身包含 userId、jti、过期时间）
    ↓
【第5步】返回 token
    不写缓存（登录态由前端存 token，每次请求带 Authorization 头）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| MySQL | 读 | `user` 表 | 用户记录，用于密码校验 |

**关键设计：** 用户不存在和密码错误返回**同一个错误码**，防止攻击者枚举哪些用户名已注册。JWT 是无状态的，登录时不写 Redis，登出时才写黑名单。

---

### 1.3 已登录请求鉴权（拦截器）

**文件：`LoginInterceptor.java`**

```
请求到达 Controller 之前
    ↓
【第1步】从请求头取 Token
    读 HTTP Header: Authorization: Bearer <token>
    没有 token → 放行（接口可能是公开的，由 @RequireLogin 注解控制）
    ↓
【第2步】解析 JWT
    不涉及缓存（本地解析，验证签名和过期时间）
    解析失败/已过期 → 放行或返回401（看接口是否要求登录）
    ↓
【第3步】检查 Token 黑名单
    读 Redis: auth:blacklist:{jti}
    key 存在 → token 已被登出/踢下线 → 返回401
    key 不存在 → 继续
    ↓
【第4步】把用户信息存入 ThreadLocal
    写 Caffeine(进程内 ThreadLocal): UserContext.HOLDER
    key = 当前线程，value = LoginUser(userId, username, jti)
    ↓
【第5步】执行业务逻辑（Controller → Service → Mapper）
    业务代码通过 UserContext.getUserId() 取当前用户
    ↓
【第6步】请求结束，清理 ThreadLocal（afterCompletion）
    删 Caffeine(进程内 ThreadLocal): UserContext.HOLDER.remove()
    必须清理！Tomcat 线程池复用，不清理会导致下一个请求读到上一个用户的身份
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| Redis | 读 | `auth:blacklist:{jti}` | 占位符 `"1"`，存在即表示已登出 | token 剩余有效期 |
| ThreadLocal | 写 | 当前线程 | `LoginUser(userId, username, jti)` | 请求结束即清理 |

**关键设计：**
- ThreadLocal 必须在 `afterCompletion` 里清理，否则 Tomcat 线程池复用时会越权
- 黑名单用 Redis key 存在性判断，自动过期，不需要手动删
- jti（JWT ID）是每个 token 的唯一标识，黑名单按 jti 拉黑，不是按 userId——这样只拉黑当前 token，不影响用户其他设备登录

---

### 1.4 用户登出

```
用户点击退出登录
    ↓
【第1步】从请求头取 Token
    读 HTTP Header: Authorization
    ↓
【第2步】有 token 的情况
    解析 JWT → 拿到 jti 和剩余有效期
    写 Redis: auth:blacklist:{jti} = "1"，TTL = token 剩余有效期
    ↓
【第3步】没有 token 的情况（前端清了本地存储，但用户还点了退出）
    从 ThreadLocal 兜底：UserContext.get().getJti()
    写 Redis: auth:blacklist:{jti} = "1"，TTL = accessTokenTtl（配置的默认有效期）
    ↓
【第4步】返回成功
    token 非法/已过期 → 不报错（"退出"这个诉求已经天然满足了）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| Redis | 写 | `auth:blacklist:{jti}` | 占位符 `"1"` | token 剩余有效期 |
| ThreadLocal | 读 | 当前线程 | `LoginUser.jti`（无 token 时兜底） |

**关键设计：** 两种获取 jti 的方式——解析传入的 token（精确的剩余有效期），或从 UserContext 兜底（用默认 TTL）。黑名单 key 过期后自动消失，不需要手动清理。

---

## 二、限流模块

### 2.1 令牌桶限流（登录接口）

**配置：`login` 规则，TOKEN_BUCKET，60s，limit=10，capacity=10，rate=0.5/s，IP维度**

```
请求到达（被 @RateLimit 注解拦截）
    ↓
【第1步】拼接限流 key
    key = rate:limit:login:IP:127.0.0.1
    （规则名 + 维度名 + 维度值）
    ↓
【第2步】执行 Redis Lua 令牌桶脚本
    读+写 Redis（一个 Lua 原子操作）：
      读 Redis Hash: rate:limit:login:IP:127.0.0.1
        字段: tokens（当前令牌数）, lastRefillTime（上次补充时间）
      计算: 距上次补充的秒数 × rate(0.5/s) = 应补充令牌数
      更新: tokens = min(capacity, tokens + 补充数)
      判断: tokens >= 1 ?
        是 → tokens -= 1，放行，返回剩余令牌数
        否 → 返回 -1（限流）
    ↓
【第3步】Lua 返回结果
    >= 0 → 放行，继续执行业务
    -1 → 抛限流异常，返回"操作太频繁"
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| Redis | 读+写（Lua原子） | `rate:limit:login:IP:{ip}` | Hash: `{tokens: 9.5, lastRefillTime: 1789012345000}` | 脚本内自动管理 |

**关键设计：**
- 令牌数和上次补充时间存在同一个 Hash key 里，Lua 一次操作完成，保证原子性
- rate=0.5/s 表示每2秒补充1个令牌，capacity=10 表示最多攒10个令牌（允许突发）
- 令牌桶允许突发：桶里有10个令牌时，用户可以1秒内连续发10个请求，之后每2秒才能再发1个

---

### 2.2 滑动窗口限流（商品详情/秒杀接口）

**配置：商品详情 `product-detail`，SLIDING_WINDOW，1s，limit=50，IP+API维度；秒杀 `seckill`，1s，limit=5，USER+IP维度**

```
请求到达（被 @RateLimit 注解拦截）
    ↓
【第1步】拼接限流 key
    商品详情: rate:limit:product-detail:IP:127.0.0.1:API:/api/product/1
    秒杀: rate:limit:seckill:USER:10086:IP:127.0.0.1
    ↓
【第2步】生成唯一 member
    member = 当前时间戳 + "-" + UUID
    （ZSET 的 member 必须唯一，否则同一毫秒的多个请求会被覆盖）
    ↓
【第3步】执行 Redis Lua 滑动窗口脚本
    读+写 Redis（一个 Lua 原子操作）：
      写 Redis ZSET: rate:limit:seckill:USER:10086:IP:127.0.0.1
        ZADD key 当前时间戳 member（把当前请求加入时间线）
      删 Redis ZSET: ZREMRANGEBYSCORE key 0 (当前时间戳 - 窗口1000ms)
        （删掉窗口外的旧请求记录）
      读 Redis ZSET: ZCARD key（统计当前窗口内的请求数）
      判断: count <= limit(5) ?
        是 → 放行，返回剩余额度
        否 → ZREM 删掉刚才加的 member，返回 -1（限流）
      设置 TTL: 窗口时间 + 缓冲
    ↓
【第4步】Lua 返回结果
    >= 0 → 放行
    -1 → 抛限流异常
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| Redis | 读+写（Lua原子） | `rate:limit:{rule}:{dimensions}` | ZSET: member=时间戳-UUID, score=时间戳 | 窗口时间 + 缓冲 |

**关键设计：**
- ZSET 的 score 是时间戳，用 ZREMRANGEBYSCORE 清理窗口外的数据，ZCARD 统计窗口内请求数
- member 用「时间戳+UUID」保证唯一，否则同一毫秒多个请求 ZADD 会覆盖，计数偏低
- 滑动窗口是**硬限制**：1秒内最多5次，第6次一定被拒，不允许突发

---

## 三、商品多级缓存模块

### 3.1 商品详情读取（多级缓存 + 逻辑过期 + 异步重建）

```
用户访问商品详情页 /api/product/{id}
    ↓
【第1步】查 L1 Caffeine 本地缓存
    读 Caffeine: product:detail:{productId}
    key = productId（Long），value = ProductDetailVO 对象
    TTL = 3秒（写入后过期）
    命中 → 直接返回，不碰 Redis/DB
    未命中 → 继续
    ↓
【第2步】查 L2 Redis 缓存
    读 Redis String: cache:product:detail:{productId}
    value = JSON（包含 data 和 expireAt 逻辑过期时间）
    物理 TTL = 随机抖动（防雪崩）
    未命中 → 走互斥锁回源（见3.4）
    命中 → 继续
    ↓
【第3步】判断逻辑是否过期
    比较: 当前时间 > value.expireAt ?
    未过期 → 回填 Caffeine → 返回数据
    已过期 → 继续
    ↓
【第4步】逻辑过期：立即返回旧数据 + 异步加锁重建
    立即返回旧数据给用户（用户不等）
    异步（cacheRebuildExecutor 线程池）：
      尝试获取 Redisson 分布式锁: lock:cache:rebuild:{productId}
      抢到锁 → 双重检查 Redis（可能别的线程已经重建了）
               → 查 MySQL: product 表
               → 写 Redis: cache:product:detail:{productId}（新逻辑过期时间）
               → 写 Caffeine: product:detail:{productId}
               → 释放锁
      没抢到锁 → 直接放弃（反正旧数据还能用，下次还有机会）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| Caffeine L1 | 读 | `product:detail:{productId}` | `ProductDetailVO` 对象 | 3秒 |
| Caffeine L1 | 写（回填） | `product:detail:{productId}` | `ProductDetailVO` 对象 | 3秒 |
| Redis L2 | 读 | `cache:product:detail:{productId}` | JSON: `{data: {...}, expireAt: 1789012345000}` | 物理TTL随机抖动 |
| Redis L2 | 写（重建） | `cache:product:detail:{productId}` | JSON: `{data: {...}, expireAt: 当前+逻辑过期时间}` | 物理TTL随机抖动 |
| Redis 锁 | 读+写 | `lock:cache:rebuild:{productId}` | Redisson 锁 | 自动续期 |
| MySQL | 读（重建时） | `product` 表 | 商品详情数据 | - |

**关键设计：**
- 逻辑过期 ≠ 物理过期：Redis key 不删，过期时间存在 value 里，过期后立即返回旧数据+异步重建
- 异步重建不让用户等，加锁防止并发打挂数据库
- Caffeine 3秒 TTL 是多实例不一致窗口的上界：A实例更新了缓存，B实例最多3秒后才能读到

---

### 3.2 新增商品（布隆过滤器 + 延迟双删）

```
管理员新增商品
    ↓
【第1步】开启数据库事务
    ↓
【第2步】INSERT 商品到 MySQL
    写 MySQL: product 表
    ↓
【第3步】加入布隆过滤器
    写 Redis 布隆过滤器: bloom:product
    BF.ADD bloom:product {productId}
    （防止新增商品后，缓存穿透查询直接打数据库）
    ↓
【第4步】发商品变更事件（Spring 事件）
    不涉及缓存，事件在事务提交后才触发
    ↓
【第5步】事务提交
    ↓
【第6步】AFTER_COMMIT：第一次删缓存
    删 Redis: cache:product:detail:{productId}
    （可能之前有人查过这个不存在的商品，缓存了空值占位）
    ↓
【第7步】延迟500ms：第二次删缓存（cacheDoubleDeleteScheduler 单线程调度）
    删 Redis: cache:product:detail:{productId}
    （堵住"事务提交前有读请求查到旧值/空值，提交后才写回缓存"的竞态窗口）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| MySQL | 写 | `product` 表 | 新商品记录 | - |
| Redis 布隆过滤器 | 写 | `bloom:product` | productId 加入过滤器 | 永久 |
| Redis | 删（第一次） | `cache:product:detail:{productId}` | 删除 key | - |
| Redis | 删（第二次，500ms后） | `cache:product:detail:{productId}` | 删除 key | - |

**关键设计：**
- 新增商品也要删缓存：因为可能之前有人查过这个不存在的商品ID，缓存了空值占位（短TTL），不删的话新增后用户还会查到"商品不存在"
- 延迟双删：第一次删在事务提交后，第二次等500ms，堵住竞态窗口
- 布隆过滤器在事务内加：保证商品入库后，过滤器里一定有这个ID，防止缓存穿透

---

### 3.3 修改商品（Cache Aside + 延迟双删）

```
管理员修改商品
    ↓
【第1步】开启数据库事务
    ↓
【第2步】UPDATE 商品到 MySQL
    写 MySQL: product 表
    ↓
【第3步】发商品变更事件
    ↓
【第4步】事务提交
    ↓
【第5步】AFTER_COMMIT：第一次删缓存
    删 Redis: cache:product:detail:{productId}
    删 Caffeine: product:detail:{productId}（本地缓存也要失效）
    ↓
【第6步】延迟500ms：第二次删缓存
    删 Redis: cache:product:detail:{productId}
    （单线程调度器 cacheDoubleDeleteScheduler）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| MySQL | 写 | `product` 表 | 更新商品记录 | - |
| Redis | 删（第一次） | `cache:product:detail:{productId}` | 删除 key | - |
| Caffeine | 删（第一次） | `product:detail:{productId}` | 失效本地缓存 | - |
| Redis | 删（第二次，500ms后） | `cache:product:detail:{productId}` | 删除 key | - |

**关键设计：**
- 用「删除缓存」而不是「更新缓存」：删除是幂等的，删错了大不了下次查库回填；更新可能用旧数据覆盖新数据
- Cache Aside 模式：先写库，再删缓存（不是先删缓存再写库）
- 延迟双删的500ms是经验值：覆盖"读请求查到旧值 → 写库提交 → 读请求把旧值写回缓存"的时间窗口

---

### 3.4 缓存击穿互斥锁回源

```
商品详情查询，L1/L2 都未命中（缓存击穿场景）
    ↓
【第1步】尝试获取 Redisson 分布式锁
    写 Redis: lock:cache:rebuild:{productId}
    tryLock(等待时间, 租约时间)
    ↓
【第2步】抢到锁的线程
    双重检查 Redis（可能别的线程已经重建好了）
      Redis 有数据 → 回填 Caffeine → 返回
      Redis 没数据 → 查 MySQL → 写 Redis → 写 Caffeine → 返回
    释放锁
    ↓
【第3步】没抢到锁的线程
    短暂等待（自旋/睡眠）
    再查一次 Redis：
      有数据 → 回填 Caffeine → 返回
      还是没数据（等待超时）→ 降级直查 MySQL → 返回
        注意：不回填缓存！
        （无锁线程写缓存可能用旧数据覆盖持锁者的新数据）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| Redis 锁 | 读+写 | `lock:cache:rebuild:{productId}` | Redisson 锁 | 自动续期 |
| Redis | 读（双重检查） | `cache:product:detail:{productId}` | 商品详情 JSON | - |
| Redis | 写（持锁线程） | `cache:product:detail:{productId}` | 商品详情 JSON | 随机抖动 |
| Caffeine | 写（回填） | `product:detail:{productId}` | `ProductDetailVO` | 3秒 |
| MySQL | 读 | `product` 表 | 商品详情 | - |

**关键设计：**
- 没抢到锁的线程等待超时后，直查 MySQL 但**不回填缓存**——因为无锁线程写缓存可能用旧数据覆盖持锁者刚写的新数据
- 双重检查：抢到锁后再查一次 Redis，可能别的线程已经重建好了，避免重复查库
- 互斥锁保证同一时间只有一个线程查数据库，防止缓存击穿把 DB 打挂

---

## 四、秒杀模块

### 4.1 活动预热

**文件：`SeckillPreheatService.java`，由 `SeckillPreheatJob` 定时触发（启动时 + 每分钟）**

> **注意**：代码里定义了 `lock:seckill:preheat:{id}`（分布式锁）和 `seckill:preheated`（已预热集合）两个 key，但实际预热流程中**并未使用**。预热是幂等的（重复写同样的值），多实例重复执行也不会出问题，所以没有加锁和去重。以下为实际代码流程。

```
定时任务触发（ApplicationRunner 启动时 + @Scheduled 每分钟）
    ↓
【第1步】查 MySQL：正在进行 + 未来10分钟内开始的活动
    读 MySQL: seckill_activity 表
    条件: end_time > now AND start_time < now + 10min
    ↓
【第2步】对每个活动，调用 preheat(activityId)
    单个活动预热失败不影响其他活动（try-catch 包住）
    ↓
【第3步】查 MySQL：活动详情 + 商品详情
    读 MySQL: seckill_activity 表 + product 表
    ↓
【第4步】计算 TTL = 活动剩余时间 + 1小时缓冲
    最少1小时（活动快结束时也不能TTL太短）
    ↓
【第5步】预热活动快照
    写 Redis String: seckill:activity:{activityId} = JSON(SeckillActivitySnapshot)
    TTL = 活动剩余时间 + 1小时
    ↓
【第6步】预热库存 + 清本地售罄缓存
    写 Redis String: seckill:stock:{act:activityId} = availableStock
    TTL = 活动剩余时间 + 1小时
    清 Caffeine: localSoldOutCache.invalidate(activityId)
    （必须清！否则库存回补后，本地还记着售罄，有库存也抢不到）
    ↓
【第7步】设置已购集合 TTL
    写 Redis: seckill:bought:{act:activityId} EXPIRE
    TTL = max(配置的boughtSetTtl, 活动剩余+1小时)
    （已购集合不能提前过期，否则用户能重复抢购）
    ↓
【第8步】清本地活动快照缓存
    清 Caffeine: activityService.evictLocal(activityId)
    （让新快照立刻对热路径可见，否则要等5秒本地缓存过期）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| MySQL | 读 | `seckill_activity` 表 | 即将开始/进行中的活动列表 | - |
| MySQL | 读 | `seckill_activity` + `product` 表 | 活动详情 + 商品详情 | - |
| Redis String | 写 | `seckill:activity:{N}` | 活动快照 JSON（活动名、商品名、价格、时间） | 活动剩余+1h |
| Redis String | 写 | `seckill:stock:{act:N}` | 可用库存数字，如 `"100"` | 活动剩余+1h |
| Redis Set | 设TTL | `seckill:bought:{act:N}` | 已购 userId 集合（预热时只设TTL，不创建空集合） | 活动剩余+1h |
| Caffeine | 删 | `localSoldOutCache`（key=activityId） | 清掉该活动的售罄标记 | - |
| Caffeine | 删 | `activityService.localCache`（key=activityId） | 清掉该活动的快照缓存 | - |

**关键设计：**
- 用 `availableStock` 而不是 `totalStock` 预热：重新预热时 availableStock 才是真实剩余量，用 totalStock 会把已卖出的库存「复活」导致超卖
- 不清已购集合：重新预热时清空已购集合会让买过的用户重新获得资格，然后在数据库层撞唯一键
- 三个核心 key（stock/bought/inflight）都带 Hash Tag `{act:N}`，保证 Redis Cluster 同槽
- 预热提前量10分钟：活动开始前10分钟就把数据加载到 Redis，开始瞬间不需要冷启动
- 预热后必须清两个 Caffeine 本地缓存：售罄标记 + 活动快照，否则本地旧数据会挡住新数据
- 实际代码未使用分布式锁和已预热集合：预热幂等，多实例重复执行无副作用，加锁反而增加复杂度

---

### 4.2 秒杀下单（全链路）

```
用户点击抢购 /api/seckill/activities/{activityId}/order
    ↓
【第1步】限流（@RateLimit 注解，滑动窗口）
    读+写 Redis ZSET: rate:limit:seckill:USER:10086:IP:127.0.0.1
    1秒内最多5次，超限直接拒绝
    ↓
【第2步】活动校验（本地缓存）
    读 Caffeine: activityId → SeckillActivitySnapshot（5秒TTL）
    命中 → 判断开始/结束时间
    未命中 → 读 Redis: seckill:activity:2001 → 回填 Caffeine
    Redis也没有 → 读 MySQL → 回填 Redis + Caffeine
    未开始/已结束 → 直接拒绝
    ↓
【第3步】本地售罄标记
    读 Caffeine: activityId → Boolean（10秒TTL）
    存在（售罄）→ 直接拒绝，不碰 Redis
    不存在 → 继续
    ↓
【第4步】生成订单号 + 消息ID
    不涉及缓存
    ↓
【第5步】Redis Lua 原子扣减（一个脚本操作3个key）
    读+写 Redis（Lua原子）：
      读 Redis String: seckill:stock:{act:2001}
        不存在 → 返回3（未预热，直接拒绝）
        <= 0 → 返回1（售罄）
      读 Redis Set: seckill:bought:{act:2001}
        SISMEMBER userId → 存在 → 返回2（重复购买）
      写 Redis String: seckill:stock:{act:2001} → DECR -1
      写 Redis Set: seckill:bought:{act:2001} → SADD userId
      写 Redis String: seckill:inflight:{act:2001} → INCR +1（在途计数）
      返回0（成功）
    如果返回售罄 → 写 Caffeine: activityId → TRUE（标记本地售罄）
    ↓
【第6步】写排队中结果
    写 Redis String: seckill:result:SOxxx = JSON({status: QUEUEING})
    TTL = 30分钟
    （必须先发排队中再发MQ，防止消费端写成功被排队中覆盖）
    ↓
【第7步】发 MQ 消息（异步创建订单）
    不涉及缓存，ReliableMessageSender 发送
    发送异常 → 立即回补 Redis 库存 + 写补偿守卫
    ↓
【第8步】返回「已受理」+ orderNo
    ↓
========== 以下是异步消费端 ==========
【第9步】MQ 消费端创建订单
    写 MySQL: order 表（唯一键 uk_order_no 防重复）
    写 MySQL: 扣 product 库存
    写 Redis String: seckill:result:SOxxx = JSON({status: SUCCESS})
    写 Redis String: seckill:inflight:{act:2001} → DECR -1（归还在途计数）
    手动 ACK
    失败 → 回补 Redis 库存 + 补偿守卫 + 重试/死信
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| Redis ZSET | 读+写 | `rate:limit:seckill:USER:{uid}:IP:{ip}` | 限流时间线 | 1s+缓冲 |
| Caffeine | 读 | `activityId` → snapshot | `SeckillActivitySnapshot` | 5秒 |
| Caffeine | 写（回填） | `activityId` → snapshot | `SeckillActivitySnapshot` | 5秒 |
| Caffeine | 读 | `activityId` → soldOut | `Boolean.TRUE` | 10秒 |
| Caffeine | 写（售罄时） | `activityId` → soldOut | `Boolean.TRUE` | 10秒 |
| Redis String | 读 | `seckill:activity:{N}` | 活动快照 JSON | 活动剩余+1h |
| Redis String | 读+写（Lua） | `seckill:stock:{act:N}` | 库存数字 | 活动剩余+1h |
| Redis Set | 读+写（Lua） | `seckill:bought:{act:N}` | 已购 userId 集合 | 活动剩余+1h |
| Redis String | 写（Lua） | `seckill:inflight:{act:N}` | 在途计数 +1 | 活动剩余+1h |
| Redis String | 写 | `seckill:result:{orderNo}` | `{status: QUEUEING}` | 30分钟 |
| Redis String | 写（消费端） | `seckill:result:{orderNo}` | `{status: SUCCESS/FAILED}` | 30分钟 |
| Redis String | 写（消费端） | `seckill:inflight:{act:N}` | 在途计数 -1 | 活动剩余+1h |
| Redis String | 写（补偿时） | `seckill:compensate:guard:{msgId}` | `"1"` | 24小时 |
| MySQL | 写（消费端） | `order` 表 | 订单记录 | - |
| MySQL | 写（消费端） | `product` 表 | 扣库存 | - |

---

### 4.3 秒杀结果查询

```
前端轮询 /api/seckill/result/{orderNo}
    ↓
【第1步】查 MySQL 订单
    读 MySQL: order 表 WHERE order_no = ?
    命中 → 返回订单详情（已落库）
    未命中 → 继续
    ↓
【第2步】查 Redis 结果缓存
    读 Redis String: seckill:result:{orderNo}
    不存在 → 返回「排队中」（订单还在 MQ 里没落库）
    存在 → 解析 JSON：
      status = QUEUEING → 自动续期30分钟 → 返回「排队中」
      status = SUCCESS → 返回成功
      status = FAILED → 返回失败原因
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 | TTL |
|------|------|---------|------|-----|
| MySQL | 读 | `order` 表 | 订单记录 | - |
| Redis String | 读 | `seckill:result:{orderNo}` | 结果 JSON | 30分钟 |
| Redis String | 写（续期） | `seckill:result:{orderNo}` | EXPIRE 30分钟 | 续期30分钟 |

**关键设计：**
- 优先查 MySQL：订单已落库就直接返回，不需要等缓存
- 排队中自动续期：用户还在轮询，结果不能被过期清理
- 结果缓存把大量轮询请求挡在 Redis，不会把数据库再打一遍

---

## 五、线程池与定时任务

### 5.1 秒杀活动预热定时任务

**文件：`SeckillPreheatJob.java`**

```
应用启动完成
    ↓
【第1步】ApplicationRunner.run() 触发（@Order 控制顺序）
    立即执行一次 preheatUpcoming(10分钟)
    （启动时就预热，不用等第一次定时调度）
    ↓
【第2步】@Scheduled 定时触发
    fixedDelayString = "${mall.job.preheat.interval:60000}"
    （上一次执行完后，间隔60秒再执行下一次）
    initialDelay = 60000（首次延迟60秒，避开启动高峰期）
    ↓
【第3步】每次执行：查未来10分钟内的活动 → 预热（见4.1）
    读 MySQL: seckill_activity 表
    写 Redis: stock/activity/bought
    清 Caffeine: 本地售罄标记 + 活动快照
    由 taskScheduler 线程池执行（4线程）
```

**涉及的缓存/存储：** 同 4.1 活动预热

**关键设计：**
- 双触发：ApplicationRunner（启动时立即执行一次）+ @Scheduled（每分钟循环）
- fixedDelay 不是 fixedRate：上一次执行完后才开始计时，防止任务堆积
- 由 `taskScheduler` 线程池执行（@Primary，4线程），不是 Tomcat 请求线程

---

### 5.2 缓存延迟双删调度

**文件：`ProductCacheInvalidator.java`，使用 `cacheDoubleDeleteScheduler`（单线程）**

```
商品新增/修改事件触发（AFTER_COMMIT）
    ↓
【第1步】第一次删缓存（同步，当前线程）
    删 Redis: cache:product:detail:{productId}
    删 Caffeine: product:detail:{productId}
    ↓
【第2步】调度延迟任务（异步）
    cacheDoubleDeleteScheduler.schedule(
        () -> 删 Redis: cache:product:detail:{productId},
        Instant.now().plusMillis(500)
    )
    （单线程调度器，500ms后执行第二次删除）
    ↓
【第3步】500ms后，第二次删缓存
    删 Redis: cache:product:detail:{productId}
    （单线程串行执行，不会并发）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key 格式 | 内容 |
|------|------|---------|------|
| Redis | 删（第一次） | `cache:product:detail:{productId}` | 删除 |
| Caffeine | 删（第一次） | `product:detail:{productId}` | 失效 |
| Redis | 删（第二次，500ms后） | `cache:product:detail:{productId}` | 删除 |

**关键设计：**
- 用 `scheduler.schedule()` 而不是 `@Scheduled`：延迟双删是事件驱动的一次性任务，不是固定周期任务
- 单线程调度器：延迟任务不需要高并发，单线程足够，还能避免并发删除的竞态
- 500ms延迟是经验值，覆盖"读请求查到旧值 → 写库提交 → 读请求写回旧值"的时间窗口

---

## 附录：缓存 key 命名规范总览

| 前缀 | 模块 | 示例 |
|------|------|------|
| `rate:limit:` | 限流 | `rate:limit:login:IP:127.0.0.1` |
| `auth:blacklist:` | 认证黑名单 | `auth:blacklist:{jti}` |
| `cache:product:` | 商品缓存 | `cache:product:detail:{id}` |
| `bloom:product` | 布隆过滤器 | `bloom:product` |
| `lock:cache:rebuild:` | 缓存重建锁 | `lock:cache:rebuild:{id}` |
| `seckill:stock:` | 秒杀库存 | `seckill:stock:{act:2001}` |
| `seckill:bought:` | 秒杀已购 | `seckill:bought:{act:2001}` |
| `seckill:inflight:` | 秒杀在途 | `seckill:inflight:{act:2001}` |
| `seckill:activity:` | 秒杀活动快照 | `seckill:activity:2001` |
| `seckill:result:` | 秒杀结果 | `seckill:result:SOxxx` |
| `seckill:compensate:guard:` | 补偿守卫 | `seckill:compensate:guard:{msgId}` |
| `cache:seckill:list` | 活动列表缓存 | `cache:seckill:list` |
| ~~`seckill:preheated`~~ | 已预热集合（代码定义但实际未使用） | `seckill:preheated` |
| ~~`lock:seckill:preheat:`~~ | 预热锁（代码定义但实际未使用） | `lock:seckill:preheat:2001` |

---

## 六、MQ 可靠性设计

### 6.1 整体架构：两条 MQ 链路

项目里有两条独立的 MQ 链路：

**秒杀下单链路：**
```
mall.seckill.exchange (direct)
       │ rk=seckill.order
       ▼
mall.seckill.order.queue ──消费失败(重试未耗尽)──► retry.queue
       │                                      (TTL=5s, DLX回主队列)
       │ 重试耗尽 / 业务不可重试
       ▼
mall.dlx.exchange ──► mall.seckill.order.dlq（死信，人工/自动补偿）
```

**订单超时关单链路：**
```
mall.order.delay.exchange ──► mall.order.delay.queue
                             (TTL=订单超时时间, DLX指向下一跳)
                                      │
                                      ▼
mall.order.close.exchange ──► mall.order.close.queue
```

---

### 6.2 配置类：交换机、队列、绑定

**文件：`RabbitMQConfig.java`**

**交换机（DirectExchange）：**
```java
@Bean
public DirectExchange seckillExchange() {
    // durable=true：broker 重启后交换机依然存在
    return new DirectExchange(properties.getMq().getSeckillExchange(), true, false);
}
```

**业务队列（配置死信交换机）：**
```java
@Bean
public Queue seckillOrderQueue() {
    Map<String, Object> args = new HashMap<>();
    // 消息变成死信时，自动转发到死信交换机
    args.put("x-dead-letter-exchange", properties.getMq().getDlxExchange());
    args.put("x-dead-letter-routing-key", properties.getMq().getSeckillDlqRoutingKey());
    return QueueBuilder.durable(properties.getMq().getSeckillQueue())
            .withArguments(args).build();
}
```

**绑定：**
```java
@Bean
public Binding seckillOrderBinding() {
    return BindingBuilder.bind(seckillOrderQueue())
            .to(seckillExchange())
            .with(properties.getMq().getSeckillRoutingKey());
}
```

**生产者可靠性回调：**
```java
@Bean
public RabbitTemplateCustomizer rabbitTemplateCustomizer() {
    return template -> {
        template.setMandatory(true);  // 无法路由的消息通过 Return 回调返回
        template.setConfirmCallback((correlationData, ack, cause) -> {
            sendTracker.onConfirm(correlationData, ack, cause);
        });
        template.setReturnsCallback(returned -> {
            sendTracker.onReturn(returned);
        });
    };
}
```

> 为什么 `mandatory=true` + ReturnCallback 缺一不可？Confirm 只能告诉生产者「broker 收到了」，不能告诉你「消息被路由到了队列」。如果 routingKey 写错、队列被误删，broker 会 ack 然后直接丢弃消息——生产者以为成功，消息却消失了。

---

### 6.3 延迟重试队列

```java
@Bean
public Queue seckillRetryQueue() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-message-ttl", (int) properties.getMq().getRetryDelay().toMillis());  // TTL=5秒
    args.put("x-dead-letter-exchange", properties.getMq().getSeckillExchange());     // 过期后回主交换机
    args.put("x-dead-letter-routing-key", properties.getMq().getSeckillRoutingKey());
    return QueueBuilder.durable(properties.getMq().getSeckillRetryQueue())
            .withArguments(args).build();
}
```

**延迟重试原理：**
- 这个队列**没有消费者**
- 消息进来后「躺着」，直到 TTL（5秒）到期
- 到期后通过 DLX 重新投回主业务队列
- 实现「延迟5秒再重试」

**为什么不用 `basicNack(requeue=true)`？**
- `requeue=true` 会把消息立刻重新投回队首，零间隔自旋
- 如果失败原因是数据库连接池满了，立刻重投只会让下游更没机会恢复
- 延迟队列给下游留出5秒恢复时间

---

### 6.4 死信队列（DLQ）

```java
@Bean
public DirectExchange dlxExchange() {
    return new DirectExchange(properties.getMq().getDlxExchange(), true, false);
}

@Bean
public Queue seckillDlq() {
    return QueueBuilder.durable(properties.getMq().getSeckillDlq()).build();
}

@Bean
public Binding seckillDlqBinding() {
    return BindingBuilder.bind(seckillDlq())
            .to(dlxExchange())
            .with(properties.getMq().getSeckillDlqRoutingKey());
}
```

重试耗尽、业务不可重试的消息最终到这里，保留现场供人工/对账处理。

---

### 6.5 生产者可靠发送

**文件：`ReliableMessageSender.java`**

```java
public void sendSeckillOrder(SeckillOrderMessage msg) {
    CorrelationData correlationData = sendTracker.register(msg);  // 登记待确认
    try {
        rabbitTemplate.convertAndSend(
            mq.getSeckillExchange(),
            mq.getSeckillRoutingKey(),
            JsonUtils.toJson(msg),  // 自己序列化，不用框架的 Jackson2JsonMessageConverter
            message -> {
                applyCommonProperties(message, msg.getMessageId());
                message.getMessageProperties().setHeader("bizType", "SECKILL_ORDER");
                message.getMessageProperties().setHeader("retryCount", 0);
                return message;
            },
            correlationData);
    } catch (Exception e) {
        // 发送异常：消息确定没发出去，立刻补偿
        sendTracker.compensateByMessageId(msg.getMessageId(), "生产端发送异常");
        throw new IllegalStateException("消息发送失败", e);
    }
}
```

**消息实体（SeckillOrderMessage）：**
```java
public class SeckillOrderMessage implements Serializable {
    private String messageId;      // 消息唯一ID，幂等去重依据
    private String orderNo;        // 订单号（生产端预生成）
    private Long userId;
    private Long activityId;
    private Long productId;
    private String productTitle;   // 商品标题快照
    private BigDecimal seckillPrice; // 秒杀价快照
    private Integer quantity;
    private Integer totalStock;    // 活动总库存（补偿脚本用）
    private LocalDateTime createdAt;
}
```

**为什么消息体带业务快照？**
1. 消费端不需要再回查活动/商品表，少一次数据库依赖
2. 避免「消费时活动已被修改」导致的金额不一致
3. 消息本身成为可审计的凭证

---

### 6.6 发送追踪：Confirm/Return/超时

**文件：`MessageSendTracker.java`**

解决三个问题：
1. Confirm 回调只能拿到 CorrelationData，拿不到业务上下文 → 用 Caffeine 存 messageId → 业务消息的映射
2. Return 回调与 Confirm 回调是两条独立路径（Return 先于 Ack）→ 统一收敛到补偿服务
3. broker 静默失联时 Confirm 永远不来 → 定时扫描超时消息补偿

```java
// Caffeine 存待确认消息，30分钟TTL，最多20万条
private final Cache<String, PendingSend> pending;

// 登记
public CorrelationData register(SeckillOrderMessage msg) {
    pending.put(msg.getMessageId(), PendingSend.of(msg));
    return new CorrelationData(msg.getMessageId());
}

// Confirm 回调
public void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
    if (ack) {
        pending.invalidate(messageId);  // broker 收下了，清掉
        return;
    }
    PendingSend send = pending.asMap().remove(messageId);
    compensate(send, "broker 拒绝接收(nack)");  // nack，补偿
}

// Return 回调（路由失败）
public void onReturn(ReturnedMessage returned) {
    PendingSend send = pending.getIfPresent(messageId);
    compensate(send, "路由失败(return)");
}

// 定时扫描超时消息
public int compensateTimedOut() {
    List<PendingSend> timedOut = findTimedOut();
    for (PendingSend send : timedOut) {
        compensate(pending.asMap().remove(send.getMessageId()), "Confirm 超时");
    }
    return timedOut.size();
}
```

---

### 6.7 消费者：手动 ACK 与五种结果

**文件：`SeckillOrderConsumer.java`**

**为什么手动 ACK？** 自动 ACK 表达不了这么细的语义，需要区分五种结果：

```java
@RabbitListener(queues = "#{mallProperties.mq.seckillQueue}")
public void onSeckillOrder(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    try {
        payload = parse(message);
        orderCreationService.createFromSeckill(payload);  // 创建订单
        channel.basicAck(deliveryTag, false);              // ① 成功
    } catch (DuplicateMessageException e) {
        channel.basicAck(deliveryTag, false);              // ② 幂等命中
    } catch (DuplicateOrderException e) {
        compensateQuietly(payload, "订单已存在");          // ③ 唯一键冲突，补偿后ACK
        channel.basicAck(deliveryTag, false);
    } catch (NonRetryableMessageException e) {
        compensateQuietly(payload, e.getMessage());        // ④ 不可重试，补偿后进死信
        channel.basicNack(deliveryTag, false, false);
    } catch (Exception e) {
        handleRetryable(message, deliveryTag, channel, e); // ⑤ 可重试，投延迟队列
    }
}
```

**可重试失败的处理：**
```java
private void handleRetryable(...) {
    if (retryCount < maxRetry) {
        messageSender.republish(message, exchange, retryRoutingKey, retryCount + 1);
        channel.basicAck(deliveryTag, false);  // 投到延迟队列后，ACK原消息
    } else {
        channel.basicNack(deliveryTag, false, false);  // 重试耗尽，进死信
    }
}
```

> ACK 必须放在 try 的最后：所有可能失败的动作都必须发生在 ACK 之前。如果先 ACK 再写结果缓存，缓存写失败时消息已经确认，用户永远查不到结果。

---

### 6.8 超时判断机制

**什么是 Confirm 超时？** 消息发出去了，但 broker 的确认一直没回来（broker挂了、网络分区）。RabbitMQ 本身没有内置超时机制，项目自己实现。

**超时判断代码：**
```java
public List<PendingSend> findTimedOut() {
    long timeout = properties.getMq().getConfirmTimeout().toMillis();  // 默认5秒
    List<PendingSend> timedOut = new ArrayList<>();
    for (PendingSend send : pending.asMap().values()) {
        if (send.isOlderThan(timeout)) {  // 当前时间 - 登记时间 > 5秒
            timedOut.add(send);
        }
    }
    return timedOut;
}
```

**谁触发？** 项目自己的定时任务 `PendingMessageRetryJob` 周期性扫描：
```java
@Scheduled(fixedDelayString = "...")
public void retryPending() {
    messageSendTracker.compensateTimedOut();  // 扫描超时消息并补偿
}
```

**完整超时流程：**
```
T0: 发送消息 → register() 存入 Caffeine，记录时间戳
    ↓
T0+1s: 收到 Confirm(ack=true) → 从 Caffeine 删掉，正常
    ↓
T0+5s: 还没收到 Confirm → 定时任务扫描到 → 判定超时 → 触发补偿
```

---

### 6.9 死信队列触发条件

消息进入死信队列有三种情况，RabbitMQ 自动判定：

| 情况 | 说明 | 项目里是否用到 |
|------|------|--------------|
| ① Nack/Reject 且 requeue=false | 消费者明确拒绝且不重新入队 | ✅ 用到 |
| ② 消息 TTL 过期 | 消息在队列里超过 TTL | ✅ 延迟重试队列用这个原理 |
| ③ 队列满了 | 超过最大长度被挤出 | ❌ 没配置 |

**项目里触发情况①的代码：**
```java
// 业务不可重试失败
channel.basicNack(deliveryTag, false, false);  // 第三个参数 false = requeue=false

// 重试耗尽
channel.basicNack(deliveryTag, false, false);
```

**死信流转路径：**
```
业务队列 mall.seckill.order.queue
    ↓ 消息被 Nack(requeue=false)
    ↓ 队列配置了 x-dead-letter-exchange = mall.dlx.exchange
死信交换机 mall.dlx.exchange
    ↓ routingKey = seckill.dlq
死信队列 mall.seckill.order.dlq
    ↓
死信消费者处理（记录日志、告警、对账）
```

---

### 6.10 补偿机制：三层防重

**为什么需要补偿？** 秒杀是「先扣 Redis 库存，再异步创建订单」，如果 MQ 发不出去、消息丢了、消费端失败，就会出现「Redis 扣了库存但订单没创建」，库存凭空消失。

**补偿守卫（SETNX）：** 同一条消息可能从5个入口触发补偿（发送异常、Confirm nack、Return回调、超时扫描、消费端失败），不设防会重复回补导致超卖。

```java
public boolean tryAcquireCompensateGuard(String messageId) {
    Boolean ok = redis.opsForValue().setIfAbsent(
            RedisKeys.seckillCompensateGuard(messageId), "1", Duration.ofHours(24));
    return Boolean.TRUE.equals(ok);
}
```

**三层防重：**

| 层级 | 做法 | 防止什么 |
|------|------|---------|
| 第一层 | 先查订单是否已存在 | 订单已经落库了还回补库存 = 超卖 |
| 第二层 | SETNX 一次性守卫 | 同一条消息被5个入口重复补偿 |
| 第三层 | Lua 脚本总库存封顶 | 前两层都被绕过时，回补后库存不超过总库存 |

**补偿入口全景：**
```
发送端4种失败：
  ① 发送异常 → 立刻补偿
  ② Confirm nack → 回调补偿
  ③ Return 路由失败 → 回调补偿
  ④ Confirm 超时 → 定时扫描补偿

消费端5种结果：
  ① 成功 → 不补偿
  ② 幂等命中 → 不补偿
  ③ 唯一键冲突 → 补偿后ACK
  ④ 不可重试失败 → 补偿后进死信
  ⑤ 瞬时故障 → 延迟重试 → 耗尽后进死信
```

> 核心原则：**宁可少卖，绝不超卖**。

---

### 6.11 MQ 完整调用链

```
秒杀下单接口
    ↓
ReliableMessageSender.sendSeckillOrder()
    ├─ MessageSendTracker.register() → 存 Caffeine（记录时间戳）
    ├─ rabbitTemplate.convertAndSend() → 发消息
    └─ 异常 → compensateByMessageId() → 补偿
    ↓
RabbitMQ Broker
    ├─ Confirm 回调 → MessageSendTracker.onConfirm()
    │   ├─ ack=true → 从 Caffeine 删除
    │   └─ ack=false → 补偿
    └─ Return 回调 → MessageSendTracker.onReturn() → 补偿
    ↓
SeckillOrderConsumer.onSeckillOrder()
    ├─ 成功 → ACK
    ├─ 幂等 → ACK
    ├─ 唯一键冲突 → 补偿 → ACK
    ├─ 不可重试 → 补偿 → NACK(requeue=false) → 死信
    └─ 可重试 → republish到延迟队列 → ACK原消息
        └─ 5秒后TTL过期 → DLX回到主队列 → 重新消费
            └─ 重试耗尽 → NACK → 死信
    ↓
定时任务：PendingMessageRetryJob
    └─ MessageSendTracker.compensateTimedOut() → 补偿超时未确认的消息
    ↓
死信消费者：SeckillDlqConsumer
    └─ 记录日志、告警、人工/自动补偿
    ↓
对账任务：SeckillReconcileJob
    └─ 比对 Redis库存 / DB库存 / 在途计数，修复最终不一致
```

---

### 6.12 秒杀下单 MQ 发送与消费时序（带缓存标注）

```
用户点击抢购
    ↓
【第1步】Redis Lua 原子扣减库存（分界点：库存扣了）
    读 Redis: seckill:stock:{activityId}（availableStock / totalStock）
    读 Redis: seckill:bought:{activityId}（Set，判断是否已购）
    写 Redis: seckill:stock:{activityId}（availableStock -1）
    写 Redis: seckill:bought:{activityId}（加入 userId）
    写 Redis: seckill:inflight:{activityId}（在途计数 +1）
    返回：0成功 / 1售罄 / 2重复 / 3未预热
    ↓
【第2步】写排队中结果
    写 Redis: seckill:result:{orderNo} = "QUEUEING"（TTL=1小时）
    ↓
【第3步】登记待确认消息到 Caffeine
    写 Caffeine: key=messageId, value=PendingSend（业务消息+创建时间戳），TTL=30分钟
    ↓
【第4步】发消息到 RabbitMQ
    发消息到 mall.seckill.order.queue（消息体带业务快照：orderNo/userId/activityId/seckillPrice...）
    消息头：messageId, bizType=SECKILL_ORDER, retryCount=0
    ↓
【第5步】RabbitMQ Broker 回调
    ├─ Confirm(ack=true) → 删 Caffeine: key=messageId → 正常
    ├─ Confirm(ack=false) → 取 Caffeine → 触发补偿（见6.10）
    ├─ Return(路由失败) → 取 Caffeine → 触发补偿
    └─ 回调都不来 → 定时任务每2秒扫 Caffeine，超5秒 → 触发补偿
    ↓
【第6步】SeckillOrderConsumer 消费
    读消息体 → 解析 SeckillOrderMessage
    ↓
【第7步】创建订单（同事务）
    读 MySQL: mq_message 表 WHERE message_id=?（幂等检查，仅用于给出更清晰异常）
    写 MySQL: order 表（INSERT，uk_order_no 唯一键防重复）
    写 MySQL: mq_message 表（INSERT 消费记录，uk_message_id 唯一键防重复消费）
    写 MySQL: stock_flow 表（库存扣减流水）
    更新 MySQL: seckill_activity 表（available_stock -1，条件更新防超卖）
    事务提交
    ↓
【第8步】事务提交后
    写 Redis: seckill:result:{orderNo} = "SUCCESS"（更新结果）
    写 Redis: seckill:inflight:{activityId}（在途计数 -1）
    发消息到 mall.order.delay.queue（超时关单消息，TTL=30分钟）
    ↓
【第9步】ACK
    channel.basicAck(deliveryTag, false) → 消息从队列删除
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 | TTL |
|------|------|--------|------|-----|
| Redis | 读写 | `seckill:stock:{activityId}` | Hash: availableStock / totalStock | 活动剩余+1h |
| Redis | 读写 | `seckill:bought:{activityId}` | Set: userId | 活动剩余+1h |
| Redis | 读写 | `seckill:inflight:{activityId}` | String: 在途计数 | 活动剩余+1h |
| Redis | 写 | `seckill:result:{orderNo}` | String: QUEUEING/SUCCESS/FAILED | 1小时 |
| Redis | 写(补偿时) | `seckill:compensate:guard:{messageId}` | String: "1" | 24小时 |
| Caffeine | 读写删 | `{messageId}` | PendingSend（业务消息+时间戳） | 30分钟 |
| MySQL | 写 | `order` 表 | 订单记录 | - |
| MySQL | 写 | `mq_message` 表 | 消息消费记录（uk_message_id） | - |
| MySQL | 写 | `stock_flow` 表 | 库存流水 | - |
| MySQL | 更新 | `seckill_activity` 表 | available_stock -1 | - |

---

### 6.13 订单超时关单链路时序（带缓存标注）

```
秒杀消费成功，创建订单（status=CREATED 待支付）
    ↓
【第1步】发超时关单消息
    发消息到 mall.order.delay.queue
    消息体：OrderTimeoutMessage（orderNo, orderId, activityId, userId, quantity, totalStock, expireTime）
    这个队列没有消费者
    ↓
【第2步】消息在延迟队列「躺着」
    队列配置 x-message-ttl = 订单超时时间（默认30分钟）
    队列配置 x-dead-letter-exchange = mall.order.close.exchange
    ↓
【第3步】30分钟后消息 TTL 过期
    RabbitMQ 自动把消息通过 DLX 转发到 mall.order.close.exchange
    → 路由到 mall.order.close.queue
    ↓
【第4步】OrderTimeoutConsumer 消费
    读消息体 → 解析 OrderTimeoutMessage
    ↓
【第5步】条件更新关单（同一个事务）
    执行 UPDATE t_order SET status=2(CANCELED), cancel_time=? 
         WHERE order_no=? AND status=0(CREATED)
    影响行数=1？
    ├─ 是 → 订单还是待支付，用户没交钱 → 关单成功
    │       更新 MySQL: seckill_activity 表（available_stock + quantity，条件回补不超总库存）
    │       写 MySQL: stock_flow 表（回补流水，uk_order_change 防重复）
    │       事务提交
    │       ↓
    │       【第6步】事务提交后回补 Redis
    │       写 Redis: seckill:stock:{activityId}（availableStock + quantity，Lua总库存封顶）
    │       releaseQualification=false（一人一单资格不回收）
    │       decInflight=false（订单早已落库，在途计数下单成功时就归还了）
    │       ↓
    │       ACK
    │
    └─ 否 → 订单已经支付(status=1)或已取消(status=2) → 什么都不做 → ACK
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 | TTL |
|------|------|--------|------|-----|
| RabbitMQ | 发消息 | `mall.order.delay.queue` | OrderTimeoutMessage | 队列级TTL=30分钟 |
| RabbitMQ | DLX转发 | `mall.order.close.queue` | 同上 | - |
| MySQL | 条件更新 | `order` 表 | status 0→2（WHERE status=0） | - |
| MySQL | 条件更新 | `seckill_activity` 表 | available_stock + quantity（不超总库存） | - |
| MySQL | 写 | `stock_flow` 表 | 回补流水（uk_order_change） | - |
| Redis | 写(关单成功时) | `seckill:stock:{activityId}` | availableStock + quantity（Lua封顶） | 活动剩余+1h |

**关键设计：**
- **为什么回补 Redis 放在事务提交后？** Redis 不参与数据库事务，如果放在事务内，后续语句失败导致回滚，Redis 已经加回去了 → 库存凭空多出来 → 超卖。先提交数据库再回补 Redis，最坏情况是 Redis 没来得及加回去，对账任务会修回来。两种失败模式后果不对称：超卖(资损) vs 少卖(可自愈)，永远选后者。
- **为什么 releaseQualification=false？** 一人一单是硬规则，用户已经抢到了资格（只是没付钱），资格不回收。如果回收了，用户重新抢会在 Redis 通过，但在 DB 撞唯一键报错，体验更差。
- **为什么 decInflight=false？** 在途计数记录的是「Redis已扣但订单未落库」的数量，订单创建成功时就已经 -1 了，关单时不需要再减。

---

### 6.14 支付流程（模拟支付+条件更新）

> 本项目是教学项目，支付是**模拟**的，没有对接微信/支付宝，没有真实资金流转、没有回调、没有签名验证。

```
用户点击「支付」按钮
    ↓
【第1步】前端调用支付接口
    POST /order/{orderNo}/pay
    ↓
【第2步】查订单
    读 MySQL: order 表 WHERE order_no=?
    订单不存在 → 抛 ORDER_NOT_FOUND
    ↓
【第3步】条件更新支付状态
    执行 UPDATE t_order SET status=1(PAID), pay_time=? 
         WHERE order_no=? AND status=0(CREATED)
    影响行数=1？
    ├─ 是 → 支付成功，返回 ok
    └─ 否 → 订单已经被取消(超时关单)或已经支付过了
            → 抛 ORDER_STATUS_CONFLICT，提示"订单当前状态为xxx，无法支付"
    ↓
【第4步】支付成功后
    不扣库存（下单时已在 Redis 扣了）
    不清缓存
    不发消息（超时关单消息30分钟后到了会发现 status=1，自动跳过）
    直接返回 ok
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| MySQL | 读 | `order` 表 | 查订单是否存在 |
| MySQL | 条件更新 | `order` 表 | status 0→1（WHERE status=0） |

**关键设计：**
- **为什么用条件更新而不是先查再改？** 支付和超时关单是并发竞争的两个操作。先查再改有竞态窗口：查到 status=0，同时超时关单把 status 改成2，然后支付还把 status 改成1 → 订单既被关单又被支付。条件更新是数据库层面原子操作，谁先成功另一个影响行数=0就知道状态已被改了。
- **真实项目 vs 模拟项目：** 真实项目支付流程是「调用第三方支付统一下单 → 用户扫码 → 第三方异步回调 → 验证签名 → 条件更新状态」，本项目简化为一个接口直接改状态。

---

### 6.15 MQ 模块涉及的所有缓存汇总

#### Caffeine（本地缓存，进程内，不共享）

| key | value | TTL | 谁写 | 谁读/删 | 作用 |
|-----|-------|-----|------|---------|------|
| `{messageId}` | PendingSend（业务消息+创建时间戳） | 30分钟 | MessageSendTracker.register() | onConfirm删 / onReturn读 / 超时扫描读删 | 发送后等待Confirm期间存业务上下文，回调时找回是哪一单 |

#### Redis（分布式缓存，多实例共享）

| key | 类型 | value | TTL | 谁写 | 谁读 | 作用 |
|-----|------|-------|-----|------|------|------|
| `seckill:stock:{activityId}` | Hash | availableStock / totalStock | 活动剩余+1h | 预热任务 / Lua扣减 / Lua回补 | Lua扣减 / Lua回补 / 对账 | 秒杀库存 |
| `seckill:bought:{activityId}` | Set | userId | 活动剩余+1h | Lua扣减(加入) | Lua扣减(判断) / 补偿回补(移除) | 一人一单，防重复购买 |
| `seckill:inflight:{activityId}` | String | 在途计数 | 活动剩余+1h | Lua扣减+1 / 订单落库-1 / 补偿-1 | 对账 | Redis已扣但订单未落库的数量 |
| `seckill:result:{orderNo}` | String | QUEUEING / SUCCESS / FAILED | 1小时 | 下单时写QUEUEING / 成功时更新SUCCESS / 失败时更新FAILED | 结果轮询接口 | 用户查询抢购结果 |
| `seckill:compensate:guard:{messageId}` | String | "1" | 24小时 | 补偿时SETNX | 补偿时判断 | 同一条消息只补偿一次（SETNX守卫） |

#### MySQL

| 表 | 作用 | 关键索引 |
|----|------|---------|
| `order` | 订单表 | uk_order_no（订单号唯一） |
| `mq_message` | 消息台账，消费幂等 | uk_message_id（消息ID唯一） |
| `stock_flow` | 库存流水 | uk_order_change（同一订单的流水只落一次） |
| `seckill_activity` | 活动表（含库存） | - |

#### RabbitMQ 队列

| 队列 | 类型 | 消费者 | TTL | DLX指向 | 作用 |
|------|------|--------|-----|---------|------|
| `mall.seckill.order.queue` | 业务主队列 | SeckillOrderConsumer | - | mall.dlx.exchange | 秒杀下单消息消费 |
| `mall.seckill.retry.queue` | 延迟重试队列 | 无 | 5秒 | mall.seckill.exchange | 消费失败后延迟5秒重试 |
| `mall.seckill.order.dlq` | 死信队列 | SeckillDlqConsumer | - | - | 重试耗尽/不可重试的消息 |
| `mall.order.delay.queue` | 订单延迟队列 | 无 | 30分钟 | mall.order.close.exchange | 下单后延迟30分钟检查是否未支付 |
| `mall.order.close.queue` | 关单队列 | OrderTimeoutConsumer | - | - | 超时未支付订单关单+回补库存 |

---

## 七、用户上下文模块（ThreadLocal）

### 7.1 已登录请求完整生命周期

```
用户发起请求（带 Authorization: Bearer {token}）
    ↓
【第1步】LoginInterceptor.preHandle（请求进入 Controller 之前）
    读请求头: Authorization 或 token
    解析 Token（JWT 验签 + 判过期）
    读 Redis: auth:blacklist:{jti}（判断是否已登出/拉黑）
    ├─ Token 无效/过期/在黑名单 → 抛 401，请求终止
    └─ 通过 → UserContext.set(LoginUser)
         写 ThreadLocal: HOLDER = LoginUser(userId, username, jti)
    ↓
【第2步】Controller / Service / Mapper 执行业务逻辑
    读 ThreadLocal: UserContext.getUserId() / getUsername() / requireUserId()
    不需要把 userId 当参数层层传递
    ↓
【第3步】业务逻辑执行完毕，返回响应
    ↓
【第4步】LoginInterceptor.afterCompletion（请求完全结束后）
    清理 ThreadLocal: UserContext.clear() → HOLDER.remove()
    【必须执行】Tomcat 线程池复用，不清理会导致下一个请求读到上一个用户身份
```

**涉及的缓存/存储：**

| 存储 | 操作 | key | 内容 | TTL |
|------|------|-----|------|-----|
| ThreadLocal | 写 | `HOLDER`（线程私有） | LoginUser(userId, username, jti) | 请求生命周期 |
| ThreadLocal | 读 | `HOLDER` | 业务代码通过 UserContext.get() 读取 | - |
| ThreadLocal | 删 | `HOLDER` | afterCompletion 中 remove() | - |
| Redis | 读 | `auth:blacklist:{jti}` | 存在=已登出 | Token 剩余有效期 |

**关键设计：**
- **为什么用 ThreadLocal 而不是参数传递？** Controller→Service→Mapper 隔好几层，透传 userId 会污染所有方法签名。ThreadLocal 让任何层都能直接拿到当前用户。
- **为什么必须在 afterCompletion 清理？** Tomcat 工作线程是池化复用的，如果只 set 不 remove，下一个复用该线程的请求会读到上一个用户的身份——这是真实发生过多次的越权漏洞。
- **requireUserId() vs getUserId()？** `getUserId()` 取不到返回 null（可能为 null 的场景），`requireUserId()` 取不到抛 IllegalStateException（必须登录的分支，避免下游 NPE）。
- **@Async 不自动传递**：ThreadLocal 是线程私有的，异步线程拿不到。本项目异步重建缓存的任务刻意不读用户身份，就是为了绕开这个坑。

---

## 八、订单模块

### 8.1 秒杀订单创建（消费端落库）

> 这是 MQ 消费端的核心逻辑，整个方法在一个事务内完成5件事。

```
SeckillOrderConsumer 收到消息
    ↓
【第1步】幂等闸门
    读 MySQL: mq_message 表 WHERE message_id=?（仅用于给出明确异常语义）
    已存在 → 抛 DuplicateMessageException → 消费者 ACK
    写 MySQL: mq_message 表（INSERT 消费记录）
    └─ 撞 uk_message_id 唯一键 → 翻译成 DuplicateMessageException
    ↓
【第2步】数据库库存扣减（真正的超卖防线）
    更新 MySQL: seckill_activity 表
    UPDATE SET available_stock = available_stock - ? 
      WHERE id=? AND available_stock >= ?
    影响行数=0 → 库存不足（Redis与DB漂移）→ 抛 NonRetryableMessageException
    ↓
【第3步】创建订单
    写 MySQL: order 表（INSERT）
    └─ 撞 uk_order_no / uk_user_activity → 抛 DuplicateOrderException
    订单状态=CREATED(待支付)，expire_time=当前时间+30分钟
    ↓
【第4步】创建订单项
    写 MySQL: order_item 表（INSERT，商品快照：标题、秒杀价、数量）
    ↓
【第5步】库存流水
    写 MySQL: stock_flow 表（INSERT，uk_order_change 保证同一订单只扣一次）
    ↓
【第6步】发布 OrderCreatedEvent（事务提交后执行）
    由 OrderCreatedListener 在 AFTER_COMMIT 阶段处理：
    ├─ 更新 Redis: seckill:result:{orderNo} = SUCCESS
    ├─ 更新 Redis: seckill:inflight:{activityId} -1
    └─ 发消息到 mall.order.delay.queue（超时关单消息，TTL=30分钟）
    ↓
【第7步】事务提交 → 消费者 ACK
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| MySQL | 读写 | `mq_message` 表 | 消费记录（uk_message_id 幂等） |
| MySQL | 条件更新 | `seckill_activity` 表 | available_stock - quantity（WHERE available_stock >= quantity） |
| MySQL | 写 | `order` 表 | 订单记录（uk_order_no, uk_user_activity） |
| MySQL | 写 | `order_item` 表 | 订单项（商品快照） |
| MySQL | 写 | `stock_flow` 表 | 库存扣减流水（uk_order_change） |
| Redis | 写(事务提交后) | `seckill:result:{orderNo}` | SUCCESS |
| Redis | 写(事务提交后) | `seckill:inflight:{activityId}` | 在途计数 -1 |
| RabbitMQ | 发(事务提交后) | `mall.order.delay.queue` | 超时关单消息 |

**关键设计：**
- **为什么5件事必须在同一个事务？** 消费记录和订单创建不能分开，如果先写消费记录再单独开事务写订单，订单回滚后消费记录还在，重试时被误判为"已消费"直接 ACK，消息被永久丢弃。
- **为什么 DB 还要扣库存？Redis 不是扣过了吗？** Redis 是"准入闸门"，DB 是"真正的超卖防线"。Redis 可能漂移、可能丢数据，DB 的条件更新（available_stock >= quantity）由 InnoDB 行锁保证原子性，是最后一道防线。
- **为什么用雪花ID不用自增主键？** 高并发插入下自增主键有 AUTO-INC 锁竞争，且分库分表时自增无法全局唯一。
- **为什么事务提交后才更新 Redis 和发 MQ？** Redis 和 MQ 不参与 DB 事务，如果放在事务内，事务回滚时 Redis/MQ 的修改无法撤销，导致不一致。

---

### 8.2 订单详情查询

```
用户请求 GET /api/order/{orderNo}
    ↓
【第1步】从 ThreadLocal 取 userId
    读 ThreadLocal: UserContext.requireUserId()
    ↓
【第2步】查订单
    读 MySQL: order 表 WHERE order_no=?
    订单不存在 → 抛 ORDER_NOT_FOUND
    ↓
【第3步】越权校验
    订单.userId != 当前userId → 抛 FORBIDDEN（无权查看该订单）
    ↓
【第4步】返回 OrderVO
    不涉及缓存（订单数据读量不大，直接查 DB）
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| ThreadLocal | 读 | `HOLDER` | 当前用户 userId |
| MySQL | 读 | `order` 表 | 订单详情 |

**关键设计：**
- **为什么强制用上下文中的 userId 做归属校验？** 不能从前端参数取 userId，否则攻击者猜到别人的订单号就能拿到数据。用上下文的 userId 做校验，即便有人猜到订单号也拿不到任何数据。

---

### 8.3 订单列表查询

```
用户请求 GET /api/order/list?pageNum=1&pageSize=10
    ↓
【第1步】从 ThreadLocal 取 userId
    读 ThreadLocal: UserContext.requireUserId()
    ↓
【第2步】分页查询
    读 MySQL: order 表 WHERE user_id=? ORDER BY id DESC
    MyBatis-Plus 分页拦截器自动加 LIMIT
    maxLimit=500（防止 pageSize 过大打挂 DB）
    ↓
【第3步】返回 PageResult<OrderVO>
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| ThreadLocal | 读 | `HOLDER` | 当前用户 userId |
| MySQL | 分页读 | `order` 表 | 当前用户的订单列表 |

**关键设计：**
- **分页拦截器 maxLimit=500**：不设的话 pageSize=1000000 会让一个请求把 DB 打挂，这是最容易被忽略也最容易被利用的接口层风险。

---

### 8.4 用户主动取消订单

```
用户请求 POST /api/order/{orderNo}/cancel
    ↓
【第1步】越权校验
    读 MySQL: order 表 WHERE order_no=?
    订单.userId != 当前userId → 抛 FORBIDDEN
    ↓
【第2步】条件更新关单（同事务）
    更新 MySQL: order 表
    UPDATE SET status=2(CANCELED), cancel_time=? 
      WHERE order_no=? AND status=0(CREATED)
    影响行数=0 → 订单已支付/已取消 → 返回 canceled=false
    影响行数=1 → 关单成功
    ↓
【第3步】回补 DB 库存（同事务）
    更新 MySQL: seckill_activity 表
    UPDATE SET available_stock = available_stock + ? 
      WHERE id=? AND available_stock + ? <= total_stock
    ↓
【第4步】写库存流水（同事务）
    写 MySQL: stock_flow 表（uk_order_change 防重复）
    ↓
【第5步】事务提交后回补 Redis
    写 Redis: seckill:stock:{activityId}（availableStock + quantity，Lua封顶）
    releaseQualification=false（资格不回收）
    decInflight=false（在途已归还）
    ↓
返回 canceled=true
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| MySQL | 条件更新 | `order` 表 | status 0→2（WHERE status=0） |
| MySQL | 条件更新 | `seckill_activity` 表 | available_stock + quantity（不超总库存） |
| MySQL | 写 | `stock_flow` 表 | 回补流水 |
| Redis | 写(事务提交后) | `seckill:stock:{activityId}` | availableStock + quantity（Lua封顶） |

**关键设计：**
- 和超时关单走同一套 `cancelIfUnpaid` 逻辑，只是 remark 不同（"用户主动取消" vs "超时未支付自动关单"）。
- 条件更新保证并发安全：用户取消和支付同时发生时，谁先成功另一个影响行数=0。

---

## 九、对账模块

### 9.1 对账任务执行时序

```
每5分钟（cron: 0 */5 * * * ?）定时任务触发
    ↓
【第1步】查所有进行中的活动
    读 MySQL: seckill_activity 表 WHERE end_time > now()
    已结束的活动不对账
    ↓
【第2步】逐个活动对账
    读 Redis: seckill:stock:{activityId}（availableStock）
    读 Redis: seckill:inflight:{activityId}（在途计数）
    读 MySQL: seckill_activity 表（available_stock）
    ↓
【第3步】计算漂移
    expected = dbAvailableStock - inflight
    drift = redisStock - expected
    drift == 0 → 一致
    drift != 0 → 不一致（漂移）
    ↓
【第4步】不一致时
    ├─ autoRepair 开启？→ 修复（Redis对齐到DB，在途清零，重新预热）
    └─ autoRepair 关闭？→ 打 error 日志告警
    ↓
【第5步】在途泄漏检测
    inflight > 0？→ 连续采样次数+1
    ├─ 连续3次（15分钟）都 > 0 → 判定泄漏 → 修复
    └─ 不到3次 → 记录次数，下次继续观察
```

**涉及的缓存/存储：**

| 存储 | 操作 | key/表 | 内容 |
|------|------|--------|------|
| MySQL | 读 | `seckill_activity` 表 | 进行中的活动列表 + available_stock |
| Redis | 读 | `seckill:stock:{activityId}` | availableStock |
| Redis | 读 | `seckill:inflight:{activityId}` | 在途计数 |
| Redis | 写(修复时) | `seckill:stock:{activityId}` | 重置为 DB 的值 |
| Redis | 写(修复时) | `seckill:inflight:{activityId}` | 清零 |
| 内存 | 读写 | `staleInflightCounter`（ConcurrentHashMap） | activityId → 连续在途>0的次数 |

**关键设计：**
- **为什么只对账进行中的活动？** 已结束的活动不会再有新的抢购请求，对账没有意义。
- **为什么默认每5分钟？** 可配置（`mall.reconcile.cron`），5分钟是平衡"及时发现问题"和"减少对账开销"的经验值。
- **为什么默认只告警不自动修？** 自动修复的前提是对账逻辑本身正确，如果对账逻辑有 bug，自动修复会把一个数据错误放大成持续的数据破坏。建议先跑一段时间只告警，确认 drift 恒为0后再开启 auto-repair。

---

### 9.2 漂移检测与在途泄漏检测

**不变式（Invariant）：**
```
稳态（无在途请求）：  Redis库存 == DB available_stock
有在途请求时：        Redis库存 == DB available_stock - 在途数
```

**推导：** 每次成功抢购，Redis 先减1（进入在途），订单落库后 DB 也减1；每次补偿/关单，两边同时加回1。所以差值只可能来自"在途"。

**漂移检测：**
```
drift = redisStock - (dbAvailableStock - inflight)
drift != 0 → 漂移（Redis和DB不一致）
```

**在途泄漏检测（最隐蔽的故障）：**
```
问题：进程在"Redis已扣减、消息还没确认"时崩溃
  → Redis库存永久少1，在途计数永久多1
  → 两者相减正好抵消，drift=0，单纯比对库存发现不了

破局点：在途是瞬态量
  正常业务下，从扣减到落库只有几十毫秒
  每5分钟采样一次，几乎不可能采到"在途>0"
  连续3次采样（15分钟）都看到在途>0 → 判定泄漏

修复：把Redis对齐到DB库存，在途清零
```

**修复方向：永远以 DB 为准**
- 数据库有唯一索引、事务、CHECK 约束，是唯一能自我保证正确性的一层
- Redis 只是可重建的投影，漂移时把 Redis 对齐到 DB，而不是反过来
- 反过来会把已经发生的错误"固化"进权威数据源

---

## 十、MyBatis-Plus 配置

### 10.1 三个拦截器

**文件：`MybatisPlusConfig.java`**

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

    // 1. 分页拦截器
    PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
    pagination.setMaxLimit(500L);       // 单页上限，防 pageSize=1000000 打挂DB
    pagination.setOverflow(false);       // 页码超总页数不返回第一页，防无限翻页爬虫
    interceptor.addInnerInterceptor(pagination);

    // 2. 乐观锁拦截器
    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

    // 3. 防全表更新/删除拦截器
    interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

    return interceptor;
}
```

**三个拦截器的作用：**

| 拦截器 | 作用 | 影响的SQL |
|--------|------|----------|
| PaginationInnerInterceptor | 自动给查询加 LIMIT，maxLimit=500 防大分页 | 只拦截分页查询（IPage 参数的方法） |
| OptimisticLockerInnerInterceptor | 实体类带 @Version 字段的 UPDATE 自动拼 version 条件+自增 | 只拦截带 @Version 的实体更新 |
| BlockAttackInnerInterceptor | 拦截没有 WHERE 条件的 UPDATE/DELETE | 只拦截全表更新/删除 |

**关键设计：**
- **三个拦截器是并列关系，不是包含关系**：都挂在 MybatisPlusInterceptor 总容器下，各管各的。分页拦截器只管分页查询，非分页SQL不受影响。
- **乐观锁怎么用？** 实体类加 `@Version private Integer version;`，更新时自动拼 `WHERE version=?` 并 `SET version=version+1`，并发更新时只有一个能成功，另一个影响行数=0。
- **BlockAttack 为什么重要？** 一次手抖的 `updateById(null)` 或 wrapper 条件写漏，就是全表数据被改。有了它，这类操作在开发阶段就直接报错，不会流到生产。

---

## 核心设计原则总结

### 缓存与一致性
1. **多级缓存**：Caffeine L1（纳秒级，进程内）+ Redis L2（毫秒级，分布式共享）
2. **逻辑过期**：缓存过期不物理删除，返回旧数据+异步重建，用户无感知
3. **Cache Aside**：写库后删缓存（不是更新缓存），删除是幂等的
4. **延迟双删**：AFTER_COMMIT 第一次删 + 500ms 后第二次删，堵竞态窗口
5. **数据库是权威，缓存是可重建的投影**：先提交 DB 事务再回补 Redis，对账以 DB 为准

### 秒杀与超卖
6. **Redis Lua 原子扣减**：库存校验+扣减+一人一单在一个原子操作内完成，不用分布式锁
7. **宁可少卖，绝不超卖**：补偿三层防重（查订单+SETNX守卫+Lua总库存封顶），死信先查订单再补偿
8. **在途计数**：记录"Redis已扣但订单未落库"的数量，用于对账和泄漏检测

### MQ 可靠性
9. **消息持久化**：交换机、队列、消息都是 durable，broker 重启不丢
10. **手动 ACK**：区分5种消费结果（成功/幂等/唯一键冲突/不可重试/可重试），不自动确认
11. **延迟重试**：TTL + DLX 实现5秒间隔重试，不零间隔自旋
12. **死信兜底**：重试耗尽/不可重试的消息进死信，保留现场
13. **发送端三重确认**：发送异常 + Confirm/Return 回调 + 超时扫描
14. **消费幂等靠唯一键**：消息台账与订单创建同事务，uk_message_id 防重复消费

### 安全与防护
15. **ThreadLocal 必须成对清理**：preHandle set / afterCompletion clear，防 Tomcat 线程池复用越权
16. **越权校验用上下文 userId**：不能从前端参数取 userId，防止遍历订单号拿到别人数据
17. **防枚举攻击**：用户不存在和密码错误返回同一错误码，登录失败不区分原因
18. **BlockAttack 拦截器**：拦截无 WHERE 的 UPDATE/DELETE，防手抖全表操作
19. **分页 maxLimit=500**：防 pageSize 过大打挂 DB

### 并发与性能
20. **线程池隔离**：4个线程池各司其职，防止一个功能拖垮全局
21. **条件更新代替先查再改**：支付/关单/扣库存都用 WHERE 条件更新，数据库层面保证并发安全
22. **fail-open 降级**：限流组件 Redis 故障时降级放行，保护手段不影响可用性
23. **对账兜底**：补偿是第一道防线，对账是最后一道防线，定时扫描修复最终不一致
