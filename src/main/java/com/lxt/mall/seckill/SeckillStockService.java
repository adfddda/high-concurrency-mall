package com.lxt.mall.seckill;

import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.common.util.RedisClusterSlot;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.enums.SeckillMode;
import com.lxt.mall.metrics.MallMetrics;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀库存的 Redis 操作封装（Lua 脚本的唯一出口）。
 *
 * <p>所有涉及秒杀库存的命令都从这里发出，好处是
 * 「Hash Tag 一致性」这件事只需要在一个地方保证。
 * 类初始化时还会做一次同槽自检，把配置错误暴露在启动阶段而不是运行时。
 *
 * <h3>库存分桶（可选，默认关闭）</h3>
 * 单个活动的库存是一个天然的热点 key。Redis Cluster 下它只落在一个分片，
 * 该分片的单线程能力就是活动吞吐的上限——加机器也救不了。
 * {@code mall.seckill.bucket-count} 把它拆成 N 个桶，按 userId 哈希路由：
 * <pre>
 *   bucket = floorMod(hash(userId), N)
 *   keys   = seckill:stock:{act:N:bK} / bought / inflight / codes
 * </pre>
 * 同一个用户永远落同一个桶，因此「一人一单」和「资格码消费」都限制在桶内，
 * 一个 Lua 只需操作同槽的 4 个 key，同时不同桶能分散到不同分片。
 *
 * <p>三个必须说清楚的代价：桶间负载由哈希决定（不可控）；不做跨桶回退
 * （会少卖、不会超卖）；单机 Redis 上分桶没有任何吞吐收益。
 */
@Slf4j
@Service
public class SeckillStockService {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> seckillDeductScript;
    private final RedisScript<Long> seckillRollbackScript;
    private final RedisScript<Long> seckillInflightDecScript;
    private final LocalSoldOutCache localSoldOutCache;
    private final MallProperties properties;
    private final MallMetrics metrics;
    private final int bucketCount;

    public SeckillStockService(StringRedisTemplate redis,
                               RedisScript<Long> seckillDeductScript,
                               RedisScript<Long> seckillRollbackScript,
                               RedisScript<Long> seckillInflightDecScript,
                               LocalSoldOutCache localSoldOutCache,
                               MallProperties properties,
                               MallMetrics metrics) {
        this.redis = redis;
        this.seckillDeductScript = seckillDeductScript;
        this.seckillRollbackScript = seckillRollbackScript;
        this.seckillInflightDecScript = seckillInflightDecScript;
        this.localSoldOutCache = localSoldOutCache;
        this.properties = properties;
        this.metrics = metrics;
        this.bucketCount = Math.max(1, properties.getSeckill().getBucketCount());
        if (bucketCount > 1) {
            log.info("秒杀库存分桶已启用 bucketCount={}（注意：单机 Redis 无吞吐收益，收益在 Cluster）",
                    bucketCount);
        }
    }

    /**
     * 启动自检：验证秒杀相关的 key 落在同一个哈希槽。
     *
     * <p>这个检查的价值在于把一类<b>只在 Redis Cluster 下才会暴露</b>的故障
     * 提前到启动阶段。单机 Redis 永远不会报 CROSSSLOT，所以
     * 一旦有人在重构时"顺手"把 Hash Tag 去掉，
     * 单机环境全绿、一上集群全崩。启动即失败远好过上线即故障。
     */
    @PostConstruct
    void assertSameSlot() {
        long probe = 1L;
        assertSameSlot(probe, -1);
        if (bucketCount > 1) {
            assertSameSlot(probe, 0);
        }
    }

    private void assertSameSlot(long activityId, int bucket) {
        List<String> keys = deductKeys(activityId, bucket);
        if (!RedisClusterSlot.sameSlot(keys.toArray(new String[0]))) {
            throw new IllegalStateException(
                    "秒杀相关 key 未落在同一个哈希槽，Redis Cluster 下 Lua 会直接报 CROSSSLOT。"
                            + " 请检查 RedisKeys 中是否遗漏了 Hash Tag。keys=" + keys);
        }
        log.debug("秒杀 key 同槽自检通过 bucket={} slots={}", bucket,
                keys.stream().map(RedisClusterSlot::slot).toList());
    }

    // ========================================================================
    // 分桶路由
    // ========================================================================

    /**
     * 用户所属的桶。返回 -1 表示未启用分桶（沿用旧的单 key 格式）。
     *
     * <p>用 {@link Long#hashCode} 而不是取模 userId 本身：雪花 ID 低位有规律，
     * 直接取模容易把连续 ID 分到同一个桶。哈希后再取模能打散。
     */
    public int bucketOf(Long userId) {
        if (bucketCount <= 1) {
            return -1;
        }
        long id = userId == null ? 0L : userId;
        return Math.floorMod(Long.hashCode(id), bucketCount);
    }

    /** 每个桶分到的库存份额（余数分给前几个桶）。 */
    private int share(int total, int bucket) {
        if (bucket < 0) {
            return total;
        }
        int base = total / bucketCount;
        int remainder = total % bucketCount;
        return base + (bucket < remainder ? 1 : 0);
    }

    // ========================================================================
    // 扣减 / 回补
    // ========================================================================

    /**
     * 原子扣减：库存校验 + 扣减 + 一人一单去重 + 资格码消费 + 在途计数，
     * 全部在一个 Lua 里完成。
     *
     * @param mode        活动模式，{@link SeckillMode#LOTTERY} 时校验资格码
     * @param qualifyCode 资格码（抽签模式必填，其它模式忽略）
     */
    public SeckillDeductResult deduct(Long activityId, Long userId, SeckillMode mode, String qualifyCode) {
        int bucket = bucketOf(userId);
        List<String> keys = deductKeys(activityId, bucket);
        long start = System.nanoTime();
        Long result = redis.execute(seckillDeductScript, keys,
                String.valueOf(userId),
                properties.getSeckill().isInflightTracking() ? "1" : "0",
                String.valueOf(properties.getSeckill().getBoughtSetTtl().toSeconds()),
                mode == SeckillMode.LOTTERY ? "1" : "0",
                qualifyCode == null ? "" : qualifyCode);
        metrics.recordSeckillLua(System.nanoTime() - start);

        if (result == null) {
            throw new IllegalStateException("秒杀扣减脚本返回 null，请检查 Redis 连接与脚本内容");
        }
        SeckillDeductResult deductResult = SeckillDeductResult.of(result);

        if (deductResult == SeckillDeductResult.SOLD_OUT && !hasStockLeft(activityId)) {
            // 只有"全部分桶都空了"才标记售罄。
            // 分桶模式下，用户所在桶空了不代表别的桶也空了——
            // 如果只看本桶就打本地标记，会把还有库存的活动整个屏蔽掉。
            localSoldOutCache.markSoldOut(activityId);
        }
        return deductResult;
    }

    /** 兼容重载：直接抢购模式，无资格码。 */
    public SeckillDeductResult deduct(Long activityId, Long userId) {
        return deduct(activityId, userId, SeckillMode.DIRECT, null);
    }

    /**
     * 回补库存（回补到该用户所属的桶）。
     *
     * @param releaseQualification true=同时回收该用户的购买资格（订单没落库的场景）
     * @param decInflight          是否归还"在途计数"
     * @return 回补后的库存；-1 表示 key 已不存在（活动已结束）
     */
    public long rollback(Long activityId, Long userId, int totalStock,
                         boolean releaseQualification, boolean decInflight) {
        int bucket = bucketOf(userId);
        List<String> keys = rollbackKeys(activityId, bucket);
        // 分桶时用"该桶的份额"作为上限，避免某个桶被回补到超过自己的配额
        int cap = share(totalStock, bucket);
        Long result = redis.execute(seckillRollbackScript, keys,
                String.valueOf(userId),
                String.valueOf(cap),
                decInflight ? "1" : "0",
                releaseQualification ? "1" : "0");

        // 库存被放回来了，本实例的"售罄"标记必须立刻清掉，
        // 否则会一直拒绝本机的用户请求（详见 LocalSoldOutCache 的注释）。
        localSoldOutCache.clear(activityId);

        long value = result == null ? -1 : result;
        if (value >= 0) {
            log.info("秒杀库存已回补 activityId={} userId={} bucket={} 回补后库存={} 回收资格={}",
                    activityId, userId, bucket, value, releaseQualification);
        }
        return value;
    }

    /** 订单成功落库后归还在途计数（回补到该用户所属的桶）。 */
    public void inflightDec(Long activityId, Long userId) {
        if (!properties.getSeckill().isInflightTracking()) {
            return;
        }
        try {
            int bucket = bucketOf(userId);
            redis.execute(seckillInflightDecScript,
                    List.of(RedisKeys.seckillInflight(activityId, bucket)));
        } catch (Exception e) {
            // 在途计数只影响对账精度，失败不能影响主链路
            log.warn("归还在途计数失败 activityId={}（不影响订单）", activityId, e);
        }
    }

    /**
     * 补偿守卫。
     *
     * <p>同一条消息的补偿可能从多个入口触发（发送异常、Confirm nack、Return 回调、
     * 定时扫描）。用 Redis SETNX 做一次性闸门，保证库存只回补一次。
     * TTL 取 24 小时，足够覆盖任何合理的补偿延迟。
     */
    public boolean tryAcquireCompensateGuard(String messageId) {
        Boolean ok = redis.opsForValue().setIfAbsent(
                RedisKeys.seckillCompensateGuard(messageId), "1", Duration.ofHours(24));
        return Boolean.TRUE.equals(ok);
    }

    // ========================================================================
    // 查询与维护（分桶模式下都是"聚合"语义）
    // ========================================================================

    /** 剩余库存总和；null 表示活动未预热。 */
    public Integer getStock(Long activityId) {
        if (bucketCount <= 1) {
            String v = redis.opsForValue().get(RedisKeys.seckillStock(activityId, -1));
            return v == null ? null : Integer.valueOf(v);
        }
        boolean any = false;
        int sum = 0;
        for (int b = 0; b < bucketCount; b++) {
            String v = redis.opsForValue().get(RedisKeys.seckillStock(activityId, b));
            if (v != null) {
                any = true;
                sum += Integer.parseInt(v);
            }
        }
        return any ? sum : null;
    }

    /** 在途计数总和。 */
    public Integer getInflight(Long activityId) {
        if (bucketCount <= 1) {
            String v = redis.opsForValue().get(RedisKeys.seckillInflight(activityId, -1));
            return v == null ? 0 : Integer.valueOf(v);
        }
        int sum = 0;
        for (int b = 0; b < bucketCount; b++) {
            String v = redis.opsForValue().get(RedisKeys.seckillInflight(activityId, b));
            if (v != null) {
                sum += Integer.parseInt(v);
            }
        }
        return sum;
    }

    public boolean isBought(Long activityId, Long userId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(
                RedisKeys.seckillBought(activityId, bucketOf(userId)), String.valueOf(userId)));
    }

    private boolean hasStockLeft(Long activityId) {
        if (bucketCount <= 1) {
            return false;
        }
        Integer stock = getStock(activityId);
        return stock != null && stock > 0;
    }

    /** 预热：把库存按桶拆分写入 Redis，并设置覆盖活动周期的 TTL。 */
    public void preheatStock(Long activityId, int availableStock, Duration ttl) {
        if (bucketCount <= 1) {
            redis.opsForValue().set(RedisKeys.seckillStock(activityId, -1),
                    String.valueOf(availableStock), ttl);
        } else {
            for (int b = 0; b < bucketCount; b++) {
                redis.opsForValue().set(RedisKeys.seckillStock(activityId, b),
                        String.valueOf(share(availableStock, b)), ttl);
            }
        }
        localSoldOutCache.clear(activityId);
    }

    /** 强制把 Redis 库存改写为指定值（对账修复用），同样按桶拆分。 */
    public void resetStock(Long activityId, int stock, Duration ttl) {
        if (bucketCount <= 1) {
            redis.opsForValue().set(RedisKeys.seckillStock(activityId, -1), String.valueOf(stock), ttl);
        } else {
            for (int b = 0; b < bucketCount; b++) {
                redis.opsForValue().set(RedisKeys.seckillStock(activityId, b),
                        String.valueOf(share(stock, b)), ttl);
            }
        }
        localSoldOutCache.clear(activityId);
    }

    public void setActivitySnapshot(Long activityId, String json, Duration ttl) {
        redis.opsForValue().set(RedisKeys.seckillActivity(activityId), json, ttl);
    }

    public String getActivitySnapshot(Long activityId) {
        return redis.opsForValue().get(RedisKeys.seckillActivity(activityId));
    }

    public void releaseInflightKey(Long activityId) {
        redis.delete(inflightKeys(activityId));
    }

    /** 对账修复用：把在途计数强制归零（说明之前的在途已经泄漏）。 */
    public void resetInflight(Long activityId) {
        for (String key : inflightKeys(activityId)) {
            redis.opsForValue().set(key, "0", Duration.ofHours(24));
        }
    }

    public void setBoughtSetExpire(Long activityId, Duration ttl) {
        // 已购集合必须设置 TTL：否则活动结束后它会永久占用内存
        for (int b = -1; b < (bucketCount <= 1 ? 0 : bucketCount); b++) {
            redis.expire(RedisKeys.seckillBought(activityId, b), ttl.toSeconds(), TimeUnit.SECONDS);
        }
    }

    public long boughtCount(Long activityId) {
        if (bucketCount <= 1) {
            Long size = redis.opsForSet().size(RedisKeys.seckillBought(activityId, -1));
            return size == null ? 0 : size;
        }
        long sum = 0;
        for (int b = 0; b < bucketCount; b++) {
            Long size = redis.opsForSet().size(RedisKeys.seckillBought(activityId, b));
            sum += size == null ? 0 : size;
        }
        return sum;
    }

    // ========================================================================

    private List<String> inflightKeys(Long activityId) {
        if (bucketCount <= 1) {
            return List.of(RedisKeys.seckillInflight(activityId, -1));
        }
        return java.util.stream.IntStream.range(0, bucketCount)
                .mapToObj(b -> RedisKeys.seckillInflight(activityId, b))
                .toList();
    }

    /** 回补脚本的 key 列表，顺序必须与 seckill_rollback.lua 的 KEYS[1..3] 对应。 */
    private List<String> rollbackKeys(Long activityId, int bucket) {
        return List.of(
                RedisKeys.seckillStock(activityId, bucket),
                RedisKeys.seckillBought(activityId, bucket),
                RedisKeys.seckillInflight(activityId, bucket));
    }

    /**
     * 扣减脚本的 key 列表（多一个资格码集合）。
     *
     * <p>四个 key 必须同槽——这是抽签模式和直接抢模式能共用一套扣减逻辑的前提。
     * 分桶后同一桶内的 4 个 key 仍然同槽，只是不同桶落在不同槽上。
     */
    private List<String> deductKeys(Long activityId, int bucket) {
        return List.of(
                RedisKeys.seckillStock(activityId, bucket),
                RedisKeys.seckillBought(activityId, bucket),
                RedisKeys.seckillInflight(activityId, bucket),
                RedisKeys.seckillQualifyCodes(activityId, bucket));
    }
}
