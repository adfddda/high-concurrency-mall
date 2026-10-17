package com.lxt.mall.trade;

import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.GroupBuy;
import com.lxt.mall.domain.entity.GroupBuyMember;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.domain.mapper.GroupBuyMapper;
import com.lxt.mall.domain.mapper.GroupBuyMemberMapper;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.support.BaseIntegrationTest;
import com.lxt.mall.trade.dto.PaymentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 拼团测试。
 *
 * <p>三条主线：<b>成团判定只发生一次</b>、<b>重复参团被拒绝</b>、
 * <b>到期失败后资金与库存都要收拾干净</b>。
 */
class GroupBuyTest extends BaseIntegrationTest {

    private static final long SKU_ID = 960_001L;
    private static final long LEADER = 840_001L;
    private static final long MEMBER2 = 840_002L;
    private static final long MEMBER3 = 840_003L;
    private static final BigDecimal PRICE = new BigDecimal("30.00");

    @Autowired
    private GroupBuyService groupBuyService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private GroupBuyMapper groupBuyMapper;
    @Autowired
    private GroupBuyMemberMapper memberMapper;

    @BeforeEach
    void prepareSku() {
        jdbcTemplate.update("""
                INSERT INTO t_sku (id, product_id, sku_code, spec_json, price, stock, status)
                VALUES (?, ?, ?, ?, ?, 10, 1)
                ON DUPLICATE KEY UPDATE stock = 10, price = VALUES(price), status = 1
                """, SKU_ID, TEST_PRODUCT_ID, "SKU-GROUP-1", "{\"color\":\"green\"}", PRICE);
    }

    private int skuStock() {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM t_sku WHERE id = ?", Integer.class, SKU_ID);
        return stock == null ? -1 : stock;
    }

    private void pay(String orderNo) {
        PaymentVO payment = paymentService.createPayment(orderNo, null);
        paymentService.handleCallback(payment.getPaymentNo(), "CH-G-" + orderNo, PRICE);
    }

    @Test
    @DisplayName("开团：创建团 + 团长订单 + 成员记录，并扣减库存")
    void openCreatesGroupAndOrder() {
        GroupBuyService.GroupBuyResult result = groupBuyService.open(LEADER, SKU_ID, 1);

        assertThat(result.groupId()).isNotNull();
        assertThat(result.orderNo()).startsWith("NO");
        assertThat(skuStock()).isEqualTo(9);

        GroupBuy group = groupBuyMapper.selectById(result.groupId());
        assertThat(group.getStatus()).isZero();
        assertThat(group.getPaidCount()).isZero();
        assertThat(group.getRequiredMembers()).isEqualTo(3);

        GroupBuyMember member = memberMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<GroupBuyMember>lambdaQuery()
                        .eq(GroupBuyMember::getGroupId, result.groupId()));
        assertThat(member.getUserId()).isEqualTo(LEADER);
        assertThat(member.getStatus()).isZero();
    }

    @Test
    @DisplayName("同一用户不能重复参团")
    void duplicateJoinIsRejected() {
        GroupBuyService.GroupBuyResult result = groupBuyService.open(LEADER, SKU_ID, 1);

        assertThatThrownBy(() -> groupBuyService.join(LEADER, result.groupId(), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已参加");
    }

    @Test
    @DisplayName("三人全部支付后自动成团，且支付回调重复不会重复计数")
    void groupCompletesWhenAllMembersPaid() {
        GroupBuyService.GroupBuyResult first = groupBuyService.open(LEADER, SKU_ID, 1);
        Long groupId = first.groupId();
        String order2 = groupBuyService.join(MEMBER2, groupId, 1).orderNo();
        String order3 = groupBuyService.join(MEMBER3, groupId, 1).orderNo();

        pay(first.orderNo());
        pay(order2);
        pay(order3);

        GroupBuy group = groupBuyMapper.selectById(groupId);
        assertThat(group.getStatus()).as("三人支付完成后应自动成团").isEqualTo(1);
        assertThat(group.getPaidCount()).isEqualTo(3);
        assertThat(group.getCompleteTime()).isNotNull();

        // 重复回调：不应把人数再加一次
        groupBuyService.onMemberPaid(order3);
        assertThat(groupBuyMapper.selectById(groupId).getPaidCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("到期未成团：未支付订单被关闭，库存回补，团置为失败")
    void expiredGroupCancelsUnpaidOrders() {
        GroupBuyService.GroupBuyResult first = groupBuyService.open(LEADER, SKU_ID, 1);
        groupBuyService.join(MEMBER2, first.groupId(), 1);
        assertThat(skuStock()).isEqualTo(8);

        expireNow(first.groupId());
        int closed = groupBuyService.closeExpired(10);

        assertThat(closed).isEqualTo(1);
        assertThat(groupBuyMapper.selectById(first.groupId()).getStatus()).isEqualTo(2);
        assertThat(orderService.findByOrderNo(first.orderNo()).getStatus())
                .isEqualTo(OrderStatus.CANCELED);
        assertThat(skuStock()).as("未支付订单关单后库存必须回补").isEqualTo(10);
    }

    @Test
    @DisplayName("到期未成团：已支付成员自动退款，库存与订单状态都正确")
    void expiredGroupRefundsPaidMembers() {
        GroupBuyService.GroupBuyResult first = groupBuyService.open(LEADER, SKU_ID, 1);
        groupBuyService.join(MEMBER2, first.groupId(), 1);

        pay(first.orderNo());
        assertThat(groupBuyMapper.selectById(first.groupId()).getPaidCount()).isEqualTo(1);

        expireNow(first.groupId());
        groupBuyService.closeExpired(10);

        assertThat(orderService.findByOrderNo(first.orderNo()).getStatus())
                .as("拼团失败，已支付订单必须退款").isEqualTo(OrderStatus.REFUNDED);
        assertThat(skuStock()).as("退款回补库存").isEqualTo(10);

        List<GroupBuyMember> members = memberMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<GroupBuyMember>lambdaQuery()
                        .eq(GroupBuyMember::getGroupId, first.groupId()));
        assertThat(members).anyMatch(m -> m.getStatus() == 2);
    }

    private void expireNow(Long groupId) {
        jdbcTemplate.update("""
                UPDATE t_group_buy SET expire_time = DATE_SUB(NOW(3), INTERVAL 1 MINUTE)
                 WHERE id = ?
                """, groupId);
    }
}
