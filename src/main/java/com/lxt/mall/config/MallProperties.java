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
    private Admin admin = new Admin();
    private Search search = new Search();
    private Risk risk = new Risk();

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

        /**
         * 库存分桶数。1 = 不分桶（默认，行为与早期版本完全一致）。
         *
         * <p><b>什么时候需要它</b>：单个秒杀活动的库存是一个热点 key。
         * Redis Cluster 下这个 key 只落在一个分片上，该分片的单线程能力
         * 就是整个活动的吞吐上限——加机器、加分片都救不了它。
         * 把库存拆成 N 个桶、按 userId 哈希路由，桶就能分散到不同分片。
         *
         * <p><b>代价（必须说清楚）</b>：
         * <ol>
         *   <li>同一个用户永远路由到同一个桶——这是"一人一单"和"资格码"
         *       能在桶内原子判定的前提，但也意味着<b>桶间负载由哈希决定，不可控</b>；</li>
         *   <li>不做跨桶回退：用户所在桶卖完就直接返回售罄，即使别的桶还有货。
         *       这会<b>少卖</b>，但绝不会超卖，对账任务可以兜底；</li>
         *   <li>单机 Redis 上分桶<b>没有吞吐收益</b>（仍然是一个线程），
         *       收益只在 Cluster 下出现。</li>
         * </ol>
         */
        private int bucketCount = 1;

        /**
         * 本地库存分段（实验特性，默认关闭）。
         *
         * <p>开启后实例从 Redis 一次性领走 {@code segmentSize} 件库存，
         * 后续请求在本地内存扣减，把"每请求一次 Redis"降到"每 N 请求一次"。
         *
         * <p><b>代价</b>：本地去重换吞吐——同一用户打到不同实例时会退化成
         * 数据库唯一索引兜底，产生少量"到落库才失败"的请求。
         * 这是吞吐与失败率之间的显式取舍，不适合默认开启。
         */
        private boolean segmentEnabled = false;

        /** 每次领取的段大小。 */
        private int segmentSize = 50;

        /**
         * 段登记的 TTL。
         *
         * <p>它是<b>崩溃回收的时钟</b>：实例崩溃后不再续期，
         * TTL 到期即视为"这批库存没人管了"，由对账补回。
         * 所以它必须明显大于一次压测或一次正常请求的处理时间。
         */
        private Duration segmentTtl = Duration.ofSeconds(60);
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

    /**
     * 运维接口的访问控制。
     *
     * <p>原实现里 {@code /api/admin/**} 只校验了"是否登录"，没有校验"是不是管理员"——
     * 任何一个注册用户都能调用 {@code /api/admin/seckill/reconcile} 触发对账修复，
     * 或者调用预热接口改写 Redis 库存。这是一个真实的越权漏洞。
     *
     * <p>演示项目用「配置白名单」而不是完整的 RBAC：
     * 引入 t_role / t_permission 三张表，对一个没有任何运营后台的项目是过度设计。
     * 真实系统应该走统一权限中心（角色 → 权限点 → 接口），并且管理员身份
     * 不能只体现在用户名上，还要有独立的认证策略（如 U2F / 内网限制）。
     */
    /**
     * 商品搜索（Elasticsearch）。
     *
     * <p>{@code enabled=false} 时整条 ES 链路关闭，搜索直接走数据库。
     * 这不是"没配好"的兜底，而是刻意的设计：搜索是<b>可选能力</b>，
     * 本地开发、CI 的部分场景都不需要它。
     */
    /**
     * 秒杀风控。
     *
     * <p>限流解决"量太大"，风控解决"人不正常"：
     * 一个人开 100 个账号抢购，每个账号都合法，限流拦不住他，
     * 只能靠<b>设备维度</b>收敛。这是秒杀最容易被忽略的一层。
     */
    @Data
    public static class Risk {
        private boolean enabled = true;
        /** 同一设备在同一活动内的最多购买次数。 */
        private int deviceLimitPerActivity = 1;
        /** 设备指纹最大长度（防止被当作超大 key 写入 Redis）。 */
        private int deviceIdMaxLength = 64;
    }

    @Data
    public static class Search {
        private boolean enabled = false;
        private String endpoint = "http://localhost:9200";
        private String index = "mall_product";
        /** 启动时是否全量重建索引。 */
        private boolean reindexOnStartup = true;
    }

    @Data
    public static class Admin {
        /** 允许访问 /api/admin/** 的用户名。生产环境务必通过配置覆盖。 */
        private List<String> usernames = new ArrayList<>(List.of("lxt"));
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
