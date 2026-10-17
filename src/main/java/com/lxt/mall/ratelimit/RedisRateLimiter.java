package com.lxt.mall.ratelimit;

import com.lxt.mall.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis Lua 的限流器。
 *
 * <p>全部判定逻辑下沉到 Lua，客户端只负责"发一条命令、读一个返回值"。
 * 这样做的直接收益是<b>每次限流判定只消耗一次 RTT</b>；
 * 如果用 {@code INCR + EXPIRE} 两条命令，中间的空隙就是竞态窗口，
 * 而且 RTT 翻倍——在秒杀入口这种 QPS 极高的位置，RTT 就是命。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> slidingWindowScript;
    private final RedisScript<Long> tokenBucketScript;

    /**
     * 滑动窗口限流。
     *
     * @param rule      规则名，参与 key 拼接，避免不同规则在同一维度上串额度
     * @param dimension 维度名
     * @param target    维度取值（userId / ip / 接口名）
     * @return true 放行
     */
    public boolean tryAcquireSlidingWindow(String rule, String dimension, String target,
                                           int limit, Duration window) {
        String key = RedisKeys.rateLimit(rule, dimension, target);
        long now = System.currentTimeMillis();
        // member 用 UUID 而不是固定值：ZSET 的 member 必须唯一，
        // 否则同一毫秒内的多个请求会被 ZADD 覆盖，计数严重偏低。
        String member = now + "-" + UUID.randomUUID();

        Long result = redis.execute(slidingWindowScript, List.of(key),
                String.valueOf(now),
                String.valueOf(window.toMillis()),
                String.valueOf(limit),
                member);

        // 返回 -1 表示超限；>=0 表示放行并带上剩余额度
        return result != null && result >= 0;
    }

    /**
     * 令牌桶限流。
     *
     * @param capacity 桶容量（允许的瞬时突发量）
     * @param rate     每秒补充令牌数
     */
    public boolean tryAcquireTokenBucket(String rule, String dimension, String target,
                                         int capacity, double rate, int permits) {
        String key = RedisKeys.tokenBucket(rule, dimension, target);
        long now = System.currentTimeMillis();

        Long result = redis.execute(tokenBucketScript, List.of(key),
                String.valueOf(capacity),
                String.valueOf(rate),
                String.valueOf(now),
                String.valueOf(permits));

        return result != null && result >= 0;
    }
}
