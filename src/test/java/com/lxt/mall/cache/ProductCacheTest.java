package com.lxt.mall.cache;

import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.product.ProductService;
import com.lxt.mall.product.dto.ProductDetailVO;
import com.lxt.mall.product.dto.ProductSaveRequest;
import com.lxt.mall.support.BaseIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多级缓存行为验证。
 *
 * <p>缓存的正确性很难靠"看起来快了"来验证，所以这里的断言都落在
 * <b>可观测的事实</b>上：
 * <ul>
 *   <li>响应体里的 {@code source} 字段（L1 / L2 / DB）</li>
 *   <li>Redis 里 key 的实际存在情况</li>
 *   <li>{@code mall.cache.access} 计数器里"数据库加载"的次数</li>
 * </ul>
 * 尤其是第三个：它能证明"缓存真的挡住了数据库"，而不是靠响应变快来猜。
 */
class ProductCacheTest extends BaseIntegrationTest {

    private static final long EXISTING_PRODUCT = 1001L;
    private static final long NOT_EXIST_PRODUCT = 9_999_999L;

    @Autowired
    private ProductCacheService cacheService;

    @Autowired
    private ProductService productService;

    @Autowired
    private MeterRegistry meterRegistry;

    private double dbLoadCount() {
        Counter counter = meterRegistry.find("mall.cache.access")
                .tags("cache", "product", "level", "db", "result", "load")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("读取链路：第一次回源 DB，第二次命中 L1，清掉 L1 后命中 L2")
    void readPathGoesThroughAllLevels() {
        ProductDetailVO first = cacheService.getDetail(EXISTING_PRODUCT);
        assertThat(first).isNotNull();
        assertThat(first.getSource()).as("首次访问缓存为空，必须回源数据库").isEqualTo("DB");

        ProductDetailVO second = cacheService.getDetail(EXISTING_PRODUCT);
        assertThat(second.getSource()).as("第二次应当命中 L1 本地缓存").isEqualTo("L1");

        // 模拟"另一个实例"或本地缓存过期：只清 L1，保留 Redis
        cacheService.invalidateAllLocal();

        ProductDetailVO third = cacheService.getDetail(EXISTING_PRODUCT);
        assertThat(third.getSource()).as("L1 未命中时应当命中 L2 Redis").isEqualTo("L2");
        assertThat(third.getTitle()).isEqualTo(first.getTitle());
    }

    @Test
    @DisplayName("缓存穿透防护：不存在的商品只查一次库，之后由空值缓存挡住")
    void nullValueCacheStopsPenetration() {
        double before = dbLoadCount();

        ProductDetailVO first = cacheService.getDetail(NOT_EXIST_PRODUCT);
        assertThat(first).isNull();
        double afterFirst = dbLoadCount();
        assertThat(afterFirst).as("第一次必然要查一次数据库来确认商品不存在").isGreaterThan(before);

        // 再查几次
        for (int i = 0; i < 5; i++) {
            assertThat(cacheService.getDetail(NOT_EXIST_PRODUCT)).isNull();
        }

        assertThat(dbLoadCount())
                .as("后续查询必须被空值缓存挡住，否则就是典型的缓存穿透（每次都打数据库）")
                .isEqualTo(afterFirst);

        assertThat(redis.hasKey(RedisKeys.productNullCache(NOT_EXIST_PRODUCT)))
                .as("空值占位必须真的写进了 Redis")
                .isTrue();
    }

    @Test
    @DisplayName("缓存雪崩防护：物理 TTL 带随机抖动，不同 key 的过期时间不会完全一致")
    void ttlHasJitter() {
        // 直接观察 Redis 的 TTL 值
        cacheService.getDetail(1001L);
        cacheService.getDetail(1002L);
        cacheService.getDetail(1003L);

        Long ttl1 = redis.getExpire(RedisKeys.productCache(1001L));
        Long ttl2 = redis.getExpire(RedisKeys.productCache(1002L));
        Long ttl3 = redis.getExpire(RedisKeys.productCache(1003L));

        assertThat(ttl1).isNotNull().isPositive();
        assertThat(ttl2).isNotNull().isPositive();
        assertThat(ttl3).isNotNull().isPositive();

        // 基础 TTL 是 5 分钟 = 300 秒，抖动向上浮动最多 20% → 300~360 秒
        assertThat(ttl1).isBetween(280L, 380L);
        // 不强求两两不同（理论上可能随机撞上同一个秒数），
        // 但只要三个值不全相等，就说明抖动在起作用
        assertThat(java.util.Set.of(ttl1, ttl2, ttl3))
                .as("三个 key 的 TTL 完全相同说明抖动没生效")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("数据更新后缓存必须失效：AFTER_COMMIT 生效，下一次读取回源 DB")
    void updateInvalidatesCache() {
        // 预热缓存
        assertThat(cacheService.getDetail(EXISTING_PRODUCT).getSource()).isEqualTo("DB");
        assertThat(cacheService.getDetail(EXISTING_PRODUCT).getSource()).isEqualTo("L1");

        // 更新商品（事务提交 → AFTER_COMMIT 监听器删除缓存）
        ProductSaveRequest request = new ProductSaveRequest();
        request.setTitle("缓存一致性验证-" + System.nanoTime());
        request.setPrice(new BigDecimal("1234.00"));
        request.setStock(66);
        request.setStatus(1);
        productService.update(EXISTING_PRODUCT, request);

        ProductDetailVO afterUpdate = cacheService.getDetail(EXISTING_PRODUCT);
        assertThat(afterUpdate.getSource())
                .as("更新后缓存必须已被删除，读请求应当回源数据库")
                .isEqualTo("DB");
        assertThat(afterUpdate.getTitle()).isEqualTo(request.getTitle());
        assertThat(afterUpdate.getPrice()).isEqualByComparingTo("1234.00");
    }

    @Test
    @DisplayName("参数校验层：非法 ID 直接返回 null，不查库也不查缓存")
    void invalidIdIsRejectedBeforeAnyLookup() {
        double before = dbLoadCount();

        assertThat(cacheService.getDetail(null)).isNull();
        assertThat(cacheService.getDetail(0L)).isNull();
        assertThat(cacheService.getDetail(-1L)).isNull();

        assertThat(dbLoadCount())
                .as("非法 ID 必须在最前面被挡掉，这是成本最低的一层防护")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("逻辑过期：到期后仍返回旧数据，并异步重建缓存")
    void logicalExpireReturnsStaleDataAndRebuilds() throws Exception {
        // 先缓存一份
        ProductDetailVO cached = cacheService.getDetail(EXISTING_PRODUCT);
        assertThat(cached.getSource()).isEqualTo("DB");

        // 手动把逻辑过期时间改成"已过期"，直接把物理 key 保留
        String key = RedisKeys.productCache(EXISTING_PRODUCT);
        String json = redis.opsForValue().get(key);
        assertThat(json).isNotNull();
        // 逻辑过期时间戳是 JSON 里的 logicalExpireAt 字段，改成 1（远古时间）
        String expired = json.replaceAll("\"logicalExpireAt\":\\d+", "\"logicalExpireAt\":1");
        assertThat(expired).isNotEqualTo(json);
        redis.opsForValue().set(key, expired, java.time.Duration.ofMinutes(5));

        // 清掉 L1，强制走 L2 的逻辑过期分支
        cacheService.invalidateAllLocal();

        ProductDetailVO result = cacheService.getDetail(EXISTING_PRODUCT);
        assertThat(result)
                .as("逻辑过期方案的核心特征：返回旧数据而不是阻塞等待重建")
                .isNotNull();
        assertThat(result.getSource()).isEqualTo("L2");

        // 异步重建应当很快完成，之后 Redis 里的逻辑过期时间会被刷新到未来
        Thread.sleep(500);
        String rebuilt = redis.opsForValue().get(key);
        assertThat(rebuilt).isNotNull();
        assertThat(logicalExpireAtOf(rebuilt))
                .as("异步重建应当把 logicalExpireAt 刷新到未来时间")
                .isGreaterThan(System.currentTimeMillis());
    }

    /**
     * 从缓存 JSON 里取出 logicalExpireAt。
     *
     * <p>这里刻意不用 {@code contains("\"logicalExpireAt\":1")} 这种子串断言：
     * 新写入的时间戳 {@code 1790765596303} 恰好<b>以 1 开头</b>，
     * 子串匹配会把"重建成功"误判成"重建失败"。
     * 凡是结构化数据，都应该解析成字段再断言，而不是匹配字符串片段。
     */
    private long logicalExpireAtOf(String json) {
        try {
            return com.lxt.mall.common.util.JsonUtils.mapper().readTree(json).path("logicalExpireAt").asLong();
        } catch (Exception e) {
            throw new AssertionError("缓存内容不是合法的 CacheValue JSON: " + json, e);
        }
    }
}
