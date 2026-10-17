package com.lxt.mall.order;

import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.mapper.OrderItemMapper;
import com.lxt.mall.mq.exception.DuplicateMessageException;
import com.lxt.mall.mq.exception.DuplicateOrderException;
import com.lxt.mall.seckill.SeckillPreheatService;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单幂等性验证。
 *
 * <p>消息队列的投递语义是<b>至少一次（at-least-once）</b>，
 * 也就是说"同一条消息被消费两次"不是异常，而是必然会发生的事
 * （消费者处理完但 ACK 丢失、网络抖动导致重投、broker 主从切换……）。
 * 所以幂等不是"最好有"，而是<b>必须由数据库强约束保证</b>。
 *
 * <p>这一组测试直接验证三层唯一索引是否真的在起作用：
 * <pre>
 *   uk_message_id     → 同一条消息重复消费
 *   uk_user_activity  → 同一用户抢同一活动
 *   uk_order_no       → 同一订单号
 * </pre>
 */
class OrderIdempotencyTest extends BaseIntegrationTest {

    private static final long ACTIVITY_ID = 900_201L;

    @Autowired
    private OrderCreationService orderCreationService;

    @Autowired
    private com.lxt.mall.domain.mapper.OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private SeckillPreheatService preheatService;

    private SeckillOrderMessage buildMessage(String messageId, String orderNo, long userId) {
        return new SeckillOrderMessage(messageId, orderNo, userId, ACTIVITY_ID, TEST_PRODUCT_ID,
                "测试商品", new BigDecimal("9.90"), 1, 100, LocalDateTime.now());
    }

    @Test
    @DisplayName("同一条消息重复消费：只产生一个订单，第二次抛 DuplicateMessageException")
    void duplicateMessageCreatesOnlyOneOrder() {
        prepareActivity(ACTIVITY_ID, 100);
        String messageId = UUID.randomUUID().toString().replace("-", "");
        String orderNo = "SO-DUP-" + System.nanoTime();
        SeckillOrderMessage message = buildMessage(messageId, orderNo, 600_001L);

        Order created = orderCreationService.createFromSeckill(message);
        assertThat(created).isNotNull();
        assertThat(countOrders(orderNo)).isEqualTo(1);

        // 同一条消息再来一次
        assertThatThrownBy(() -> orderCreationService.createFromSeckill(message))
                .isInstanceOf(DuplicateMessageException.class);

        assertThat(countOrders(orderNo)).as("重复消费不能产生第二个订单").isEqualTo(1);
        assertThat(orderItemMapper.selectCount(null))
                .as("订单项也不应该被重复写入")
                .isEqualTo(1);
        // 数据库库存只被扣了一次
        assertThat(dbAvailableStock(ACTIVITY_ID)).isEqualTo(99);
    }

    @Test
    @DisplayName("不同消息、同一用户同一活动：被 uk_user_activity 拦下")
    void sameUserSameActivityIsRejectedByUniqueKey() {
        prepareActivity(ACTIVITY_ID, 100);
        long userId = 600_002L;

        SeckillOrderMessage first = buildMessage(
                UUID.randomUUID().toString().replace("-", ""), "SO-A-" + System.nanoTime(), userId);
        orderCreationService.createFromSeckill(first);

        SeckillOrderMessage second = buildMessage(
                UUID.randomUUID().toString().replace("-", ""), "SO-B-" + System.nanoTime(), userId);

        assertThatThrownBy(() -> orderCreationService.createFromSeckill(second))
                .as("Redis 的一人一单判定被绕过时，数据库唯一键必须兜住")
                .isInstanceOf(DuplicateOrderException.class);

        assertThat(countOrdersByActivity(ACTIVITY_ID)).isEqualTo(1);
        // 第二次的库存扣减随事务一起回滚了
        assertThat(dbAvailableStock(ACTIVITY_ID)).isEqualTo(99);
    }

    @Test
    @DisplayName("库存不足时整笔事务回滚，不会留下孤儿订单")
    void insufficientStockRollsBackEverything() {
        prepareActivity(ACTIVITY_ID, 0);   // 数据库库存为 0

        SeckillOrderMessage message = buildMessage(
                UUID.randomUUID().toString().replace("-", ""), "SO-NOSTOCK-" + System.nanoTime(), 600_003L);

        assertThatThrownBy(() -> orderCreationService.createFromSeckill(message))
                .isInstanceOf(com.lxt.mall.mq.exception.NonRetryableMessageException.class);

        assertThat(countOrdersByActivity(ACTIVITY_ID)).isZero();
        assertThat(orderItemMapper.selectCount(null)).as("订单项必须一并回滚").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_mq_message WHERE biz_type = 'SECKILL_ORDER'", Integer.class))
                .as("消费记录必须与业务同事务回滚，否则重试时会被误判为已消费而永久丢消息")
                .isZero();
        assertThat(dbAvailableStock(ACTIVITY_ID)).isZero();
    }

    @Test
    @DisplayName("库存扣减是条件更新：库存为 1 时两个订单只有一个能成功")
    void conditionalStockDeductionAllowsOnlyOne() {
        prepareActivity(ACTIVITY_ID, 1);

        SeckillOrderMessage first = buildMessage(
                UUID.randomUUID().toString().replace("-", ""), "SO-X-" + System.nanoTime(), 600_004L);
        SeckillOrderMessage second = buildMessage(
                UUID.randomUUID().toString().replace("-", ""), "SO-Y-" + System.nanoTime(), 600_005L);

        orderCreationService.createFromSeckill(first);

        assertThatThrownBy(() -> orderCreationService.createFromSeckill(second))
                .isInstanceOf(com.lxt.mall.mq.exception.NonRetryableMessageException.class);

        assertThat(countOrdersByActivity(ACTIVITY_ID)).isEqualTo(1);
        assertThat(dbAvailableStock(ACTIVITY_ID)).isZero();
    }

    @Test
    @DisplayName("数据库 CHECK 约束兜底：库存不可能被更新成负数")
    void databaseRefusesNegativeStock() {
        prepareActivity(ACTIVITY_ID, 0);

        // 绕过应用层的条件判断，直接尝试把库存扣成负数
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE t_seckill_activity SET available_stock = available_stock - 1 WHERE id = ?",
                ACTIVITY_ID))
                .isInstanceOf(DataAccessException.class);

        assertThat(dbAvailableStock(ACTIVITY_ID)).isZero();
    }

    @Test
    @DisplayName("订单落库后能查到，且 expire_time 正确设置")
    void orderHasCorrectExpireTime() {
        prepareActivity(ACTIVITY_ID, 10);
        String orderNo = "SO-EXP-" + System.nanoTime();
        SeckillOrderMessage message = buildMessage(
                UUID.randomUUID().toString().replace("-", ""), orderNo, 600_006L);

        Order order = orderCreationService.createFromSeckill(message);

        Order loaded = orderMapper.selectById(order.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOrderNo()).isEqualTo(orderNo);
        assertThat(loaded.getExpireTime()).isAfter(LocalDateTime.now());
        assertThat(loaded.getStatus()).isEqualTo(com.lxt.mall.domain.enums.OrderStatus.CREATED);
        assertThat(loaded.getPayAmount()).isEqualByComparingTo("9.90");
    }
}
