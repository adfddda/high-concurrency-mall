package com.lxt.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由定义。
 *
 * <h3>为什么用 Java 而不是 YAML</h3>
 * Spring Cloud Gateway 4.2 起把配置前缀从 {@code spring.cloud.gateway.routes}
 * 挪到了 {@code spring.cloud.gateway.server.webflux.routes}。这种"配置项搬家"
 * 在升级时是纯粹的踩坑点：YAML 写错了不会报错，只会静默地"没有路由"，
 * 表现为所有请求 404。
 *
 * <p>用 {@link RouteLocator} 显式构造，好处是：
 * <ul>
 *   <li>编译期就能发现 API 变化；</li>
 *   <li>路由与过滤器链在一个地方看得全，不用在 YAML 和 Java 之间跳；</li>
 *   <li>上游地址可以走 {@code @Value} 注入，容器编排时用环境变量覆盖。</li>
 * </ul>
 */
@Configuration
public class GatewayRoutes {

    /** 业务服务地址。容器里注入 http://app:8080，本机默认 localhost:8080。 */
    @Value("${mall.upstream:http://localhost:8080}")
    private String upstream;

    @Bean
    public RouteLocator mallRoutes(RouteLocatorBuilder builder, TraceIdFilter traceIdFilter) {
        return builder.routes()
                // 所有业务接口统一转发；限流由全局过滤器完成（见 RateLimitFilter）
                .route("mall-api", r -> r
                        .path("/api/**")
                        .filters(f -> f
                                .filter(traceIdFilter)
                                // 去掉客户端伪造的 X-Forwarded-* 由容器层处理，
                                // 这里只保证往后传的请求带统一标识
                                .addResponseHeader("X-Gateway", "mall-gateway"))
                        .uri(upstream))
                // 健康检查单独放行，方便探针与负载均衡
                .route("mall-actuator", r -> r
                        .path("/actuator/**")
                        .uri(upstream))
                .build();
    }
}
