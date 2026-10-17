package com.lxt.mall.support;

import com.lxt.mall.cache.ProductCacheService;
import com.lxt.mall.seckill.LocalSoldOutCache;
import com.lxt.mall.seckill.SeckillActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

/**
 * 集成测试基类。
 *
 * <p>每个用例前把<b>三层状态</b>全部复位，保证用例之间完全隔离、可任意顺序执行：
 * <ol>
 *   <li>MySQL：清空业务表（保留用户与商品，避免触发 DataSeeder 重复插入）</li>
 *   <li>Redis：flush 测试专用的 15 号库</li>
 *   <li><b>JVM 进程内的 Caffeine 缓存</b></li>
 * </ol>
 *
 * <h3>第 3 条为什么必须做（这是一个真实的踩坑）</h3>
 * 最初的版本只清了 MySQL 和 Redis，结果 {@code ProductCacheTest} 里
 * 出现了一批随机失败的用例：断言"第一次读应该回源数据库（source=DB）"，
 * 实际却得到 {@code source=L1}。
 *
 * <p>原因在于 <b>L1 本地缓存活在 Spring 上下文里，而 Spring 上下文在
 * 同一个测试类（甚至不同测试类）之间是复用的</b>。
 * 上一个用例把商品塞进了 Caffeine，下一个用例即使用
 * {@code flushDb} 清空了 Redis，读请求依然会在 L1 命中，
 * 压根不会走到 Redis 和数据库。
 *
 * <p>这类问题的危险之处在于它是<b>顺序相关</b>的：单独跑某个用例永远通过，
 * 全量跑就挂，而且挂的用例还会随执行顺序变化。修复方式就是承认
 * "内存缓存也是测试状态的一部分"，把它和数据库、Redis 一视同仁地清理。
 *
 * <p>顺带一个环境隔离的细节：Redis 用 <b>15 号库</b>而不是 0 号。
 * 因为 {@code flushDb} 是清库操作，如果测试和开发共用 0 号库，
 * 一个跑测试的动作就会把本地正在调试的缓存全清掉。
 * 测试环境必须与开发环境在<b>存储层面</b>隔离，不能只靠约定。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    protected static final long TEST_ACTIVITY_ID = 900_001L;
    protected static final long TEST_PRODUCT_ID = 1001L;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected ProductCacheService productCacheService;

    @Autowired
    protected SeckillActivityService seckillActivityService;

    @Autowired
    protected LocalSoldOutCache localSoldOutCache;

    @BeforeEach
    void resetState() {
        // 删除顺序无所谓（没有外键），但要把交易域的新表一起清掉：
        // 支付单 / 退款单的 uk_order_no 会跨用例残留，导致第二个用例的
        // "首次支付"被误判为"重复支付"。
        jdbcTemplate.execute("DELETE FROM t_refund");
        jdbcTemplate.execute("DELETE FROM t_payment");
        jdbcTemplate.execute("DELETE FROM t_order_item");
        jdbcTemplate.execute("DELETE FROM t_order");
        jdbcTemplate.execute("DELETE FROM t_stock_flow");
        jdbcTemplate.execute("DELETE FROM t_mq_message");

        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });

        // 进程内的 L1 缓存同样是测试状态，必须一并复位
        productCacheService.invalidateAllLocal();
        seckillActivityService.evictAllLocal();
        localSoldOutCache.clearAll();
    }

    /**
     * 准备一个可控库存的秒杀活动。
     *
     * @param activityId 活动 ID
     * @param stock      初始库存
     */
    protected void prepareActivity(long activityId, int stock) {
        jdbcTemplate.update("""
                INSERT INTO t_seckill_activity
                    (id, activity_name, product_id, seckill_price, total_stock, available_stock,
                     start_time, end_time, status)
                VALUES (?, ?, ?, ?, ?, ?, DATE_SUB(NOW(3), INTERVAL 1 HOUR), DATE_ADD(NOW(3), INTERVAL 1 DAY), 1)
                ON DUPLICATE KEY UPDATE
                    total_stock = VALUES(total_stock),
                    available_stock = VALUES(available_stock),
                    start_time = VALUES(start_time),
                    end_time = VALUES(end_time),
                    status = 1
                """, activityId, "测试活动-" + activityId, TEST_PRODUCT_ID,
                new BigDecimal("9.90"), stock, stock);
    }

    protected int dbAvailableStock(long activityId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT available_stock FROM t_seckill_activity WHERE id = ?", Integer.class, activityId);
        return value == null ? -1 : value;
    }

    protected int countOrders(String orderNo) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE order_no = ?", Integer.class, orderNo);
        return value == null ? 0 : value;
    }

    protected int countOrdersByActivity(long activityId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE activity_id = ?", Integer.class, activityId);
        return value == null ? 0 : value;
    }
}
