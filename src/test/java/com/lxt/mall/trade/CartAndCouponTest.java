package com.lxt.mall.trade;

import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.mapper.CouponMapper;
import com.lxt.mall.domain.mapper.CouponTemplateMapper;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.support.BaseIntegrationTest;
import com.lxt.mall.trade.dto.CartItemVO;
import com.lxt.mall.trade.dto.CouponVO;
import com.lxt.mall.trade.dto.PaymentVO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 购物车 + 优惠券测试。
 *
 * <p>两组用例关注点不同：
 * <ul>
 *   <li>购物车：<b>幂等累加</b>与<b>实时价格</b>；</li>
 *   <li>优惠券：<b>限量发放</b>与<b>一券一单</b>，以及退款后券要还回来。</li>
 * </ul>
 * 共同点是"有限资源的并发安全"，跟秒杀库存是同一类问题。
 */
@Slf4j
class CartAndCouponTest extends BaseIntegrationTest {

    private static final long SKU_ID = 940_001L;
    private static final long USER_ID = 830_001L;
    private static final BigDecimal PRICE = new BigDecimal("60.00");

    private static final long TEMPLATE_ID = 410_001L;
    private static final BigDecimal THRESHOLD = new BigDecimal("100.00");
    private static final BigDecimal DISCOUNT = new BigDecimal("20.00");

    @Autowired
    private CartService cartService;
    @Autowired
    private CouponService couponService;
    @Autowired
    private TradeService tradeService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private CouponMapper couponMapper;
    @Autowired
    private CouponTemplateMapper couponTemplateMapper;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("""
                INSERT INTO t_sku (id, product_id, sku_code, spec_json, price, stock, status)
                VALUES (?, ?, ?, ?, ?, 10, 1)
                ON DUPLICATE KEY UPDATE stock = 10, price = VALUES(price), status = 1
                """, SKU_ID, TEST_PRODUCT_ID, "SKU-CART-1", "{\"color\":\"blue\"}", PRICE);

        jdbcTemplate.update("""
                INSERT INTO t_coupon_template
                    (id, name, threshold_amount, discount_amount, total_count, issued_count,
                     per_user_limit, start_time, end_time, status)
                VALUES (?, '满100减20', ?, ?, 100, 0, 1,
                        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 30 DAY), 1)
                ON DUPLICATE KEY UPDATE
                    total_count = 100, issued_count = 0,
                    threshold_amount = VALUES(threshold_amount),
                    discount_amount = VALUES(discount_amount),
                    start_time = VALUES(start_time), end_time = VALUES(end_time), status = 1
                """, TEMPLATE_ID, THRESHOLD, DISCOUNT);
    }

    // ========================================================================
    // 购物车
    // ========================================================================

    @Test
    @DisplayName("加购幂等累加：重复加同一 SKU 只增数量，不产生第二行")
    void addToCartAccumulates() {
        cartService.add(USER_ID, SKU_ID, 1);
        CartItemVO vo = cartService.add(USER_ID, SKU_ID, 2);

        assertThat(vo.getQuantity()).isEqualTo(3);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_cart_item WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("购物车数量封顶 99")
    void cartQuantityIsCapped() {
        cartService.add(USER_ID, SKU_ID, 60);
        CartItemVO vo = cartService.add(USER_ID, SKU_ID, 60);

        assertThat(vo.getQuantity()).isEqualTo(99);
    }

    @Test
    @DisplayName("购物车价格是实时价格，不是加购时的快照")
    void cartUsesLivePrice() {
        cartService.add(USER_ID, SKU_ID, 1);
        // 商品调价
        jdbcTemplate.update("UPDATE t_sku SET price = 80.00 WHERE id = ?", SKU_ID);

        List<CartItemVO> items = cartService.list(USER_ID);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getPrice()).isEqualByComparingTo("80.00");
        assertThat(items.get(0).getSubtotal()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("库存不足时购物车条目标记为不可结算，但不会被删除")
    void cartMarksUnavailableWithoutDeleting() {
        cartService.add(USER_ID, SKU_ID, 5);
        jdbcTemplate.update("UPDATE t_sku SET stock = 1 WHERE id = ?", SKU_ID);

        List<CartItemVO> items = cartService.list(USER_ID);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getAvailable()).isFalse();
    }

    // ========================================================================
    // 优惠券
    // ========================================================================

    @Test
    @DisplayName("领券幂等：重复领取返回同一张券")
    void claimIsIdempotent() {
        CouponVO first = couponService.claim(TEMPLATE_ID, USER_ID);
        CouponVO second = couponService.claim(TEMPLATE_ID, USER_ID);

        assertThat(second.getId()).isEqualTo(first.getId());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_coupon WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("限量发放：券领完后按条件更新拒绝，不会超发")
    void claimStopsWhenExhausted() {
        jdbcTemplate.update("UPDATE t_coupon_template SET total_count = 1 WHERE id = ?", TEMPLATE_ID);

        couponService.claim(TEMPLATE_ID, USER_ID);

        assertThatThrownBy(() -> couponService.claim(TEMPLATE_ID, 830_002L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("领完");

        Integer issued = jdbcTemplate.queryForObject(
                "SELECT issued_count FROM t_coupon_template WHERE id = ?", Integer.class, TEMPLATE_ID);
        assertThat(issued).as("发行数不能超过总量").isEqualTo(1);
    }

    @Test
    @DisplayName("下单使用优惠券：应付金额 = 商品金额 − 优惠，券状态变为已使用")
    void orderAppliesCoupon() {
        CouponVO coupon = couponService.claim(TEMPLATE_ID, USER_ID);

        // 2 × 60 = 120，满 100 减 20 → 实付 100
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 2, coupon.getId());

        assertThat(order.getPayAmount()).isEqualByComparingTo("100.00");
        var saved = orderService.findByOrderNo(order.getOrderNo());
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(saved.getCouponId()).isEqualTo(coupon.getId());

        var used = couponMapper.selectById(coupon.getId());
        assertThat(used.getStatus()).isEqualTo(1);
        assertThat(used.getOrderNo()).isEqualTo(order.getOrderNo());
    }

    @Test
    @DisplayName("金额未达门槛：下单失败，库存与优惠券都必须回滚")
    void orderBelowThresholdRollsBackEverything() {
        CouponVO coupon = couponService.claim(TEMPLATE_ID, USER_ID);

        assertThatThrownBy(() -> tradeService.createOrder(USER_ID, SKU_ID, 1, coupon.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("门槛");

        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM t_sku WHERE id = ?", Integer.class, SKU_ID);
        assertThat(stock).as("失败的订单不能扣库存").isEqualTo(10);

        assertThat(couponMapper.selectById(coupon.getId()).getStatus())
                .as("失败的订单不能核销券").isZero();
    }

    @Test
    @DisplayName("退款后优惠券退回未使用")
    void refundRestoresCoupon() {
        CouponVO coupon = couponService.claim(TEMPLATE_ID, USER_ID);
        OrderVO order = tradeService.createOrder(USER_ID, SKU_ID, 2, coupon.getId());

        PaymentVO payment = paymentService.createPayment(order.getOrderNo(), USER_ID);
        paymentService.handleCallback(payment.getPaymentNo(), "CH-C-1", new BigDecimal("100.00"));
        refundService.refund(order.getOrderNo(), "测试退款", USER_ID);

        var restored = couponMapper.selectById(coupon.getId());
        assertThat(restored.getStatus()).as("退款必须把券还给用户").isZero();
        assertThat(restored.getOrderNo()).isNull();
    }
}
