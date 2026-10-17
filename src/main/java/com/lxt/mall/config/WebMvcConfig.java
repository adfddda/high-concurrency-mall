package com.lxt.mall.config;

import com.lxt.mall.auth.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册登录拦截器。
 *
 * <p>采用「默认全拦截 + 白名单放行」而不是「逐个接口加注解」：
 * 新增接口时如果忘了加鉴权注解，默认行为是<b>被拦截</b>（安全），
 * 而反过来则是默认<b>裸奔</b>（危险）。安全设计上一定要让默认值站在安全的一侧。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 认证入口
                        "/api/auth/register",
                        "/api/auth/login",
                        // 商品浏览：读接口，允许游客访问（也方便压测时不带 token 打缓存）
                        "/api/product/list",
                        "/api/product/detail/**",
                        // 秒杀活动浏览：活动列表与详情是公开的
                        "/api/seckill/activities",
                        "/api/seckill/activities/running",
                        // 健康检查与监控
                        "/actuator/**",
                        "/error"
                );
    }
}
