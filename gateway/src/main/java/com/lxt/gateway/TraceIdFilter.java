package com.lxt.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求标识过滤器。
 *
 * <p>如果客户端没带 {@code X-Request-Id}，网关生成一个并透传给下游。
 * 这件事看起来很小，但它让"用户投诉的那一次请求"可以在日志里被精确定位：
 * 前端/客户端只要把 requestId 打到错误提示里，运维就能捞出完整链路。
 *
 * <p>与链路追踪（traceId）的区别：traceId 是<b>采样</b>的、会用于跨服务传播；
 * requestId 是<b>全量</b>的、只用于这一次请求的日志关联。两者互补，不能替代。
 */
@Component
public class TraceIdFilter implements GatewayFilter {

    public static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        String finalRequestId = requestId;

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header(HEADER, finalRequestId))
                .build();
        mutated.getResponse().getHeaders().set(HEADER, finalRequestId);
        return chain.filter(mutated);
    }
}
