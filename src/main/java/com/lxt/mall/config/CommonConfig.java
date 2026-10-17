package com.lxt.mall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 通用 Bean。
 */
@Configuration
public class CommonConfig {

    /**
     * 密码编码器。
     *
     * <p>选 BCrypt 而不是 MD5/SHA256 加盐，核心差异在于<b>计算成本</b>：
     * BCrypt 的 cost 参数可以指数级抬高单次哈希的耗时（默认 10 ≈ 50~100ms），
     * 让离线暴力破解的代价高到不可接受。MD5 哪怕加了 salt，
     * 单次计算也是微秒级，GPU 集群下几亿次/秒。
     *
     * <p>代价是登录接口本身变慢（每次 50~100ms），
     * 所以登录接口一定要限流，否则这个"慢"会变成被利用的放大攻击面。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
