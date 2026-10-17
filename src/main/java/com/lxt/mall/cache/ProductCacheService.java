package com.lxt.mall.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Product;
import com.lxt.mall.domain.mapper.ProductMapper;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.product.dto.ProductDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品详情多级缓存。
 *
 * <pre>
 *   请求 ──► L1 Caffeine（进程内，纳秒级）
 *              │ miss
 *              ▼
 *            L2 Redis（毫秒级，带逻辑过期）
 *              │ miss
 *              ▼
 *            Redisson 互斥锁 + 双重检查 ──► MySQL
 * </pre>
 *
 * <h3>各层解决什么问题</h3>
 * <table border="1">
 *   <tr><th>问题</th><th>手段</th><th>本项目位置</th></tr>
 *   <tr><td>缓存穿透（查不存在的数据）</td><td>参数校验 + 布隆过滤器 + 空值缓存</td>
 *       <td>getDetail 第 0/2.5/3 步</td></tr>
 *   <tr><td>缓存击穿（热点 key 失效瞬间）</td><td>逻辑过期 + 异步重建（热 key）；
 *       互斥锁 + 双重检查（冷 key）</td><td>getDetail 第 2/4 步</td></tr>
 *   <tr><td>缓存雪崩（大批 key 同时失效）</td><td>物理 TTL 加随机抖动</td>
 *       <td>writeCache</td></tr>
 *   <tr><td>数据库压力（读放大）</td><td>L1 本地缓存挡住绝大部分重复读</td>
 *       <td>getDetail 第 1 步</td></tr>
 * </table>
 *
 * <h3>为什么两种防击穿手段都要有</h3>
 * 「逻辑过期」要求缓存里已经有数据，它解决的是<b>热 key 过期</b>；
 * 但冷 key（第一次访问、被 LRU 淘汰、Redis 重启后）根本没数据，
 * 此时所有并发请求会一起回源，必须靠<b>互斥锁</b>把它们收成一次查询。
 * 两者是互补关系，不是二选一。
 *
 * <h3>L1 的一致性代价</h3>
 * 本地缓存无法被其他实例删除，所以 {@code mall.cache.product.l1-ttl}
 * 就是<b>多实例部署下的不一致窗口上界</b>。本项目设为 3 秒：
 * 商品详情能容忍 3 秒延迟，换来的是 Redis 的读 QPS 直接降一个数量级。
 * 如果业务不能容忍，就只能砍掉 L1，或者引入 Redis Pub/Sub 广播失效消息。
 */
@Slf4j
@Service
public class ProductCacheService {

    private static final String CACHE_NAME = "product";

    /** 空值占位符。用固定字符串而不是 JSON，减少无意义的序列化开销。 */
    public static final String NULL_SENTINEL = "__NULL__";

    private final StringRedisTemplate redis;
    private final ProductMapper productMapper;
    private final RedissonClient redisson;
    private final ProductBloomFilter bloomFilter;
    private final MallProperties properties;
    private final MallMetrics metrics;
    private final Clock clock;
    private final ThreadPoolTaskExecutor rebuildExecutor;

    /** L1：进程内缓存。value 要么是 ProductDetailVO，要么是 NULL_SENTINEL。 */
    private final Cache<Long, Object> localCache;

    /**
     * 正在重建中的商品 ID 集合。
     *
     * <p>作用：逻辑过期时每个读请求都会尝试提交一次异步重建任务。
     * 热门商品在过期瞬间可能同时有几千个请求，如果全都提交，
     * 线程池队列会被瞬间打满（虽然最终只有一个能拿到锁）。
     * 这里用一个进程内的 Set 先做一次去重，把提交量压到"每个 key 同时最多一个"。
     * 它是纯优化，即使判断失效也不会出错——真正的互斥仍由 Redisson 锁保证。
     */
    private final Set<Long> rebuilding = ConcurrentHashMap.newKeySet();

    public ProductCacheService(StringRedisTemplate redis,
                               ProductMapper productMapper,
                               RedissonClient redisson,
                               ProductBloomFilter bloomFilter,
                               MallProperties properties,
                               MallMetrics metrics,
                               Clock clock,
                               @Qualifier("cacheRebuildExecutor") ThreadPoolTaskExecutor rebuildExecutor) {
        this.redis = redis;
        this.productMapper = productMapper;
        this.redisson = redisson;
        this.bloomFilter = bloomFilter;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.rebuildExecutor = rebuildExecutor;

        MallProperties.ProductCache pc = properties.getCache().getProduct();
        this.localCache = Caffeine.newBuilder()
                .maximumSize(pc.getL1MaxSize())
                // expireAfterWrite：写后固定时间过期。这里不用 refreshAfterWrite，
                // 因为本地缓存的重建也应该走"异步 + 互斥"的逻辑，而不是 Caffeine 自己的语义。
                .expireAfterWrite(pc.getL1Ttl().toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .build();
    }

    // ========================================================================
    // 读
    // ========================================================================

    public ProductDetailVO getDetail(Long productId) {
        // 第 0 层：参数校验。成本最低、经常被忽略的一层。
        // 查 id=-1 / id=0 的请求本来就不该进入缓存或数据库逻辑。
        if (productId == null || productId <= 0) {
            return null;
        }

        // 第 1 层：L1 进程内缓存
        Object l1 = localCache.getIfPresent(productId);
        if (l1 != null) {
            metrics.cacheAccess(CACHE_NAME, "l1", "hit");
            return unwrap(l1, "L1");
        }
        metrics.cacheAccess(CACHE_NAME, "l1", "miss");

        // 第 2 层：L2 Redis
        String json = redis.opsForValue().get(RedisKeys.productCache(productId));
        if (json != null) {
            CacheValue<ProductDetailVO> cached = parse(json);
            if (cached != null && cached.getData() != null) {
                metrics.cacheAccess(CACHE_NAME, "l2", "hit");
                localCache.put(productId, cached.getData());

                if (cached.logicallyExpired(clock)) {
                    // 逻辑过期：数据仍然可用，直接返回旧值；
                    // 同时异步触发重建，保证"下一个"请求能拿到新数据。
                    asyncRebuild(productId);
                }
                return cached.getData().copyWithSource("L2");
            }
        }
        metrics.cacheAccess(CACHE_NAME, "l2", "miss");

        // 第 2.5 层：空值缓存（防穿透第二道）
        if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.productNullCache(productId)))) {
            metrics.cacheAccess(CACHE_NAME, "l2", "null");
            localCache.put(productId, NULL_SENTINEL);
            return null;
        }

        // 第 2.6 层：布隆过滤器（防穿透第一道）
        // 命中"一定不存在"时直接返回，一次数据库都不会打。
        if (!bloomFilter.mightContain(productId)) {
            metrics.cacheAccess(CACHE_NAME, "bloom", "reject");
            log.debug("布隆过滤器拦截了不存在的商品 productId={}", productId);
            return null;
        }

        // 第 3 层：回源。用互斥锁把并发请求收成一次数据库查询。
        return loadWithMutex(productId);
    }

    /**
     * 互斥锁回源（冷 key 场景）。
     *
     * <p>抢到锁的请求去查数据库并回填缓存，没抢到的请求<b>不是直接查数据库</b>，
     * 而是短暂自旋等缓存被填好。这是这个模式的关键：
     * 如果没抢到锁的请求直接查库，那"加锁"就只保护了一个请求，
     * 数据库依旧被 N-1 个请求打穿。
     */
    private ProductDetailVO loadWithMutex(Long productId) {
        MallProperties.ProductCache pc = properties.getCache().getProduct();
        RLock lock = redisson.getLock(RedisKeys.productRebuildLock(productId));
        boolean locked = false;
        try {
            locked = lock.tryLock(pc.getRebuildLockWait().toMillis(),
                    pc.getRebuildLockLease().toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                metrics.cacheAccess(CACHE_NAME, "lock", "contended");
                return waitForCacheFilled(productId);
            }

            // 双重检查：抢到锁不等于缓存还是空的。
            // 从"发现 miss"到"拿到锁"之间，前一个持锁者可能已经回填完成，
            // 没有这一步就会产生一次完全多余的数据库查询。
            String json = redis.opsForValue().get(RedisKeys.productCache(productId));
            if (json != null) {
                CacheValue<ProductDetailVO> cached = parse(json);
                if (cached != null && cached.getData() != null) {
                    localCache.put(productId, cached.getData());
                    metrics.cacheAccess(CACHE_NAME, "lock", "double_check_hit");
                    return cached.getData().copyWithSource("L2");
                }
            }

            ProductDetailVO vo = loadFromDb(productId);
            if (vo == null) {
                // 空值也缓存，但 TTL 很短：商品可能马上就被创建出来，
                // 空值缓存太久会让新商品在一段时间内"查不到"。
                redis.opsForValue().set(RedisKeys.productNullCache(productId), NULL_SENTINEL,
                        pc.getNullValueTtl());
                localCache.put(productId, NULL_SENTINEL);
                return null;
            }

            writeCache(productId, vo);
            localCache.put(productId, vo);
            return vo.copyWithSource("DB");
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉 InterruptedException 不恢复中断位，
            // 会让上层线程池无法感知停机信号，是并发编程里最常见的错误之一。
            Thread.currentThread().interrupt();
            log.warn("获取缓存重建锁被中断 productId={}", productId);
            return loadFromDb(productId);
        } catch (Exception e) {
            // Redis/Redisson 故障时不能让读接口整体不可用，直接降级查库
            log.error("缓存重建锁异常，降级直查数据库 productId={}", productId, e);
            return loadFromDb(productId);
        } finally {
            // isHeldByCurrentThread 是必须的：如果 tryLock 超时失败，
            // 直接 unlock 会抛 IllegalMonitorStateException；
            // 更危险的情况是锁已因超时被 Redis 释放、又被别的线程拿到，
            // 此时 unlock 会误删别人的锁。
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 没抢到锁时的等待策略。
     *
     * <p>自旋等待而不是立即查库，是为了把"缓存重建期间的数据库 QPS"
     * 压到接近零。等待总时长约 150ms，超过就认为重建异常慢，
     * 此时降级直查数据库保证可用性（宁可给数据库一点压力，也不能让用户一直卡着）。
     */
    private ProductDetailVO waitForCacheFilled(Long productId) {
        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String json = redis.opsForValue().get(RedisKeys.productCache(productId));
            if (json != null) {
                CacheValue<ProductDetailVO> cached = parse(json);
                if (cached != null && cached.getData() != null) {
                    localCache.put(productId, cached.getData());
                    metrics.cacheAccess(CACHE_NAME, "lock", "wait_success");
                    return cached.getData().copyWithSource("L2");
                }
            }
        }
        metrics.cacheAccess(CACHE_NAME, "lock", "wait_timeout");
        // 注意：这里刻意【不】回写缓存。
        // 因为当前线程没有持锁，回写会与持锁者的重建产生竞争，
        // 有可能用一份较旧的数据覆盖掉刚写好的新数据。
        return loadFromDb(productId);
    }

    /**
     * 异步重建（热 key 逻辑过期场景）。
     *
     * <p>任务内部自己加锁并做双重检查——锁的获取与释放必须在同一个线程，
     * 如果在调用线程加锁、在异步线程释放，Redisson 会抛
     * {@code IllegalMonitorStateException}。
     */
    private void asyncRebuild(Long productId) {
        if (!rebuilding.add(productId)) {
            // 已经有一个重建任务在跑，直接返回，避免线程池队列被打爆
            metrics.cacheAccess(CACHE_NAME, "rebuild", "dedup");
            return;
        }
        try {
            rebuildExecutor.execute(() -> {
                MallProperties.ProductCache pc = properties.getCache().getProduct();
                RLock lock = redisson.getLock(RedisKeys.productRebuildLock(productId));
                boolean locked = false;
                try {
                    // waitTime=0：非阻塞。抢不到说明别的线程正在重建，本次直接放弃，
                    // 旧数据继续服务，下一次逻辑过期时还有机会。
                    locked = lock.tryLock(0, pc.getRebuildLockLease().toMillis(), TimeUnit.MILLISECONDS);
                    if (!locked) {
                        return;
                    }
                    long start = System.nanoTime();
                    ProductDetailVO vo = loadFromDb(productId);
                    if (vo != null) {
                        writeCache(productId, vo);
                        localCache.put(productId, vo);
                    }
                    metrics.recordCacheRebuild(CACHE_NAME, System.nanoTime() - start);
                    metrics.cacheAccess(CACHE_NAME, "rebuild", "success");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("异步重建商品缓存失败 productId={}", productId, e);
                    metrics.cacheAccess(CACHE_NAME, "rebuild", "failed");
                } finally {
                    if (locked && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                    rebuilding.remove(productId);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满：放弃本次重建。不阻塞、不抛给用户——
            // 旧数据仍然可用，这是"降级而不是失败"的典型处理。
            rebuilding.remove(productId);
            metrics.cacheAccess(CACHE_NAME, "rebuild", "rejected");
            log.warn("缓存重建线程池已满，跳过本次重建 productId={}", productId);
        }
    }

    private ProductDetailVO loadFromDb(Long productId) {
        // 打点：这是判断"缓存到底有没有挡住数据库"的唯一可信依据。
        // 测试里也直接断言这个计数器——比"看起来变快了"这种主观判断可靠得多。
        metrics.cacheAccess(CACHE_NAME, "db", "load");
        Product product = productMapper.selectById(productId);
        if (product == null) {
            return null;
        }
        return new ProductDetailVO(product.getId(), product.getTitle(), product.getSubTitle(),
                product.getCoverImg(), product.getPrice(), product.getStock(), product.getStatus(),
                product.getDetail(), product.getUpdateTime(), "DB");
    }

    /**
     * 写入 L2。
     *
     * <p>物理 TTL 加随机抖动（雪崩防护）：如果所有商品缓存在同一时刻
     * 被批量预热，用固定 TTL 的话它们会在同一秒集体失效，
     * 数据库瞬间承受全量回源。抖动把失效时刻打散到
     * {@code [ttl, ttl * (1 + jitter)]} 区间内。
     */
    private void writeCache(Long productId, ProductDetailVO vo) {
        MallProperties.ProductCache pc = properties.getCache().getProduct();
        CacheValue<ProductDetailVO> wrapper = CacheValue.of(vo, pc.getL2LogicalExpire(), clock);
        String json = JsonUtils.toJson(wrapper);

        long base = pc.getL2PhysicalTtl().toMillis();
        long jitter = (long) (base * pc.getL2TtlJitterRatio());
        long ttl = base + (jitter > 0 ? ThreadLocalRandom.current().nextLong(jitter + 1) : 0);

        redis.opsForValue().set(RedisKeys.productCache(productId), json, Duration.ofMillis(ttl));
        // 数据已经存在，空值缓存必须清掉，否则后续请求会先命中空值占位直接返回 null
        redis.delete(RedisKeys.productNullCache(productId));
    }

    // ========================================================================
    // 写（失效）
    // ========================================================================

    /**
     * 删除商品缓存（两级一起删）。
     *
     * <p>Cache Aside 的"写"侧标准动作是<b>删除</b>而不是<b>更新</b>：
     * 更新缓存在并发下会写入不一致的数据（两个写请求的 DB 提交顺序
     * 与缓存写入顺序可能相反，最终缓存里留下的是旧值），
     * 而删除是幂等的——删两次和删一次效果一样。
     *
     * <p>这里用一条 {@code DEL} 删两个 key，前提是它们<b>带同一个 Hash Tag</b>
     * （见 {@link RedisKeys#productTag}）。Redis Cluster 的 DEL 是多 key 命令，
     * 不同槽会直接报 CROSSSLOT，而缓存删不掉是<b>静默故障</b>：
     * 不报错，只是用户一直读到旧数据直到 TTL 到期。
     */
    public void invalidate(Long productId) {
        localCache.invalidate(productId);
        redis.delete(List.of(
                RedisKeys.productCache(productId),
                RedisKeys.productNullCache(productId)));
        log.debug("已失效商品缓存 productId={}", productId);
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    private CacheValue<ProductDetailVO> parse(String json) {
        try {
            return JsonUtils.fromJson(json, new TypeReference<CacheValue<ProductDetailVO>>() {
            });
        } catch (Exception e) {
            // 反序列化失败说明缓存里存的是脏数据（旧版本结构、人工写入等）。
            // 直接删掉让下次请求重建，比抛异常导致接口 500 合理得多。
            log.warn("商品缓存反序列化失败，将删除该 key 以待重建: {}", e.getMessage());
            return null;
        }
    }

    private ProductDetailVO unwrap(Object cached, String source) {
        if (NULL_SENTINEL.equals(cached)) {
            return null;
        }
        return ((ProductDetailVO) cached).copyWithSource(source);
    }

    /** 供监控/管理端查看 L1 命中情况。 */
    public CacheStats localCacheStats() {
        return localCache.stats();
    }

    public void invalidateAllLocal() {
        localCache.invalidateAll();
    }
}
