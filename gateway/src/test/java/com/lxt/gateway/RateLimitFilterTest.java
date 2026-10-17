package com.lxt.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关限流过滤器测试。
 *
 * <p>网关是整个仓库里测试最薄的一块（之前只有一个上下文启动测试）。
 * 但它是**所有流量的第一道闸门**，判定逻辑写错的后果是"要么没保护、要么全站 429"。
 *
 * <p>这里刻意只测<b>判定逻辑</b>：超没超、故障时怎么办、非业务路径要不要计数。
 * "怎么数"（Redis Lua）是实现细节，由 {@link RateCounter} 的 Redis 实现承担。
 */
class RateLimitFilterTest {

    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofSeconds(1);

    /** 可编程的计数器替身。 */
    private static final class StubCounter implements RateCounter {
        private final AtomicInteger calls = new AtomicInteger();
        private long count;
        private boolean fail;

        StubCounter(long count, boolean fail) {
            this.count = count;
            this.fail = fail;
        }

        @Override
        public Mono<Long> increment(String key, Duration window) {
            calls.incrementAndGet();
            return fail ? Mono.error(new IllegalStateException("redis down")) : Mono.just(count);
        }
    }

    private boolean chainInvoked(StubCounter counter, String path) {
        RateLimitFilter filter = new RateLimitFilter(counter, LIMIT, WINDOW);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        AtomicBoolean invoked = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            invoked.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        return invoked.get();
    }

    @Test
    @DisplayName("未超阈值：请求继续向下传递，并回写剩余额度")
    void underLimitPasses() {
        StubCounter counter = new StubCounter(LIMIT, false);
        RateLimitFilter filter = new RateLimitFilter(counter, LIMIT, WINDOW);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/product/list").build());
        AtomicBoolean invoked = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(exchange, ex -> {
            invoked.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(invoked).isTrue();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                .isEqualTo(String.valueOf(LIMIT));
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("超过阈值：返回 429，且不再向下游转发")
    void overLimitRejected() {
        StubCounter counter = new StubCounter(LIMIT + 1, false);
        RateLimitFilter filter = new RateLimitFilter(counter, LIMIT, WINDOW);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/seckill/activities/1/order").build());
        AtomicBoolean invoked = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(exchange, ex -> {
            invoked.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(invoked).as("被限流的请求绝不能继续打到业务服务").isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    @DisplayName("计数器故障时 fail-open：放行并保留 Limit 头，便于告警发现")
    void counterFailureFailsOpen() {
        StubCounter counter = new StubCounter(0, true);

        assertThat(chainInvoked(counter, "/api/product/list"))
                .as("限流组件挂了不能把正常业务全拒绝")
                .isTrue();
        assertThat(counter.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("非 /api 路径（健康检查、监控）不参与计数，否则探针会把自己打死")
    void nonApiPathIsNotCounted() {
        StubCounter counter = new StubCounter(LIMIT + 1, false);

        assertThat(chainInvoked(counter, "/actuator/health")).isTrue();
        assertThat(counter.calls.get()).as("健康检查不应该走限流计数").isZero();
    }
}
