package com.lxt.mall.seckill;

import com.lxt.mall.config.MallProperties;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地库存分段：段领取与段登记。
 *
 * <p>这一层最容易被误解的地方是"领走的库存去哪了"。答案是：
 * <b>从 Redis 的库存里扣掉，登记到该实例名下</b>。
 * 于是"已领 + 在库"恒等于原始库存——不超卖是算术结论，不是约定。
 *
 * <p>崩溃回收则靠登记项的 TTL：实例不再续期后登记消失，
 * 对账把这批库存当成"未归还"补回。
 */
class SegmentStockTest extends BaseIntegrationTest {

    private static final long ACTIVITY_ID = 900_501L;

    @Autowired
    private MallProperties properties;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisScript<Long> seckillClaimSegmentScript;

    @Autowired
    private com.lxt.mall.seckill.SeckillPreheatService preheatService;

    @Autowired
    private com.lxt.mall.seckill.SeckillStockService stockService;

    private SegmentStockManager newManager() {
        return new SegmentStockManager(redis, seckillClaimSegmentScript, properties);
    }

    private String stockKey() {
        return "seckill:stock:{act:" + ACTIVITY_ID + "}";
    }

    @Test
    @DisplayName("领段：从 Redis 库存里扣掉，登记到实例名下，账算得平")
    void claimMovesStockFromRedisToSegment() {
        prepareActivity(ACTIVITY_ID, 10);
        preheatService.preheat(ACTIVITY_ID);
        assertThat(stockService.getStock(ACTIVITY_ID)).isEqualTo(10);

        SegmentStockManager manager = newManager();
        assertThat(manager.tryAcquire(ACTIVITY_ID)).isTrue();
        assertThat(manager.tryAcquire(ACTIVITY_ID)).isTrue();
        assertThat(manager.tryAcquire(ACTIVITY_ID)).isTrue();

        // 段大小默认 50 > 库存 10，所以第一次就把 10 件全领走了
        int localLeft = manager.unspent(ACTIVITY_ID);
        long registered = manager.registered(ACTIVITY_ID, manager.instanceId());
        assertThat(localLeft).isEqualTo(7);
        assertThat(registered).isEqualTo(10);
        // 已领(10) + 在库(0) = 原始库存(10)
        assertThat(registered + stockService.getStock(ACTIVITY_ID)).isEqualTo(10);
    }

    @Test
    @DisplayName("段耗尽后再领：库存已为 0 时返回失败，且不会把库存领成负数")
    void exhaustedSegmentDoesNotGoNegative() {
        prepareActivity(ACTIVITY_ID, 2);
        preheatService.preheat(ACTIVITY_ID);

        SegmentStockManager manager = newManager();
        // 段大小 50 但库存只有 2：只能领到 2
        assertThat(manager.tryAcquire(ACTIVITY_ID)).isTrue();
        assertThat(manager.tryAcquire(ACTIVITY_ID)).isTrue();
        // 第三次：本地段空 + Redis 也空
        assertThat(manager.tryAcquire(ACTIVITY_ID)).isFalse();

        assertThat(stockService.getStock(ACTIVITY_ID)).isZero();
        assertThat(manager.registered(ACTIVITY_ID, manager.instanceId())).isEqualTo(2);
    }

    @Test
    @DisplayName("归还回本地段，且不回写 Redis（否则会重复计数）")
    void releaseGoesBackToLocalSegmentOnly() {
        prepareActivity(ACTIVITY_ID, 5);
        preheatService.preheat(ACTIVITY_ID);

        SegmentStockManager manager = newManager();
        assertThat(manager.tryAcquire(ACTIVITY_ID)).isTrue();
        int afterClaim = manager.unspent(ACTIVITY_ID);
        int redisStock = stockService.getStock(ACTIVITY_ID);

        manager.release(ACTIVITY_ID);

        assertThat(manager.unspent(ACTIVITY_ID)).isEqualTo(afterClaim + 1);
        // Redis 侧不受影响：还错地方会造成"库存看起来回来了、其实多算一份"
        assertThat(stockService.getStock(ACTIVITY_ID)).isEqualTo(redisStock);
    }

    @Test
    @DisplayName("多个实例的已领取量可以汇总，且与在库库存相加恒等于原始库存")
    void grabbedSumsAcrossInstances() {
        prepareActivity(ACTIVITY_ID, 100);
        preheatService.preheat(ACTIVITY_ID);

        SegmentStockManager first = newManager();
        SegmentStockManager second = newManager();
        assertThat(first.tryAcquire(ACTIVITY_ID)).isTrue();
        assertThat(second.tryAcquire(ACTIVITY_ID)).isTrue();

        long grabbed = first.totalGrabbedAcrossInstances(ACTIVITY_ID);
        // 登记的是"领走多少"，不是"还剩多少"：两段各 50，库存被领到 0
        assertThat(grabbed).isEqualTo(100);
        assertThat(grabbed + stockService.getStock(ACTIVITY_ID)).isEqualTo(100);
        // 本地未售出是精确的：各卖 1 件后各剩 49（汇总要靠心跳上报，下一步实现）
        assertThat(first.unspent(ACTIVITY_ID)).isEqualTo(49);
        assertThat(second.unspent(ACTIVITY_ID)).isEqualTo(49);
    }
}
