package com.lxt.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 网关上下文冒烟测试。
 *
 * <p>它验证的不是"能转发请求"，而是<b>依赖版本矩阵能装配起来</b>：
 * Spring Cloud Gateway 的自动配置一旦与 Spring Boot 版本不匹配，
 * 典型表现就是启动期 NoSuchMethodError / 路由未注册，
 * 这类问题在编译期完全看不出来。
 *
 * <p>不校验真实转发：那需要一个上游服务 + Redis，属于集成测试的范畴，
 * 由 docker-compose 的 full profile 承担。单元/上下文测试的边界应该清晰，
 * 否则会变成"看起来在测，其实什么也没验证"。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "mall.upstream=http://localhost:18080",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
class GatewayContextTest {

    @Test
    void contextLoads() {
        // 能启动即通过
    }
}
