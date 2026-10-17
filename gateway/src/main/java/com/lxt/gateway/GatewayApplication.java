package com.lxt.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关。
 *
 * <h3>它在系统里承担什么</h3>
 * <ol>
 *   <li><b>统一入口</b>：外部只暴露网关，业务服务不直接对公网开放；</li>
 *   <li><b>限流下沉</b>：把"每 IP 每秒 N 次"这类粗粒度保护从业务进程里挪出来，
 *       业务进程不必为了限流多一次 Redis 往返，也不会因为限流逻辑 bug 影响交易；</li>
 *   <li><b>请求标识</b>：统一注入 {@code X-Request-Id}，让日志与链路追踪有共同锚点。</li>
 * </ol>
 *
 * <h3>它<b>不</b>做什么（刻意的边界）</h3>
 * <ul>
 *   <li>不做 JWT 验签的业务判断——令牌的签发、黑名单、权限都在业务侧，
 *       网关重复实现一份只会产生"两套鉴权规则"的分裂；</li>
 *   <li>不做服务发现——单实例演示用静态上游即可，多实例时应换成注册中心；</li>
 *   <li>不做聚合/编排——网关一旦开始写业务编排，就会变成最难维护的那一层。</li>
 * </ul>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
