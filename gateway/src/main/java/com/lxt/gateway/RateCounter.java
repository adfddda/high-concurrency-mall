package com.lxt.gateway;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 限流计数器（网关侧的端口）。
 *
 * <p>把"计数"从过滤器里抽出来的唯一理由是<b>可测</b>：
 * 直接依赖 {@code ReactiveStringRedisTemplate} 时，要验证"超额返回 429""Redis 故障 fail-open"
 * 这两条分支，就得起一个真 Redis 或者 mock 一串响应式 API。
 * 抽出一个只有一个方法的接口后，测试里给个 10 行的假实现就够了。
 *
 * <p>这不是"为了设计而设计"——{@link RateLimitFilter} 里真正需要被验证的恰恰是
 * <b>判定逻辑</b>（超没超、故障怎么办），而"怎么数"是实现细节。
 */
public interface RateCounter {

    /**
     * 对 key 计数并返回本次窗口内的序号（从 1 开始）。
     *
     * <p>负数或空表示"计数器不可用"——调用方据此 fail-open。
     */
    Mono<Long> increment(String key, Duration window);
}
