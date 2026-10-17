package com.lxt.mall.cache;

import com.lxt.mall.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品布隆过滤器。
 *
 * <p>缓存穿透的完整防线是「三层」，缺一不可：
 * <ol>
 *   <li><b>布隆过滤器</b>：拦截"一定不存在"的 ID。特点是内存极小
 *       （1 亿个元素、1% 误判率只需约 114MB，用 Redis Bitmap 实现），
 *       但代价是<b>只会误判"存在"，不会误判"不存在"</b>——
 *       所以它拦下来的一定是攻击流量，放过去的仍需后续校验。</li>
 *   <li><b>空值缓存</b>：对"确实不存在"的 ID 也缓存一个占位符（短 TTL）。
 *       防的是<b>布隆过滤器放过去的误判流量</b>，以及商品删除后的重复查询。</li>
 *   <li><b>参数校验</b>：ID &lt;= 0、超出范围直接拒绝。最低成本的一层，
 *       却经常被忽略——查 id=-1 的请求本来就不该进入缓存逻辑。</li>
 * </ol>
 *
 * <p><b>为什么用 Redis 布隆过滤器而不是 Guava 的本地版本？</b>
 * 本地布隆过滤器在每个实例里各存一份，多实例下需要同步，而且重启就丢失；
 * 放在 Redis 里所有实例共享同一份判断，代价只是每次判断多一次网络往返。
 * 在本项目里这个往返被 L1 本地缓存挡住了，实际影响可忽略。
 *
 * <p><b>局限：布隆过滤器不支持删除</b>。商品下架后 ID 仍在过滤器里，
 * 后续查询会穿透到第 2 层（空值缓存）兜住——这正是为什么两层都要有。
 * 如果要支持删除，应换成 Counting Bloom Filter 或 Cuckoo Filter。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class ProductBloomFilter implements ApplicationRunner {

    private final RedissonClient redisson;
    private final com.lxt.mall.domain.mapper.ProductMapper productMapper;
    private final com.lxt.mall.config.MallProperties properties;

    private volatile RBloomFilter<Long> bloomFilter;
    private volatile boolean ready = false;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getCache().isBloomEnabled()) {
            log.warn("布隆过滤器已关闭，缓存穿透防护仅剩空值缓存一层");
            return;
        }
        try {
            RBloomFilter<Long> filter = redisson.getBloomFilter(RedisKeys.PRODUCT_BLOOM);
            // tryInit 是幂等的：Redis 里已存在则返回 false，不会清空已有数据。
            // 这一点很重要——多实例同时启动时不能互相把过滤器重置掉。
            boolean initialized = filter.tryInit(
                    properties.getCache().getBloomExpectedInsertions(),
                    properties.getCache().getBloomFpp());

            if (initialized) {
                List<Long> ids = productMapper.selectList(
                                com.baomidou.mybatisplus.core.toolkit.Wrappers
                                        .<com.lxt.mall.domain.entity.Product>lambdaQuery()
                                        .select(com.lxt.mall.domain.entity.Product::getId))
                        .stream().map(com.lxt.mall.domain.entity.Product::getId).toList();
                ids.forEach(filter::add);
                log.info("布隆过滤器已初始化，预置 {} 个商品ID，误判率={}",
                        ids.size(), properties.getCache().getBloomFpp());
            } else {
                log.info("布隆过滤器已存在，复用 Redis 中的既有数据");
            }
            this.bloomFilter = filter;
            this.ready = true;
        } catch (Exception e) {
            // 布隆过滤器不可用不应该阻塞启动：它只是一层优化，
            // 空值缓存仍然能兜住穿透。降级而不是 fail-fast 是有意为之。
            log.error("布隆过滤器初始化失败，已降级（穿透防护仅靠空值缓存）", e);
        }
    }

    /**
     * @return true 表示"可能存在"，需要继续查；false 表示"一定不存在"，可直接返回
     */
    public boolean mightContain(Long productId) {
        if (!ready || productId == null) {
            // 未就绪时一律返回 true（放行），保证可用性优先
            return true;
        }
        try {
            return bloomFilter.contains(productId);
        } catch (Exception e) {
            log.warn("布隆过滤器查询失败，降级放行 productId={}", productId, e);
            return true;
        }
    }

    public void add(Long productId) {
        if (!ready || productId == null) {
            return;
        }
        try {
            bloomFilter.add(productId);
        } catch (Exception e) {
            log.warn("布隆过滤器写入失败 productId={}", productId, e);
        }
    }
}
