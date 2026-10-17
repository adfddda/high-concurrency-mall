package com.lxt.mall.seckill;

import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

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
 * 库存分桶测试（bucket-count = 4）。
 *
 * <p>默认配置下 {@code bucket-count=1}，走的是与早期版本完全一致的路径
 * （由 {@code SeckillStockConcurrencyTest} 守着）。这个类单独开一个 Spring 上下文，
 * 把分桶打开，验证三件事：
 * <ol>
 *   <li><b>跨桶仍然不超卖</b>：800 并发抢 200 件，成功数恰好 200；</li>
 *   <li><b>一人一单在桶内仍然成立</b>：同一用户第二次必然 REPEAT；</li>
 *   <li><b>回补落到正确的桶</b>：回补后总量恢复，且该用户可以重新抢。</li>
 * </ol>
 *
 * <p>为什么用 1_000_00x 这类连续 ID：桶路由是 {@code floorMod(hash(userId), 4)}，
 * 连续 ID 会均匀分布到 4 个桶，每个桶恰好 200 个用户抢 50 件库存——
 * 结果因此是确定性的，不会因为哈希倾斜导致偶发失败。
 */
@TestPropertySource(properties = {"mall.seckill.bucket-count=4"})
class BucketedSeckillTest extends BaseIntegrationTest {

    private static final long OVERSell_ACTIVITY = 970_001L;
    private static final long REPEAT_ACTIVITY = 970_002L;
    private static final long ROLLBACK_ACTIVITY = 970_003L;

    @Autowired
    private SeckillStockService stockService;

    @Autowired
    private SeckillPreheatService preheatService;

    private void preheat(long activityId, int stock) {
        prepareActivity(activityId, stock);
        preheatService.preheat(activityId);
    }

    @Test
    @DisplayName("4 个桶下 800 并发抢 200 件：成功数恰好 200，库存恰好归零")
    void fourBucketsNeverOversell() throws Exception {
        int total = 200;
        preheat(OVERSell_ACTIVITY, total);

        assertThat(stockService.getStock(OVERSell_ACTIVITY))
                .as("预热应按桶拆分库存，总和不变")
                .isEqualTo(total);

        int threads = 800;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(threads);

        for (int i = 1; i <= threads; i++) {
            long userId = 1_000_000L + i;
            futures.add(pool.submit(() -> {
                start.await();
                if (stockService.deduct(OVERSell_ACTIVITY, userId) == SeckillDeductResult.SUCCESS) {
                    success.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(success.get()).as("分桶绝不能导致超卖").isEqualTo(total);
        assertThat(stockService.getStock(OVERSell_ACTIVITY)).as("所有桶都应售罄").isZero();
    }

    @Test
    @DisplayName("分桶后一人一单仍然成立：同一用户第二次必然 REPEAT")
    void oneUserOneOrderWithinBucket() {
        preheat(REPEAT_ACTIVITY, 100);
        long userId = 2_000_001L;

        assertThat(stockService.deduct(REPEAT_ACTIVITY, userId)).isEqualTo(SeckillDeductResult.SUCCESS);
        assertThat(stockService.deduct(REPEAT_ACTIVITY, userId))
                .as("用户的已购集合在他自己的桶里，判定依然准确")
                .isEqualTo(SeckillDeductResult.REPEAT);
    }

    @Test
    @DisplayName("回补落到用户所属的桶：总量恢复，且该用户可以重新抢")
    void rollbackReturnsToTheSameBucket() {
        int total = 100;
        preheat(ROLLBACK_ACTIVITY, total);
        long userId = 3_000_001L;

        assertThat(stockService.deduct(ROLLBACK_ACTIVITY, userId)).isEqualTo(SeckillDeductResult.SUCCESS);
        assertThat(stockService.getStock(ROLLBACK_ACTIVITY)).isEqualTo(total - 1);

        long after = stockService.rollback(ROLLBACK_ACTIVITY, userId, total, true, false);

        assertThat(after).as("回补后的桶库存应大于等于 0").isGreaterThanOrEqualTo(0);
        assertThat(stockService.getStock(ROLLBACK_ACTIVITY))
                .as("跨桶聚合后的总量应恢复到初始值")
                .isEqualTo(total);
        assertThat(stockService.deduct(ROLLBACK_ACTIVITY, userId))
                .as("释放资格后应能重新抢")
                .isEqualTo(SeckillDeductResult.SUCCESS);
    }
}
