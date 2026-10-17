package com.lxt.mall.common.constant;

/**
 * Redis Key 统一规范。
 *
 * <p>三条硬规矩：
 * <ol>
 *   <li><b>所有 key 只能从这里生成</b>，不允许在业务代码里手写字符串拼接。
 *       key 一旦散落各处，改前缀、查大 key、做容量规划都无从下手。</li>
 *   <li><b>命名格式固定为 {@code 业务域:对象:标识}</b>，
 *       用冒号分层，Redis Insight / redis-cli --scan 能按层级过滤。</li>
 *   <li><b>秒杀相关的 key 必须带 Hash Tag {@code {act:N}}</b>，
 *       否则 Lua 脚本在集群模式下会 CROSSSLOT 报错，见
 *       {@link com.lxt.mall.common.util.RedisClusterSlot}。</li>
 * </ol>
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ========================================================================
    // Hash Tag
    // ========================================================================

    /**
     * 活动级 Hash Tag。
     *
     * <p>大括号内的内容是 Redis Cluster 计算槽位时唯一参考的部分，
     * 所以 {@code seckill:stock:{act:2001}}、{@code seckill:bought:{act:2001}}、
     * {@code seckill:inflight:{act:2001}} 三个 key 必然同槽。
     *
     * <p>注意 tag 里刻意保留了业务语义（act 前缀），而不是裸的 {@code {2001}}：
     * 不同业务域的数字 ID 容易撞车（活动 2001 和商品 2001），
     * 加上前缀能避免"两个本不相关的 key 被强行放进同一个槽"造成数据倾斜。
     */
    public static String activityTag(Long activityId) {
        return "{act:" + activityId + "}";
    }

    // ========================================================================
    // 秒杀
    // ========================================================================

    /** 剩余可抢库存，String。 */
    public static String seckillStock(Long activityId) {
        return "seckill:stock:" + activityTag(activityId);
    }

    /** 已抢购用户集合，Set。一人一单的准入判定。 */
    public static String seckillBought(Long activityId) {
        return "seckill:bought:" + activityTag(activityId);
    }

    /** 在途计数，String。Redis 已扣减但订单尚未落库的请求数。 */
    public static String seckillInflight(Long activityId) {
        return "seckill:inflight:" + activityTag(activityId);
    }

    /**
     * 资格码集合，Set。抽签活动的"中签凭证"。
     *
     * <p>为什么资格码放 Redis 而不是只放数据库：扣减脚本需要在<b>同一个原子操作</b>里
     * 完成"校验资格码 → 消费资格码 → 扣库存"。如果资格码校验要回查数据库，
     * 就退化成"先查再扣"，中间会出现
     * 「同一个码被两个并发请求同时判定有效」的窗口。
     *
     * <p>数据库仍然是权威（t_seckill_reservation.qualify_code），
     * Redis 只是它的可重建投影——丢了可以重新灌。
     */
    public static String seckillQualifyCodes(Long activityId) {
        return "seckill:codes:" + activityTag(activityId);
    }

    /** 活动元信息快照，String(JSON)。 */
    public static String seckillActivity(Long activityId) {
        return "seckill:activity:" + activityId;
    }

    /** 秒杀结果，String(JSON)。消费者落库后写入，供前端轮询。 */
    public static String seckillResult(String orderNo) {
        return "seckill:result:" + orderNo;
    }

    /**
     * 抢购结果的订单快照，String(JSON)。
     *
     * <p>为什么除了结果状态还要单独存一份订单快照：
     * 前端轮询 {@code /api/seckill/result/{orderNo}} 时，如果每次都去数据库
     * 查一次订单，一个正在等结果的用户每分钟能产生几十次 DB 读——刚刚扛住
     * 写洪峰的数据库，会被自己的轮询流量再打一遍。
     *
     * <p>把订单快照写进 Redis 并给一个短 TTL（5 分钟），足够覆盖"抢到→看到结果"
     * 这个窗口；超过窗口后回落到数据库，所以缓存丢失不会导致查不到订单。
     */
    public static String seckillOrder(String orderNo) {
        return "seckill:order:" + orderNo;
    }

    /** 已预热的活动 ID 集合，Set。用于启动时判断是否需要重新预热。 */
    public static final String SECKILL_PREHEATED_SET = "seckill:preheated";

    /**
     * 补偿一次性守卫，String(SETNX)。
     *
     * <p>同一条消息的补偿可能从多个入口触发（发送异常、Confirm nack、
     * Return 回调、定时扫描），靠这个 key 保证库存只回补一次。
     */
    public static String seckillCompensateGuard(String messageId) {
        return "seckill:compensate:guard:" + messageId;
    }

    // ========================================================================
    // 缓存
    // ========================================================================

    /**
     * 商品维度的 Hash Tag。
     *
     * <p>和秒杀的 {@link #activityTag} 是同一个道理，但这里要解决的是一个
     * <b>更隐蔽的问题</b>：{@link #productCache} 与 {@link #productNullCache}
     * 在失效时是<b>一起被 DEL 的</b>（{@code redis.delete(List.of(a, b))}）。
     *
     * <p>Redis Cluster 的 {@code DEL} 是<b>多 key 命令</b>，要求所有 key 同槽。
     * 如果两个 key 没有 Hash Tag：
     * <pre>
     *   cache:product:1001        → 槽 A
     *   cache:product:null:1001   → 槽 B（大概率不同）
     *   DEL cache:product:1001 cache:product:null:1001  → CROSSSLOT 报错
     * </pre>
     * 而<b>缓存失效失败是静默的</b>——用户会一直读到旧数据，直到 TTL 到期。
     * 这类"不报错但数据不对"的问题，比直接抛异常难排查得多。
     */
    public static String productTag(Long productId) {
        return "{p:" + productId + "}";
    }

    /** 商品详情缓存（带逻辑过期包装）。 */
    public static String productCache(Long productId) {
        return "cache:product:" + productTag(productId);
    }

    /**
     * 商品详情空值占位（防穿透）。
     *
     * <p>与真实缓存<b>共用同一个 Hash Tag</b>，因此两者同槽，
     * 可以用一条 {@code DEL} 一起删掉——它们本来也应该被同时失效。
     */
    public static String productNullCache(Long productId) {
        return "cache:product:null:" + productTag(productId);
    }

    /** 秒杀活动列表缓存。 */
    public static final String SECKILL_ACTIVITY_LIST = "cache:seckill:list";

    /** 商品布隆过滤器。 */
    public static final String PRODUCT_BLOOM = "bloom:product";

    // ========================================================================
    // 分布式锁
    // ========================================================================

    /** 商品缓存重建锁。 */
    public static String productRebuildLock(Long productId) {
        return "lock:product:rebuild:" + productId;
    }

    /** 活动预热锁。 */
    public static String activityPreheatLock(Long activityId) {
        return "lock:seckill:preheat:" + activityId;
    }

    // ========================================================================
    // 限流
    // ========================================================================

    /**
     * 滑动窗口限流 key。
     *
     * <p><b>为什么 key 里必须包含 rule 名（这是一个真实踩到的 Bug）</b>
     *
     * <p>最初的实现是 {@code rate:limit:{维度}:{目标}}，看起来挺合理，但它有一个
     * 隐蔽的致命问题：<b>不同规则会在同一个维度上撞车</b>。
     *
     * <pre>
     *   product-detail 规则：IP=127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值 50/s）
     *   seckill       规则：IP=127.0.0.1 → rate:limit:IP:127.0.0.1   （阈值  5/s）
     * </pre>
     *
     * 用户先浏览 8 次商品详情，这 8 个请求被记进同一个 ZSET；
     * 紧接着点秒杀时，脚本读到 {@code ZCARD = 8 >= 5}，于是<b>正常用户被误判为刷接口</b>。
     * 更糟的是这个 Bug 只在"先访问某个大额度的接口、再访问小额度接口"时才复现，
     * 单独压测秒杀接口永远发现不了。
     *
     * <p>把 rule 名加进 key 之后语义也变得更清晰：
     * <ul>
     *   <li>不同规则之间完全隔离，各自维护自己的额度；</li>
     *   <li>同一个 rule 被多个接口引用时，它们<b>有意共享</b>一份额度
     *       ——这正是"一个规则多处复用、只维护一份配置"想要的效果。</li>
     * </ul>
     *
     * @param rule      规则名（{@code mall.rate-limit.rules.<rule>} 的键）
     * @param dimension 维度（global / api / user / ip）
     * @param target    维度对应的值（接口名 / userId / ip）
     */
    public static String rateLimit(String rule, String dimension, String target) {
        return "rate:limit:" + rule + ":" + dimension + ":" + target;
    }

    /** 令牌桶 key。同样必须包含 rule 名，理由见 {@link #rateLimit}。 */
    public static String tokenBucket(String rule, String dimension, String target) {
        return "rate:token:" + rule + ":" + dimension + ":" + target;
    }
}
