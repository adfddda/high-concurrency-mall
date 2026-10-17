package com.lxt.mall.order;

import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.seckill.SeckillPreheatService;
import com.lxt.mall.seckill.SeckillStockService;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单状态机与超时关单验证。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li><b>状态竞争只有一个赢家</b>：超时关单、用户取消、支付回调可能同时发生，
 *       条件更新保证只有第一个能改到行。</li>
 *   <li><b>库存只回补一次</b>：回补动作挂在"取消成功"的分支上，
 *       重复调用返回 false，库存不会被加两次（加两次就是超卖）。</li>
 * </ol>
 */
class OrderTimeoutTest extends BaseIntegrationTest {

    private static final long ACTIVITY_ID = 900_301L;

    @Autowired
    private OrderCreationService orderCreationService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private SeckillStockService stockService;

    @Autowired
    private SeckillPreheatService preheatService;

    @Autowired
    private com.lxt.mall.domain.mapper.OrderMapper orderMapper;

    private SeckillOrderMessage createOrder(long userId) {
        String orderNo = "SO-TMO-" + System.nanoTime();
        SeckillOrderMessage message = new SeckillOrderMessage(
                UUID.randomUUID().toString().replace("-", ""), orderNo, userId, ACTIVITY_ID,
                TEST_PRODUCT_ID, "测试商品", new BigDecimal("9.90"), 1, 100, LocalDateTime.now());
        orderCreationService.createFromSeckill(message);
        return message;
    }

    @Test
    @DisplayName("超时关单：状态改为已取消，数据库与 Redis 库存各回补一次")
    void timeoutClosesOrderAndRestoresBothStocks() {
        int totalStock = 10;
        long userId = 700_001L;
        prepareActivity(ACTIVITY_ID, totalStock);
        preheatService.preheat(ACTIVITY_ID);

        // 走完整的秒杀扣减，让 Redis 库存和 DB 库存都减 1
        assertThat(stockService.deduct(ACTIVITY_ID, userId))
                .isEqualTo(com.lxt.mall.seckill.SeckillDeductResult.SUCCESS);
        SeckillOrderMessage message = createOrder(userId);

        assertThat(dbAvailableStock(ACTIVITY_ID)).isEqualTo(totalStock - 1);
        assertThat(stockService.getStock(ACTIVITY_ID)).isEqualTo(totalStock - 1);

        // 超时关单
        OrderService.CancelResult result = orderService.cancelIfUnpaid(message.getOrderNo(), "测试超时");
        assertThat(result.canceled()).isTrue();

        // 数据库已回补
        assertThat(dbAvailableStock(ACTIVITY_ID)).isEqualTo(totalStock);
        Order order = orderService.findByOrderNo(message.getOrderNo());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCancelTime()).isNotNull();

        // 模拟消费者在事务提交后归还 Redis 库存
        stockService.rollback(result.activityId(), result.userId(),
                result.totalStock() == null ? totalStock : result.totalStock(), false, false);
        assertThat(stockService.getStock(ACTIVITY_ID)).isEqualTo(totalStock);

        // 库存流水应当有两条：一条扣减、一条回补
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_stock_flow WHERE order_no = ?", Integer.class, message.getOrderNo()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("重复关单只成功一次，库存不会被回补两次")
    void cancelIsIdempotent() {
        int totalStock = 5;
        long userId = 700_002L;
        prepareActivity(ACTIVITY_ID, totalStock);
        preheatService.preheat(ACTIVITY_ID);

        SeckillOrderMessage message = createOrder(userId);

        OrderService.CancelResult first = orderService.cancelIfUnpaid(message.getOrderNo(), "第一次");
        assertThat(first.canceled()).isTrue();
        assertThat(dbAvailableStock(ACTIVITY_ID)).isEqualTo(totalStock);

        // 再关 3 次
        for (int i = 0; i < 3; i++) {
            OrderService.CancelResult again = orderService.cancelIfUnpaid(message.getOrderNo(), "重复");
            assertThat(again.canceled())
                    .as("订单已经是 CANCELED，条件更新应当返回 0 行")
                    .isFalse();
        }

        assertThat(dbAvailableStock(ACTIVITY_ID))
                .as("库存必须只被回补一次，多补就是超卖")
                .isEqualTo(totalStock);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_stock_flow WHERE order_no = ? AND change_type = 2",
                Integer.class, message.getOrderNo()))
                .as("回补流水只能有一条（uk_order_change 兜底）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("已支付订单不能被超时关单（状态竞争只有一个赢家）")
    void paidOrderCannotBeClosed() {
        int totalStock = 5;
        long userId = 700_003L;
        prepareActivity(ACTIVITY_ID, totalStock);
        preheatService.preheat(ACTIVITY_ID);

        SeckillOrderMessage message = createOrder(userId);

        // 先支付
        assertThat(orderService.pay(message.getOrderNo())).isTrue();

        // 超时关单必须失败
        OrderService.CancelResult result = orderService.cancelIfUnpaid(message.getOrderNo(), "迟到的关单");
        assertThat(result.canceled())
                .as("已支付的订单绝不能被关单，否则会出现【付了钱却没订单】")
                .isFalse();

        Order order = orderService.findByOrderNo(message.getOrderNo());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        // 库存不应该被回补
        assertThat(dbAvailableStock(ACTIVITY_ID)).isEqualTo(totalStock - 1);
    }

    @Test
    @DisplayName("重复支付回调：第二次必须失败，避免重复发货")
    void duplicatePayCallbackIsRejected() {
        long userId = 700_004L;
        prepareActivity(ACTIVITY_ID, 5);
        preheatService.preheat(ACTIVITY_ID);

        SeckillOrderMessage message = createOrder(userId);

        assertThat(orderService.pay(message.getOrderNo())).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orderService.pay(message.getOrderNo()))
                .isInstanceOf(com.lxt.mall.common.exception.BusinessException.class)
                .hasMessageContaining("无法支付");
    }

    @Test
    @DisplayName("越权校验：不能查询别人的订单")
    void cannotQueryOthersOrder() {
        long owner = 700_005L;
        long attacker = 700_006L;
        prepareActivity(ACTIVITY_ID, 5);
        preheatService.preheat(ACTIVITY_ID);

        SeckillOrderMessage message = createOrder(owner);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> orderService.queryVO(message.getOrderNo(), attacker))
                .isInstanceOf(com.lxt.mall.common.exception.BusinessException.class)
                .hasMessageContaining("无权");

        assertThat(orderService.queryVO(message.getOrderNo(), owner)).isNotNull();
    }

    @Test
    @DisplayName("过期未支付订单能被兜底扫描查到")
    void expiredUnpaidOrdersAreScannable() {
        long userId = 700_007L;
        prepareActivity(ACTIVITY_ID, 5);
        preheatService.preheat(ACTIVITY_ID);

        SeckillOrderMessage message = createOrder(userId);

        // 把 expire_time 改到过去，模拟已经超时
        jdbcTemplate.update("UPDATE t_order SET expire_time = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) WHERE order_no = ?",
                message.getOrderNo());

        assertThat(orderService.findExpiredUnpaid(10))
                .extracting(Order::getOrderNo)
                .contains(message.getOrderNo());
    }
}
