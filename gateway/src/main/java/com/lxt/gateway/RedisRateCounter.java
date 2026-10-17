package com.lxt.gateway;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Redis 固定窗口计数器。
 *
 * <p>INCR 与 PEXPIRE 必须在同一个 Lua 里：分成两条命令时，
 * 如果进程在两次命令之间崩溃，就会留下一个<b>没有过期时间的计数器</b>——
 * 那个 IP 会被永久限流。这类"计数器泄漏"排查起来极其痛苦。
 */
@Component
public class RedisRateCounter implements RateCounter {

    private static final DefaultRedisScript<Long> COUNTER_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisRateCounter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Long> increment(String key, Duration window) {
        return redis.execute(COUNTER_SCRIPT, List.of(key), String.valueOf(window.toMillis()))
                .next();
    }
}
