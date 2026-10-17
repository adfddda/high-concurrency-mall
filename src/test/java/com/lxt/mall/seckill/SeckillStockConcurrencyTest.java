package com.lxt.mall.seckill;

import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀扣减的并发正确性验证（本项目最重要的一组测试）。
 *
 * <p>面试里说"用 Lua 保证了原子性、不会超卖"是<b>没有说服力的</b>——
 * 任何人都可以这么说。有说服力的是：真的起几百个并发线程去打同一份库存，
 * 然后断言"成功次数 == 库存数"且"Redis 剩余库存 == 0"。
 *
 * <p>这里用 Java 21 的<b>虚拟线程</b>而不是固定线程池：
 * 虚拟线程的创建成本极低，可以真正做到"八百个请求同时发出"，
 * 而固定大小线程池只能做到"八百个任务分批排队执行"，
 * 后者的并发度受限于池大小，压不出真正的竞态。
 */
class SeckillStockConcurrencyTest extends BaseIntegrationTest {

    private static final long ACTIVITY_ID = 900_101L;

    @Autowired
    private SeckillStockService stockService;

    @Autowired
    private SeckillPreheatService preheatService;

    @Autowired
    private LocalSoldOutCache localSoldOutCache;

    @Test
    @DisplayName("800 并发抢 200 库存：成功数恰好 200，Redis 库存归零，绝不超卖")
    void concurrentDeductNeverOversells() throws Exception {
        int stock = 200;
        int concurrency = 800;

        prepareActivity(ACTIVITY_ID, stock);
        preheatService.preheat(ACTIVITY_ID);
        localSoldOutCache.clear(ACTIVITY_ID);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger repeat = new AtomicInteger();
        AtomicInteger notPrepared = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>(concurrency);

            for (int i = 0; i < concurrency; i++) {
                // 每个请求用不同的 userId：一人一单的限制不能干扰"库存是否超卖"的验证
                final long userId = 500_000L + i;
                futures.add(pool.submit(() -> {
                    go.await();
                    SeckillDeductResult result = stockService.deduct(ACTIVITY_ID, userId);
                    switch (result) {
                        case SUCCESS -> success.incrementAndGet();
                        case SOLD_OUT -> soldOut.incrementAndGet();
                        case REPEAT -> repeat.incrementAndGet();
                        case NOT_PREPARED -> notPrepared.incrementAndGet();
                    }
                    return null;
                }));
            }

            go.countDown();     // 所有线程就绪后同时放行，最大化竞态
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(notPrepared.get()).as("不应该出现未预热").isZero();
        assertThat(repeat.get()).as("不同用户之间不应该出现重复购买").isZero();

        // 核心断言 1：成功次数必须严格等于库存数
        assertThat(success.get())
                .as("并发扣减的成功次数必须恰好等于库存数，多一次就是超卖")
                .isEqualTo(stock);

        // 核心断言 2：剩余库存必须归零，且不能为负
        assertThat(stockService.getStock(ACTIVITY_ID))
                .as("Redis 剩余库存必须恰好为 0")
                .isZero();

        // 核心断言 3：被拒绝的请求数 = 总请求数 - 库存数
        assertThat(soldOut.get()).isEqualTo(concurrency - stock);

        // 核心断言 4：已购集合大小 == 成功次数（每个用户只被记录一次）
        assertThat(stockService.boughtCount(ACTIVITY_ID)).isEqualTo(stock);
    }

    @Test
    @DisplayName("同一用户 500 次并发请求：只有 1 次成功（一人一单）")
    void oneUserCanOnlyBuyOnce() throws Exception {
        int stock = 100;
        int concurrency = 500;
        long userId = 777_777L;

        prepareActivity(ACTIVITY_ID, stock);
        preheatService.preheat(ACTIVITY_ID);
        localSoldOutCache.clear(ACTIVITY_ID);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger repeat = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    SeckillDeductResult result = stockService.deduct(ACTIVITY_ID, userId);
                    if (result == SeckillDeductResult.SUCCESS) {
                        success.incrementAndGet();
                    } else if (result == SeckillDeductResult.REPEAT) {
                        repeat.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(success.get()).as("同一用户只能成功一次").isEqualTo(1);
        assertThat(repeat.get()).isEqualTo(concurrency - 1);
        // 库存只被扣掉 1 件
        assertThat(stockService.getStock(ACTIVITY_ID)).isEqualTo(stock - 1);
    }

    @Test
    @DisplayName("未预热的活动必须快速失败，绝不能放行到数据库")
    void notPreheatedMustFailFast() {
        prepareActivity(ACTIVITY_ID, 10);
        // 刻意不调用 preheat
        redis.delete(com.lxt.mall.common.constant.RedisKeys.seckillStock(ACTIVITY_ID));

        SeckillDeductResult result = stockService.deduct(ACTIVITY_ID, 1L);

        assertThat(result)
                .as("库存 key 不存在时必须返回 NOT_PREPARED；"
                        + "如果这里放行，请求会直接打到数据库，预热就失去意义了")
                .isEqualTo(SeckillDeductResult.NOT_PREPARED);
    }

    @Test
    @DisplayName("回补库存会被总库存封顶，重复回补不会凭空造出库存")
    void rollbackIsCappedByTotalStock() {
        int totalStock = 10;
        long userId = 888_888L;
        prepareActivity(ACTIVITY_ID, totalStock);
        preheatService.preheat(ACTIVITY_ID);

        // 扣掉一件
        assertThat(stockService.deduct(ACTIVITY_ID, userId)).isEqualTo(SeckillDeductResult.SUCCESS);
        assertThat(stockService.getStock(ACTIVITY_ID)).isEqualTo(totalStock - 1);

        // 回补一件
        long afterRollback = stockService.rollback(ACTIVITY_ID, userId, totalStock, true, true);
        assertThat(afterRollback).isEqualTo(totalStock);

        // 再回补 N 次：库存必须被 totalStock 封住，绝不能超过
        for (int i = 0; i < 5; i++) {
            stockService.rollback(ACTIVITY_ID, userId, totalStock, true, true);
        }
        assertThat(stockService.getStock(ACTIVITY_ID))
                .as("重复回补必须被总库存封顶——这是【宁可少卖绝不超卖】原则的最后一道保险")
                .isEqualTo(totalStock);
    }

    @Test
    @DisplayName("回补后本地售罄标记必须被清除，否则会一直拒绝用户")
    void rollbackClearsLocalSoldOutFlag() {
        int totalStock = 1;
        long userA = 1_000_001L;
        long userB = 1_000_002L;

        prepareActivity(ACTIVITY_ID, totalStock);
        preheatService.preheat(ACTIVITY_ID);

        // 唯一的库存被 A 抢走 → 本地标记售罄
        assertThat(stockService.deduct(ACTIVITY_ID, userA)).isEqualTo(SeckillDeductResult.SUCCESS);
        assertThat(stockService.deduct(ACTIVITY_ID, userB)).isEqualTo(SeckillDeductResult.SOLD_OUT);
        assertThat(localSoldOutCache.isSoldOut(ACTIVITY_ID)).isTrue();

        // A 的订单创建失败，库存回补
        stockService.rollback(ACTIVITY_ID, userA, totalStock, true, true);

        assertThat(localSoldOutCache.isSoldOut(ACTIVITY_ID))
                .as("回补后必须清掉本地售罄标记，否则本实例会一直拒绝请求")
                .isFalse();
        // B 现在应该能抢到
        assertThat(stockService.deduct(ACTIVITY_ID, userB)).isEqualTo(SeckillDeductResult.SUCCESS);
    }
}
