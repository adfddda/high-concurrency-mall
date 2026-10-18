package com.lxt.mall.seckill;

import com.lxt.mall.config.MallProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地库存段管理器（实验特性，默认关闭）。
 *
 * <h3>它要解决什么</h3>
 * 秒杀的每个请求都要打一次 Redis 扣减。单机 Redis 是单线程的，
 * <b>每个命令都是一次串行开销</b>——这决定了单活动吞吐的上限。
 *
 * <p>库存分桶只在 Cluster 下有效（单机仍然一个线程）；分段是另一条路：
 * 实例先从 Redis <b>一次性领走 N 件</b>，后续请求在本地内存扣减，
 * 段用完再领。把"每请求一次 Redis"降到"每 N 请求一次"。
 *
 * <h3>它带来的三个新问题（也是这个类要回答的）</h3>
 * <ol>
 *   <li><b>领多了会不会超卖</b>：领段是原子的（Lua），且只从真实库存里扣，
 *       所以"已领 + 在库"恒等于原始库存，不会凭空多出来；</li>
 *   <li><b>段丢了怎么办</b>：领走的段登记在 Redis hash 里并带 TTL。
 *       实例崩溃 → 不再续期 → TTL 到期 → 对账把这段算作"未归还"补回库存；</li>
 *   <li><b>本地扣了但下单失败</b>：{@link #release} 把名额还回<b>本地段</b>，
 *       而不是 Redis——扣的是本地段，还错地方会造成重复计数。</li>
 * </ol>
 *
 * <h3>为什么默认关闭</h3>
 * 分段用"本地去重"换吞吐：同一用户打到不同实例时，去重会退化成数据库唯一索引兜底，
 * 产生少量"到落库才失败"的请求（需要补偿）。这是<b>吞吐与失败率之间的显式取舍</b>。
 */
public class SegmentStockManager {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> claimScript;
    private final MallProperties properties;
    private final String instanceId;

    /** 每个活动当前本地剩余的段库存。 */
    private final ConcurrentHashMap<Long, AtomicInteger> local = new ConcurrentHashMap<>();

    /** 每个活动一把锁，避免并发领段时重复申请。 */
    private final ConcurrentHashMap<Long, Object> claimLocks = new ConcurrentHashMap<>();

    public SegmentStockManager(StringRedisTemplate redis, RedisScript<Long> claimScript,
                               MallProperties properties) {
        this.redis = redis;
        this.claimScript = claimScript;
        this.properties = properties;
        // 实例 ID 参与 Redis 上的段登记：崩溃回收靠它识别"哪个实例的段没人管了"
        this.instanceId = "inst-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * 尝试从本地段取一个名额，段空则回源到 Redis 领一段。
     *
     * @return true 表示成功占用一个库存名额；false 表示库存确实取不到
     */
    public boolean tryAcquire(long activityId) {
        AtomicInteger counter = local.computeIfAbsent(activityId, k -> new AtomicInteger(0));
        for (; ; ) {
            if (counter.getAndUpdate(v -> v > 0 ? v - 1 : v) > 0) {
                return true;
            }
            if (claimOnce(activityId, counter) == 0) {
                return false;
            }
        }
    }

    /**
     * 归还一个名额到本地段（下单失败 / 取消订单时调用）。
     *
     * <p>只还可能被还给本地：如果这段库存其实是从 Redis 领来的，
     * 还到 Redis 会让"本地段"和"Redis 库存"各记一份。
     */
    public void release(long activityId) {
        local.computeIfAbsent(activityId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** 当前本地段剩余（监控与测试用）。 */
    public int unspent(long activityId) {
        AtomicInteger counter = local.get(activityId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 该活动所有实例<b>已领取</b>的总量。
     *
     * <p>注意语义：这是"领走了多少"，不是"还剩多少"。每个实例卖出多少只有它自己知道，
     * 要靠心跳上报（下一步实现）。在那之前对账只能把它当"已领取"算上界——
     * <b>名字必须诚实，否则下一个人会拿它当"未售出"用。</b>
     */
    public long totalGrabbedAcrossInstances(long activityId) {
        return registered(activityId).values().stream().mapToLong(Long::parseLong).sum();
    }

    /** 当前实例在 Redis 上登记的已领取量。 */
    public long registered(long activityId, String instance) {
        Map<String, String> all = registered(activityId);
        return Long.parseLong(all.getOrDefault(instance, "0"));
    }

    private Map<String, String> registered(long activityId) {
        Map<Object, Object> raw = redis.opsForHash().entries(segmentKey(activityId));
        Map<String, String> result = new java.util.HashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    /** 领一段：返回本次领到的数量（0 表示无库存可领）。 */
    private int claimOnce(long activityId, AtomicInteger counter) {
        Object lock = claimLocks.computeIfAbsent(activityId, k -> new Object());
        synchronized (lock) {
            // 双重检查：可能别的线程已经领过了
            if (counter.get() > 0) {
                return counter.get();
            }
            int want = properties.getSeckill().getSegmentSize();
            Long claimed = redis.execute(claimScript,
                    List.of(stockKey(activityId), segmentKey(activityId)),
                    instanceId,
                    String.valueOf(want),
                    String.valueOf(properties.getSeckill().getSegmentTtl().toMillis()));
            if (claimed == null || claimed <= 0) {
                return 0;
            }
            counter.addAndGet(claimed.intValue());
            return claimed.intValue();
        }
    }

    private String stockKey(long activityId) {
        return "seckill:stock:{act:" + activityId + "}";
    }

    private String segmentKey(long activityId) {
        return "seckill:segment:{act:" + activityId + "}";
    }
}
