package com.lxt.mall.ratelimit;

import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流算法正确性验证。
 *
 * <p>限流最容易出的问题不是"不生效"，而是<b>并发下多放行</b>：
 * {@code INCR} 之后判断、再 {@code EXPIRE}，这两步之间就是竞态窗口。
 * 所以这里除了顺序调用，还要做并发验证。
 */
class RedisRateLimiterTest extends BaseIntegrationTest {

    /** 测试用规则名。 */
    private static final String RULE = "test-rule";
    /** 测试用维度名。 */
    private static final String DIM = "test";

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Test
    @DisplayName("滑动窗口：1 秒内只放行 limit 个请求")
    void slidingWindowAllowsExactlyLimit() {
        String target = "sliding-" + System.nanoTime();
        int limit = 10;

        int allowed = 0;
        for (int i = 0; i < 30; i++) {
            if (rateLimiter.tryAcquireSlidingWindow(RULE, DIM, target, limit, Duration.ofSeconds(1))) {
                allowed++;
            }
        }

        assertThat(allowed).as("顺序调用应当恰好放行 %s 个", limit).isEqualTo(limit);
    }

    @Test
    @DisplayName("滑动窗口：窗口滑过之后额度自动恢复")
    void slidingWindowRecoversAfterWindow() throws Exception {
        String target = "recover-" + System.nanoTime();
        int limit = 3;

        for (int i = 0; i < limit; i++) {
            assertThat(rateLimiter.tryAcquireSlidingWindow(RULE, DIM, target, limit, Duration.ofMillis(500)))
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquireSlidingWindow(RULE, DIM, target, limit, Duration.ofMillis(500)))
                .as("额度用尽后应当拒绝").isFalse();

        Thread.sleep(700);

        assertThat(rateLimiter.tryAcquireSlidingWindow(RULE, DIM, target, limit, Duration.ofMillis(500)))
                .as("窗口滑过后额度应当恢复（旧记录被 ZREMRANGEBYSCORE 清掉）")
                .isTrue();
    }

    @Test
    @DisplayName("滑动窗口并发下不会多放行（Lua 原子性的直接验证）")
    void slidingWindowIsAtomicUnderConcurrency() throws Exception {
        String target = "concurrent-" + System.nanoTime();
        int limit = 20;
        int concurrency = 200;

        AtomicInteger allowed = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    if (rateLimiter.tryAcquireSlidingWindow(
                            RULE, DIM, target, limit, Duration.ofSeconds(60))) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        }

        assertThat(allowed.get())
                .as("并发下放行数必须严格等于 limit；大于 limit 说明脚本不是原子的")
                .isEqualTo(limit);
    }

    @Test
    @DisplayName("不同规则在同一维度上互不串额度（真实 Bug 的回归测试）")
    void differentRulesDoNotShareQuota() {
        String ip = "203.0.113.7";
        String bigRule = "product-detail";
        String smallRule = "seckill";

        // 先用「大额度规则」把这个 IP 的窗口打满 8 次
        for (int i = 0; i < 8; i++) {
            assertThat(rateLimiter.tryAcquireSlidingWindow(bigRule, "IP", ip, 50, Duration.ofSeconds(60)))
                    .as("大额度规则第 %s 次应当放行", i + 1)
                    .isTrue();
        }

        // 「小额度规则」必须仍然拥有完整的 5 次额度
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryAcquireSlidingWindow(smallRule, "IP", ip, 5, Duration.ofSeconds(60)))
                    .as("小额度规则第 %s 次应当放行——"
                            + "如果和第 1 次实现一样把 rule 名漏在 key 之外，"
                            + "这里会因为 ZCARD=8 >= 5 而被误杀", i + 1)
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquireSlidingWindow(smallRule, "IP", ip, 5, Duration.ofSeconds(60)))
                .as("小额度规则用满后应当拒绝")
                .isFalse();

        // 两个 key 必须真实存在且互相独立
        assertThat(redis.hasKey(RedisKeys.rateLimit(bigRule, "IP", ip))).isTrue();
        assertThat(redis.hasKey(RedisKeys.rateLimit(smallRule, "IP", ip))).isTrue();
    }

    @Test
    @DisplayName("令牌桶：容量内允许突发，桶空后按速率恢复")
    void tokenBucketAllowsBurstThenRefills() throws Exception {
        String target = "bucket-" + System.nanoTime();
        int capacity = 5;
        double rate = 2.0;   // 每秒 2 个

        // 桶满时可以连续突发 5 个
        for (int i = 0; i < capacity; i++) {
            assertThat(rateLimiter.tryAcquireTokenBucket(RULE, DIM, target, capacity, rate, 1))
                    .as("第 %s 个突发请求应当被放行", i + 1)
                    .isTrue();
        }
        assertThat(rateLimiter.tryAcquireTokenBucket(RULE, DIM, target, capacity, rate, 1))
                .as("桶空后应当拒绝").isFalse();

        // 等 1.1 秒，应当补充约 2 个令牌
        Thread.sleep(1100);
        assertThat(rateLimiter.tryAcquireTokenBucket(RULE, DIM, target, capacity, rate, 1))
                .as("等待后应当按速率补充令牌").isTrue();
    }

    @Test
    @DisplayName("被限流的请求不会写入 ZSET，避免把攻击者自己锁死并推高内存")
    void rejectedRequestsDoNotExtendTheWindow() {
        String target = "noextend-" + System.nanoTime();
        int limit = 2;

        for (int i = 0; i < limit; i++) {
            rateLimiter.tryAcquireSlidingWindow(RULE, DIM, target, limit, Duration.ofSeconds(10));
        }
        long sizeBefore = redis.opsForZSet().size(RedisKeys.rateLimit(RULE, DIM, target));

        // 继续猛打 100 次，全部会被拒绝
        for (int i = 0; i < 100; i++) {
            rateLimiter.tryAcquireSlidingWindow(RULE, DIM, target, limit, Duration.ofSeconds(10));
        }
        long sizeAfter = redis.opsForZSet().size(RedisKeys.rateLimit(RULE, DIM, target));

        assertThat(sizeAfter)
                .as("被拒绝的请求不应该写入 ZSET，否则持续压测会让 ZSET 无限膨胀")
                .isEqualTo(sizeBefore);
    }
}
