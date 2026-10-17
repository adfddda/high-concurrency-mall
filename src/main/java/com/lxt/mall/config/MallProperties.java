package com.lxt.mall.config;

import com.lxt.mall.common.enums.RateLimitAlgorithm;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务配置。
 *
 * <p>所有可调参数集中在这里，而不是散落成 {@code @Value("${...}")}：
 * 一是能 IDE 跳转、能补全；二是参数之间的约束关系可以写在注释里；
 * 三是测试时可以直接 new 一个对象改字段，不用起 Spring 上下文。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall")
public class MallProperties {

    private Jwt jwt = new Jwt();
    private Auth auth = new Auth();
    private RateLimit rateLimit = new RateLimit();
    private Seckill seckill = new Seckill();
    private Cache cache = new Cache();
    private Mq mq = new Mq();
    private Reconcile reconcile = new Reconcile();
    private Redisson redisson = new Redisson();
    private Demo demo = new Demo();

    // ========================================================================

    @Data
    public static class Jwt {
        private String issuer = "high-concurrency-mall";
        private String secret;
        /** access token 有效期。 */
        private Duration accessTokenTtl = Duration.ofHours(2);
        /** 允许的时钟偏移，避免多实例机器时间差导致 token 被误判过期。 */
        private Duration clockSkew = Duration.ofSeconds(30);
    }

    @Data
    public static class Auth {
        private boolean blacklistEnabled = true;
        private String blacklistKeyPrefix = "auth:token:blacklist:";
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        /**
         * Redis 故障时是放行还是拒绝。
         *
         * <p>默认放行（fail-open）。限流是"保护措施"，保护措施自身挂掉时
         * 不应该把正常业务一起拒绝——那等于自己制造了一次全站故障。
         * 代价是故障期间失去限流保护，所以必须配合告警。
         * 如果接口本身极其脆弱（比如会打挂 DB 的慢查询），可以按接口改成 fail-close。
         */
        private boolean failOpen = true;
        private Map<String, Rule> rules = new LinkedHashMap<>();
    }

    @Data
    public static class Rule {
        /** 窗口长度（滑动窗口）/ 仅用于日志（令牌桶用 capacity+rate 描述）。 */
        private Duration window = Duration.ofSeconds(1);
        /** 滑动窗口：窗口内允许的请求数。 */
        private int limit = 100;
        private RateLimitAlgorithm algorithm = RateLimitAlgorithm.SLIDING_WINDOW;
        /** 令牌桶容量（突发上限）。 */
        private Integer capacity;
        /** 令牌桶每秒补充速率。 */
        private Double rate;
    }

    @Data
    public static class Seckill {
        /** 活动元信息在 JVM 内的缓存时间，避免每次抢购都读一次 Redis。 */
        private Duration activityLocalTtl = Duration.ofSeconds(5);
        private long activityLocalMaxSize = 1000;
        /** 已购用户集合的 TTL，必须覆盖整个活动周期，否则资格校验会失效。 */
        private Duration boughtSetTtl = Duration.ofHours(24);
        /** 订单支付超时时间。演示用 30s，生产通常 15m。 */
        private Duration orderExpire = Duration.ofSeconds(30);
        /** 本地售罄标记容量。 */
        private long localSoldoutMaxSize = 10_000;
        /** 本地售罄标记 TTL。多实例下它不会实时同步，靠 TTL 收敛。 */
        private Duration localSoldoutTtl = Duration.ofSeconds(10);
        /** 是否维护在途计数（对账时需要）。 */
        private boolean inflightTracking = true;
    }

    @Data
    public static class Cache {
        private ProductCache product = new ProductCache();
        /** 更新 DB 后延迟二次删除缓存的时间。 */
        private Duration doubleDeleteDelay = Duration.ofMillis(500);
        private boolean bloomEnabled = true;
        private long bloomExpectedInsertions = 100_000;
        private double bloomFpp = 0.01;
    }

    @Data
    public static class ProductCache {
        private Duration l1Ttl = Duration.ofSeconds(3);
        private long l1MaxSize = 10_000;
        /** 逻辑过期时间：到点后数据仍可读，只是触发异步重建。 */
        private Duration l2LogicalExpire = Duration.ofMinutes(3);
        /** 物理 TTL：必须显著大于逻辑过期时间。 */
        private Duration l2PhysicalTtl = Duration.ofMinutes(30);
        private double l2TtlJitterRatio = 0.2;
        private Duration nullValueTtl = Duration.ofSeconds(60);
        private Duration rebuildLockWait = Duration.ofMillis(200);
        private Duration rebuildLockLease = Duration.ofSeconds(10);
        private int rebuildPoolSize = 4;
        private int rebuildQueueCapacity = 2048;
    }

    @Data
    public static class Mq {
        private String seckillExchange = "mall.seckill.exchange";
        private String seckillQueue = "mall.seckill.order.queue";
        private String seckillRoutingKey = "seckill.order";
        private String seckillRetryQueue = "mall.seckill.order.retry.queue";
        private String seckillRetryRoutingKey = "seckill.order.retry";
        private String dlxExchange = "mall.dlx.exchange";
        private String seckillDlq = "mall.seckill.order.dlq";
        private String seckillDlqRoutingKey = "seckill.order.dlq";

        private String orderDelayExchange = "mall.order.delay.exchange";
        private String orderDelayQueue = "mall.order.delay.queue";
        private String orderDelayRoutingKey = "order.delay";
        private String orderCloseExchange = "mall.order.close.exchange";
        private String orderCloseQueue = "mall.order.close.queue";
        private String orderCloseRoutingKey = "order.close";

        private int maxRetry = 3;
        private Duration retryDelay = Duration.ofSeconds(5);
        private Duration confirmTimeout = Duration.ofSeconds(5);
    }

    @Data
    public static class Reconcile {
        private boolean enabled = true;
        private String cron = "0 */5 * * * ?";
        private int driftTolerance = 0;
        /**
         * 是否自动修复漂移。
         *
         * <p>演示环境开着，方便观察自愈；生产环境建议先只告警再人工确认——
         * 自动修复的前提是"对账逻辑本身是对的"，而对账逻辑一旦有 bug，
         * 自动修复会把错误数据放大成事故。
         */
        private boolean autoRepair = true;
    }

    @Data
    public static class Redisson {
        /** single / cluster */
        private String mode = "single";
        private List<String> clusterNodes = new ArrayList<>();
        private int poolSize = 64;
        private int minimumIdleSize = 8;
    }

    /**
     * 演示数据初始化。
     *
     * <p>生产环境必须 {@code seed-enabled=false}：否则会在启动时静默写入
     * 一批已知密码的账号，等于开了一个后门。
     */
    @Data
    public static class Demo {
        private boolean seedEnabled = false;
        /** 批量造的用户数量，供压测使用（一人一单必须有足够多的不同用户）。 */
        private int userCount = 200;
        private String usernamePrefix = "malluser";
        private String nicknamePrefix = "演示用户";
        private String password = "123456";
    }
}
