package com.lxt.mall.seckill;

import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.common.util.RedisClusterSlot;
import com.lxt.mall.config.MallProperties;
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
    }

    /**
     * 启动自检：验证秒杀相关的三个 key 落在同一个哈希槽。
     *
     * <p>这个检查的价值在于把一类<b>只在 Redis Cluster 下才会暴露</b>的故障
     * 提前到启动阶段。单机 Redis 永远不会报 CROSSSLOT，所以
     * 一旦有人在重构时"顺手"把 {@code RedisKeys.activityTag} 去掉，
     * 单机环境全绿、一上集群全崩。启动即失败远好过上线即故障。
     */
    @PostConstruct
    void assertSameSlot() {
        long probe = 1L;
        List<String> keys = seckillKeys(probe);
        if (!RedisClusterSlot.sameSlot(keys.toArray(new String[0]))) {
            throw new IllegalStateException(
                    "秒杀相关 key 未落在同一个哈希槽，Redis Cluster 下 Lua 会直接报 CROSSSLOT。"
                            + " 请检查 RedisKeys 中是否遗漏了 Hash Tag。keys=" + keys);
        }
        log.debug("秒杀 key 同槽自检通过: slots={}", keys.stream().map(RedisClusterSlot::slot).toList());
    }

    /**
     * 原子扣减：库存校验 + 扣减 + 一人一单去重 + 在途计数，全部在一个 Lua 里完成。
     */
    public SeckillDeductResult deduct(Long activityId, Long userId) {
        List<String> keys = seckillKeys(activityId);
        long start = System.nanoTime();
        Long result = redis.execute(seckillDeductScript, keys,
                String.valueOf(userId),
                properties.getSeckill().isInflightTracking() ? "1" : "0");
        metrics.recordSeckillLua(System.nanoTime() - start);

        if (result == null) {
            throw new IllegalStateException("秒杀扣减脚本返回 null，请检查 Redis 连接与脚本内容");
        }
        SeckillDeductResult deductResult = SeckillDeductResult.of(result);

        if (deductResult == SeckillDeductResult.SOLD_OUT) {
            // 本地标记：把后续注定失败的请求挡在 Redis 之前
            localSoldOutCache.markSoldOut(activityId);
        }
        return deductResult;
    }

    /**
     * 回补库存。
     *
     * @param releaseQualification true=同时回收该用户的购买资格（订单没落库的场景）
     *                             false=只还库存，不回收资格（超时关单的场景）
     * @param decInflight          是否归还"在途计数"
     * @return 回补后的库存；-1 表示 key 已不存在（活动已结束）
     */
    public long rollback(Long activityId, Long userId, int totalStock,
                         boolean releaseQualification, boolean decInflight) {
        List<String> keys = seckillKeys(activityId);
        Long result = redis.execute(seckillRollbackScript, keys,
                String.valueOf(userId),
                String.valueOf(totalStock),
                decInflight ? "1" : "0",
                releaseQualification ? "1" : "0");

        // 库存被放回来了，本实例的"售罄"标记必须立刻清掉，
        // 否则会一直拒绝本机的用户请求（详见 LocalSoldOutCache 的注释）。
        localSoldOutCache.clear(activityId);

        long value = result == null ? -1 : result;
        if (value >= 0) {
            log.info("秒杀库存已回补 activityId={} userId={} 回补后库存={} 回收资格={}",
                    activityId, userId, value, releaseQualification);
        }
        return value;
    }

    /** 订单成功落库后归还在途计数。 */
    public void inflightDec(Long activityId) {
        if (!properties.getSeckill().isInflightTracking()) {
            return;
        }
        try {
            redis.execute(seckillInflightDecScript, List.of(RedisKeys.seckillInflight(activityId)));
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

    public Integer getStock(Long activityId) {
        String v = redis.opsForValue().get(RedisKeys.seckillStock(activityId));
        return v == null ? null : Integer.valueOf(v);
    }

    public Integer getInflight(Long activityId) {
        String v = redis.opsForValue().get(RedisKeys.seckillInflight(activityId));
        return v == null ? 0 : Integer.valueOf(v);
    }

    public boolean isBought(Long activityId, Long userId) {
        return Boolean.TRUE.equals(redis.opsForSet()
                .isMember(RedisKeys.seckillBought(activityId), String.valueOf(userId)));
    }

    /** 预热：把库存与活动快照写入 Redis，并设置覆盖活动周期的 TTL。 */
    public void preheatStock(Long activityId, int availableStock, Duration ttl) {
        redis.opsForValue().set(RedisKeys.seckillStock(activityId), String.valueOf(availableStock), ttl);
        localSoldOutCache.clear(activityId);
    }

    /** 强制把 Redis 库存改写为指定值（对账修复用）。 */
    public void resetStock(Long activityId, int stock, Duration ttl) {
        redis.opsForValue().set(RedisKeys.seckillStock(activityId), String.valueOf(stock), ttl);
        localSoldOutCache.clear(activityId);
    }

    public void setActivitySnapshot(Long activityId, String json, Duration ttl) {
        redis.opsForValue().set(RedisKeys.seckillActivity(activityId), json, ttl);
    }

    public String getActivitySnapshot(Long activityId) {
        return redis.opsForValue().get(RedisKeys.seckillActivity(activityId));
    }

    public void releaseInflightKey(Long activityId) {
        redis.delete(RedisKeys.seckillInflight(activityId));
    }

    /** 对账修复用：把在途计数强制归零（说明之前的在途已经泄漏）。 */
    public void resetInflight(Long activityId) {
        redis.opsForValue().set(RedisKeys.seckillInflight(activityId), "0", Duration.ofHours(24));
    }

    public void setBoughtSetExpire(Long activityId, Duration ttl) {
        // 已购集合必须设置 TTL：否则活动结束后它会永久占用内存
        redis.expire(RedisKeys.seckillBought(activityId), ttl.toSeconds(), TimeUnit.SECONDS);
    }

    public long boughtCount(Long activityId) {
        Long size = redis.opsForSet().size(RedisKeys.seckillBought(activityId));
        return size == null ? 0 : size;
    }

    /** 秒杀三件套的 key，顺序必须与 Lua 脚本里的 KEYS[1..3] 严格对应。 */
    private List<String> seckillKeys(Long activityId) {
        return List.of(
                RedisKeys.seckillStock(activityId),
                RedisKeys.seckillBought(activityId),
                RedisKeys.seckillInflight(activityId));
    }
}
