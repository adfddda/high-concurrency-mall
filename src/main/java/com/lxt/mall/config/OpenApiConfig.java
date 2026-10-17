package com.lxt.mall.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 *
 * <p>文档本身价值有限，真正的价值在于<b>接口契约可以被机器消费</b>：
 * 前端联调、契约测试、生成 SDK 都基于同一份 spec，
 * 而不是靠口头约定或一份会过期的 Markdown。
 *
 * <p>这里声明了 bearer 认证方案，Swagger UI 上可以直接填 token 调试——
 * 不必再用 curl 手工拼 header。对"演示项目"来说，这个体验差异很直观。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI mallOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("高并发交易系统 API")
                        .version("1.0.0")
                        .description("""
                                秒杀 / 库存一致性 / 可靠消息 / 普通交易。

                                两条下单链路刻意共存：
                                · `/api/seckill/**` —— Redis 预扣 + MQ 削峰，返回"排队中"，需轮询结果
                                · `/api/trade/**`  —— 同步事务扣减，立即返回订单

                                认证：先调用 `/api/auth/login` 拿到 token，点右上角 Authorize 填入即可。
                                """)
                        .contact(new Contact().name("adfddda"))
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Authorization: Bearer <token>")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
