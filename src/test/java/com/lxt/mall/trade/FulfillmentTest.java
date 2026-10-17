package com.lxt.mall.trade;

import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.order.FulfillmentService;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.support.BaseIntegrationTest;
import com.lxt.mall.trade.dto.PaymentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约链路测试：PAID → SHIPPED → FINISHED。
 *
 * <p>这一组用例的核心是<b>状态机的边界</b>：
 * 未支付不能发货、未发货不能确认、发货后不能退款、
 * 自动确认与手动确认不会重复完成。
 */
class FulfillmentTest extends BaseIntegrationTest {

    private static final long SKU_ID = 920_001L;
    private static final long USER_ID = 820_001L;
    private static final BigDecimal PRICE = new BigDecimal("50.00");

    @Autowired
    private TradeService tradeService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private FulfillmentService fulfillmentService;
    @Autowired
    private OrderService orderService;

    @BeforeEach
    void prepareSku() {
        jdbcTemplate.update("""
                INSERT INTO t_sku (id, product_id, sku_code, spec_json, price, stock, status)
                VALUES (?, ?, ?, ?, ?, 20, 1)
                ON DUPLICATE KEY UPDATE stock = 20, price = VALUES(price), status = 1
                """, SKU_ID, TEST_PRODUCT_ID, "SKU-FULFILL-1", "{\"color\":\"white\"}", PRICE);
    }

    private OrderVO paidOrder() {
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 1);
        PaymentVO payment = paymentService.createPayment(order.getOrderNo(), USER_ID);
        paymentService.handleCallback(payment.getPaymentNo(), "CH-F-" + order.getOrderNo(), PRICE);
        return order;
    }

    @Test
    @DisplayName("未支付订单不能发货")
    void cannotShipUnpaidOrder() {
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 1);

        assertThatThrownBy(() -> fulfillmentService.ship(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已支付");
    }

    @Test
    @DisplayName("已支付订单发货后不能重复发货")
    void shipIsIdempotentOnlyOnce() {
        OrderVO order = paidOrder();

        assertThat(fulfillmentService.ship(order.getOrderNo())).isTrue();
        assertThat(orderService.findByOrderNo(order.getOrderNo()).getStatus())
                .isEqualTo(OrderStatus.SHIPPED);

        assertThatThrownBy(() -> fulfillmentService.ship(order.getOrderNo()))
                .as("重复发货会导致重复结算，必须拒绝")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("未发货不能确认收货；发货后确认收货置为已完成")
    void confirmRequiresShipped() {
        OrderVO order = paidOrder();

        assertThatThrownBy(() -> fulfillmentService.confirm(order.getOrderNo(), USER_ID))
                .isInstanceOf(BusinessException.class);

        fulfillmentService.ship(order.getOrderNo());
        assertThat(fulfillmentService.confirm(order.getOrderNo(), USER_ID)).isTrue();

        var finished = orderService.findByOrderNo(order.getOrderNo());
        assertThat(finished.getStatus()).isEqualTo(OrderStatus.FINISHED);
        assertThat(finished.getFinishTime()).as("完成时间必须落库，结算依赖它").isNotNull();
    }

    @Test
    @DisplayName("不能替别人确认收货")
    void cannotConfirmOthersOrder() {
        OrderVO order = paidOrder();
        fulfillmentService.ship(order.getOrderNo());

        assertThatThrownBy(() -> fulfillmentService.confirm(order.getOrderNo(), 999_999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    @Test
    @DisplayName("已发货订单不能直接退款（退货退款是另一条链路）")
    void shippedOrderCannotBeRefunded() {
        OrderVO order = paidOrder();
        fulfillmentService.ship(order.getOrderNo());

        assertThatThrownBy(() -> refundService.refund(order.getOrderNo(), "不想要了", USER_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("自动确认收货：发货超期未确认的订单会被任务完成")
    void autoConfirmFinishesExpiredShippedOrders() {
        OrderVO order = paidOrder();
        fulfillmentService.ship(order.getOrderNo());

        // 把发货时间回拨 30 天，模拟"用户一直没点确认"
        jdbcTemplate.update("""
                UPDATE t_order SET ship_time = DATE_SUB(NOW(3), INTERVAL 30 DAY)
                 WHERE order_no = ?
                """, order.getOrderNo());

        int finished = fulfillmentService.autoConfirmExpired(10);
        assertThat(finished).isEqualTo(1);
        assertThat(orderService.findByOrderNo(order.getOrderNo()).getStatus())
                .isEqualTo(OrderStatus.FINISHED);

        // 再跑一次不会重复计数（状态已经是 FINISHED）
        assertThat(fulfillmentService.autoConfirmExpired(10)).isZero();
    }
}
