package com.lxt.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 网关层限流（固定窗口 + Redis 计数）。
 *
 * <h3>为什么把限流放在网关，而不是只放在业务服务里</h3>
 * 业务服务里的 {@code @RateLimit} 是<b>最后一道</b>，不是第一道：
 * <ul>
 *   <li>恶意流量要先经过 Spring MVC 的线程模型、拦截器、参数解析，
 *       才轮到限流切面判断——CPU 已经被消耗掉了；</li>
 *   <li>网关是 WebFlux 非阻塞的，同样的连接数占用的资源少一个数量级；</li>
 *   <li>限流规则变更不需要重新发布业务服务。</li>
 * </ul>
 *
 * <h3>为什么用固定窗口而不是滑动窗口</h3>
 * 固定窗口实现只有一行 Lua（INCR + 首次 PEXPIRE），单次限流判断的成本
 * 是一次 Redis 往返；滑动窗口要用 ZSET 存时间戳，内存和 CPU 都更贵。
 * 代价是<b>边界突刺</b>：窗口切换瞬间可能放过 2 倍流量。
 *
 * <p>这个代价在网关层是可以接受的：网关的限流是"粗粒度保护"，
 * 精确的业务级限流（用户维度、接口维度）仍然在业务服务里做。
 * <b>不要用同一个精度的方案去解决两个不同层次的问题。</b>
 *
 * <h3>fail-open</h3>
 * Redis 不可用时限流放行。理由与业务侧一致：限流是保护措施，
 * 保护措施自己挂掉时不能把正常业务全拒绝掉。代价是失去保护，
 * 需要通过告警让人知道。
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    /**
     * 返回本次窗口内的请求序号（从 1 开始）。
     *
     * <p>为什么要用 Lua：INCR 和 PEXPIRE 必须原子。如果分成两条命令，
     * 在两次命令之间进程崩溃，就会留下一个没有过期时间的计数器——
     * 那个 IP 会被永久限流。这类"计数器泄漏"故障排查起来极其痛苦。
     */
    private static final DefaultRedisScript<Long> COUNTER_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final int limit;
    private final Duration window;

    public RateLimitFilter(ReactiveStringRedisTemplate redis,
                           @Value("${mall.gateway.rate-limit.limit:200}") int limit,
                           @Value("${mall.gateway.rate-limit.window:1s}") Duration window) {
        this.redis = redis;
        this.limit = limit;
        this.window = window;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/api/")) {
            // 健康检查、监控端点不限流，否则探针会被自己的限流打死
            return chain.filter(exchange);
        }

        long windowMillis = Math.max(1, window.toMillis());
        long bucket = System.currentTimeMillis() / windowMillis;
        String key = "gw:rate:" + clientIp(exchange) + ":" + bucket;

        return redis.execute(COUNTER_SCRIPT, List.of(key), String.valueOf(windowMillis))
                .next()
                // 限流组件故障 → -1 → 放行（fail-open）
                .onErrorResume(e -> Mono.just(-1L))
                .defaultIfEmpty(-1L)
                .flatMap(count -> {
                    ServerHttpResponse response = exchange.getResponse();
                    response.getHeaders().add("X-RateLimit-Limit", String.valueOf(limit));
                    if (count > 0 && count <= limit) {
                        response.getHeaders().add("X-RateLimit-Remaining",
                                String.valueOf(limit - count));
                        return chain.filter(exchange);
                    }
                    if (count < 0) {
                        return chain.filter(exchange);
                    }
                    return tooManyRequests(exchange);
                });
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("Retry-After", String.valueOf(Math.max(1, window.toSeconds())));

        String body = "{\"code\":42900,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 取客户端 IP。
     *
     * <p>优先取 {@code X-Forwarded-For} 的第一段（最靠近客户端的代理写入的），
     * 没有则用连接的对端地址。注意这个头<b>可以被客户端伪造</b>，
     * 所以它只能用于"粗粒度保护"，不能用于安全决策（比如白名单）。
     */
    private String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    /** 尽量靠前：在路由转发之前就把超额请求挡掉。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
