package com.lxt.mall.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 业务埋点。
 *
 * <p>为什么业务代码要关心监控？因为<b>面试里"你怎么知道系统是健康的"
 * 和"你怎么实现的"一样重要</b>。这个项目的每一个关键决策都需要一个指标来验证：
 * <ul>
 *   <li>Lua 扣减是否真的没超卖 → {@code mall.seckill.result} 按 reason 分桶计数</li>
 *   <li>多级缓存的命中率是否真的提升了 → {@code mall.cache.access} 分 level 计数</li>
 *   <li>MQ 是否在丢消息 → {@code mall.mq.send} / {@code mall.mq.consume}</li>
 *   <li>限流是保护还是误伤 → {@code mall.rate.limit.rejected}</li>
 * </ul>
 *
 * <p>计数器用 {@link ConcurrentHashMap} 缓存句柄：Micrometer 的
 * {@code registry.counter(name, tags)} 每次调用都要做一次标签拼接和 map 查找，
 * 放在秒杀热路径上是纯粹的浪费。
 */
@Component
public class MallMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    public MallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ---------------- 秒杀 ----------------

    /** @param reason success / sold_out / repeat / not_prepared / not_started / ended / mq_failed / limited */
    public void seckillResult(String reason) {
        counter("mall.seckill.result", "reason", reason).increment();
    }

    /** 记录一次 Lua 脚本耗时。这是衡量 Redis 侧压力的最直接指标。 */
    public void recordSeckillLua(long nanos) {
        timer("mall.seckill.lua").record(java.time.Duration.ofNanos(nanos));
    }

    // ---------------- 缓存 ----------------

    /** @param level l1 / l2 / db；@param result hit / miss */
    public void cacheAccess(String cacheName, String level, String result) {
        counter("mall.cache.access", "cache", cacheName, "level", level, "result", result).increment();
    }

    /** 缓存重建耗时（含查 DB + 写回）。 */
    public void recordCacheRebuild(String cacheName, long nanos) {
        timer("mall.cache.rebuild", "cache", cacheName).record(java.time.Duration.ofNanos(nanos));
    }

    // ---------------- 消息 ----------------

    /** @param action send / confirm_ack / confirm_nack / return / consume_success / consume_retry / consume_dlq */
    public void mqEvent(String topic, String action) {
        counter("mall.mq.event", "topic", topic, "action", action).increment();
    }

    // ---------------- 限流 ----------------

    public void rateLimitRejected(String api, String dimension) {
        counter("mall.rate.limit.rejected", "api", api, "dimension", dimension).increment();
    }

    /** 限流组件自身故障而降级放行的次数。这个值一旦 > 0 就必须告警。 */
    public void rateLimitDegraded(String api) {
        counter("mall.rate.limit.degraded", "api", api).increment();
    }

    // ---------------- 对账 ----------------

    public void reconcileDrift(long activityId, int drift) {
        counter("mall.reconcile.drift", "activity", String.valueOf(activityId)).increment();
        registry.gauge("mall.reconcile.last.drift",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("activity", String.valueOf(activityId))),
                drift);
    }

    // ---------------- 内部工具 ----------------

    private Counter counter(String name, String... tags) {
        String key = name + '|' + String.join(",", tags);
        return counters.computeIfAbsent(key, k -> Counter.builder(name).tags(tags).register(registry));
    }

    private Timer timer(String name, String... tags) {
        String key = name + '|' + String.join(",", tags);
        return timers.computeIfAbsent(key, k -> Timer.builder(name)
                .tags(tags)
                .publishPercentileHistogram()
                .register(registry));
    }
}
