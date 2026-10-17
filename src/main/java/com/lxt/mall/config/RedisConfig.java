package com.lxt.mall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Redis 配置。
 *
 * <p><b>为什么不定义 RedisTemplate&lt;String, Object&gt;？</b>
 * 本项目所有 Redis 读写统一使用 {@code StringRedisTemplate} + {@code JsonUtils}：
 * <ul>
 *   <li>{@code GenericJackson2JsonRedisSerializer} 会往 JSON 里写 {@code @class}
 *       全限定类名，一旦类改名/挪包，历史缓存全部反序列化失败；
 *       而且它把安全边界交给了"反序列化任意类"，是个真实的攻击面。</li>
 *   <li>JDK 序列化（默认 JdkSerializationRedisSerializer）写出来的是二进制，
 *       {@code redis-cli get} 全是乱码，线上排查基本靠猜。</li>
 *   <li>自己控制成 UTF-8 JSON 后，缓存里存了什么一目了然，
 *       序列化行为也完全由 {@code JsonUtils} 一处决定，不会出现
 *       "写进去能读出来、改天换个序列化器就读不出来"的惨案。</li>
 * </ul>
 *
 * <p>Lua 脚本用 {@link DefaultRedisScript} 在【启动时】就把脚本内容读进来：
 * 脚本文件写错路径会直接启动失败，而不是等到线上第一次调用才报错。
 * 同时 Spring Data Redis 会缓存 sha1、优先走 EVALSHA，
 * 避免每次调用都把整个脚本内容推到 Redis（大脚本时这个开销很可观）。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> seckillDeductScript() {
        return loadLongScript("lua/seckill_deduct.lua");
    }

    @Bean
    public RedisScript<Long> seckillRollbackScript() {
        return loadLongScript("lua/seckill_rollback.lua");
    }

    @Bean
    public RedisScript<Long> seckillInflightDecScript() {
        return loadLongScript("lua/seckill_inflight_dec.lua");
    }

    @Bean
    public RedisScript<Long> slidingWindowScript() {
        return loadLongScript("lua/sliding_window_rate_limit.lua");
    }

    @Bean
    public RedisScript<Long> tokenBucketScript() {
        return loadLongScript("lua/token_bucket_rate_limit.lua");
    }

    /**
     * 可注入的时钟。
     *
     * <p>把"取当前时间"变成依赖注入，测试里就能换成固定时钟，
     * 从而对"活动未开始 / 已结束 / 订单超时"这类时间相关分支做确定性验证，
     * 不必靠 {@code Thread.sleep} 去赌时序。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    private RedisScript<Long> loadLongScript(String classpathLocation) {
        try {
            String text = StreamUtils.copyToString(
                    new ClassPathResource(classpathLocation).getInputStream(), StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(text, Long.class);
        } catch (IOException e) {
            throw new IllegalStateException("加载 Lua 脚本失败: " + classpathLocation, e);
        }
    }
}
