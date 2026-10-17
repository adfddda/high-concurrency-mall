package com.lxt.mall.config;

import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Redisson 配置（分布式锁 + 布隆过滤器）。
 *
 * <p>职责划分很关键：
 * <ul>
 *   <li><b>Lettuce（spring-boot-starter-data-redis）</b>：业务数据的读写，
 *       走 {@code StringRedisTemplate}，同步、轻量、无额外对象模型。</li>
 *   <li><b>Redisson</b>：只用来做分布式锁、布隆过滤器这类"需要客户端协议支持"
 *       的能力，不接管业务数据读写。</li>
 * </ul>
 * 两套客户端连同一个 Redis，各自用最擅长的场景，避免"用一个客户端硬扛所有事"。
 *
 * <p><b>为什么不用 redisson-spring-boot-starter？</b>
 * starter 会通过 {@code redisson-spring-data-xx} 反向接管 Spring Data Redis 的
 * 自动装配。Spring Boot 3.5 的 spring-data-redis 是 3.5.x，而 starter 内置的
 * redisson-spring-data 模块号是按 Spring Data 大版本对齐的，两者一旦错位，
 * 典型症状是启动期 {@code NoSuchMethodError} 或者 {@code RedisConnectionFactory}
 * 被静默替换、序列化行为突变。手工装配只有 30 行，但故障面完全可控。
 */
@Configuration
@RequiredArgsConstructor
public class RedissonConfig {

    private final MallProperties mallProperties;
    private final RedisProperties redisProperties;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        MallProperties.Redisson rc = mallProperties.getRedisson();
        Config config = new Config();

        if ("cluster".equalsIgnoreCase(rc.getMode())) {
            List<String> nodes = rc.getClusterNodes();
            if (nodes == null || nodes.isEmpty()) {
                throw new IllegalStateException("mall.redisson.mode=cluster 时必须配置 mall.redisson.cluster-nodes");
            }
            ClusterServersConfig cluster = config.useClusterServers()
                    .addNodeAddress(nodes.toArray(new String[0]))
                    // 集群拓扑刷新：节点扩缩容后客户端要能自动感知，
                    // 否则会一直往已经下线的节点发命令。
                    .setScanInterval(2000)
                    .setConnectTimeout(3000)
                    .setTimeout(2000)
                    .setRetryAttempts(3)
                    .setRetryInterval(500);
            if (StringUtils.hasText(redisProperties.getPassword())) {
                cluster.setPassword(redisProperties.getPassword());
            }
        } else {
            SingleServerConfig single = config.useSingleServer()
                    .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                    .setDatabase(redisProperties.getDatabase())
                    .setConnectTimeout(3000)
                    .setTimeout(2000)
                    // 锁场景下重试要克制：重试次数过多会把一次加锁拖成几百毫秒，
                    // 反而不如快速失败让上层走降级逻辑。
                    .setRetryAttempts(3)
                    .setRetryInterval(500)
                    .setConnectionPoolSize(rc.getPoolSize())
                    .setConnectionMinimumIdleSize(rc.getMinimumIdleSize());
            if (StringUtils.hasText(redisProperties.getPassword())) {
                single.setPassword(redisProperties.getPassword());
            }
        }

        // 不显式设置 codec，使用 Redisson 默认实现：
        // 换 codec 会影响 RBloomFilter 内部状态的编码方式，
        // 而默认路径是社区使用最广、测试最充分的组合。
        return Redisson.create(config);
    }
}
