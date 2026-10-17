package com.lxt.mall.trade;

import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.support.BaseIntegrationTest;
import com.lxt.mall.trade.dto.PaymentVO;
import com.lxt.mall.trade.dto.RefundVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 普通交易链路测试（SKU 下单 / 支付 / 退款）。
 *
 * <p>与秒杀测试的对照很有意思：
 * <ul>
 *   <li>秒杀测试关注"并发下不超卖"（800 线程抢 200 件）</li>
 *   <li>普通交易关注"状态机与幂等"（重复回调、重复退款）</li>
 * </ul>
 * 两套关注点不同的原因，是两条链路的并发模型不同——
 * 一个靠 Redis 挡流量，一个靠数据库行锁。
 */
class TradeFlowTest extends BaseIntegrationTest {

    private static final long SKU_ID = 910_001L;
    private static final long USER_ID = 810_001L;
    private static final BigDecimal PRICE = new BigDecimal("99.00");
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private TradeService tradeService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private OrderService orderService;

    @BeforeEach
    void prepareSku() {
        // ON DUPLICATE KEY UPDATE 让每个用例都从 10 件库存开始，用例之间互不影响
        jdbcTemplate.update("""
                INSERT INTO t_sku (id, product_id, sku_code, spec_json, price, stock, status)
                VALUES (?, ?, ?, ?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE stock = VALUES(stock), price = VALUES(price), status = 1
                """, SKU_ID, TEST_PRODUCT_ID, "SKU-TEST-1", "{\"color\":\"black\"}",
                PRICE, INITIAL_STOCK);
    }

    private int skuStock() {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM t_sku WHERE id = ?", Integer.class, SKU_ID);
        return stock == null ? -1 : stock;
    }

    @Test
    @DisplayName("普通下单：同步扣 SKU 库存并落库为 NORMAL 订单")
    void createOrderDeductsSkuStock() {
        OrderVO vo = tradeService.createOrder(USER_ID, SKU_ID, 2);

        assertThat(vo.getOrderNo()).startsWith("NO");
        assertThat(skuStock()).as("2 件库存应被同步扣减").isEqualTo(INITIAL_STOCK - 2);

        var order = orderService.findByOrderNo(vo.getOrderNo());
        assertThat(order.getBizType()).as("biz_type=1 表示普通订单").isEqualTo(1);
        assertThat(order.getActivityId()).as("普通订单没有活动维度").isNull();
        assertThat(order.getSkuId()).isEqualTo(SKU_ID);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getPayAmount()).isEqualByComparingTo("198.00");
    }

    @Test
    @DisplayName("库存不足：下单失败且不产生订单、不扣库存")
    void createOrderFailsWhenStockNotEnough() {
        assertThatThrownBy(() -> tradeService.createOrder(USER_ID, SKU_ID, INITIAL_STOCK + 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        assertThat(skuStock()).isEqualTo(INITIAL_STOCK);
        Integer orders = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_order", Integer.class);
        assertThat(orders).as("失败的下单不能留下订单").isZero();
    }

    @Test
    @DisplayName("支付回调幂等：同一笔回调重复抵达只生效一次")
    void paymentCallbackIsIdempotent() {
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 1);
        PaymentVO payment = paymentService.createPayment(order.getOrderNo(), USER_ID);

        assertThat(paymentService.handleCallback(payment.getPaymentNo(), "CH-0001", PRICE)).isTrue();
        assertThat(paymentService.handleCallback(payment.getPaymentNo(), "CH-0001", PRICE))
                .as("渠道重试必须返回成功")
                .isTrue();

        assertThat(orderService.findByOrderNo(order.getOrderNo()).getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("支付金额不一致：拒绝回调，订单保持待支付")
    void paymentCallbackRejectsAmountMismatch() {
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 1);
        PaymentVO payment = paymentService.createPayment(order.getOrderNo(), USER_ID);

        assertThatThrownBy(() -> paymentService.handleCallback(
                payment.getPaymentNo(), "CH-0002", new BigDecimal("0.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额");

        assertThat(orderService.findByOrderNo(order.getOrderNo()).getStatus())
                .isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("未支付订单不能退款")
    void refundRejectedBeforePayment() {
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 1);

        assertThatThrownBy(() -> refundService.refund(order.getOrderNo(), "测试", USER_ID))
                .isInstanceOf(BusinessException.class);

        assertThat(skuStock()).as("退款失败不能回补库存").isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("退款：回补库存、订单置为已退款，且重复调用不会多还库存")
    void refundRestoresStockAndIsIdempotent() {
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 2);
        PaymentVO payment = paymentService.createPayment(order.getOrderNo(), USER_ID);
        paymentService.handleCallback(payment.getPaymentNo(), "CH-0003", new BigDecimal("198.00"));

        RefundVO first = refundService.refund(order.getOrderNo(), "不想要了", USER_ID);
        assertThat(skuStock()).as("退款应回补 2 件").isEqualTo(INITIAL_STOCK);

        RefundVO second = refundService.refund(order.getOrderNo(), "不想要了", USER_ID);
        assertThat(second.getRefundNo()).as("重复退款应返回同一张退款单").isEqualTo(first.getRefundNo());
        assertThat(skuStock()).as("重复退款绝不能多还库存（否则就是超卖）").isEqualTo(INITIAL_STOCK);

        assertThat(orderService.findByOrderNo(order.getOrderNo()).getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }
}
