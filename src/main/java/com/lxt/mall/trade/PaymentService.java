package com.lxt.mall.trade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.entity.Payment;
import com.lxt.mall.domain.enums.OrderBizType;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.domain.enums.PaymentStatus;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.domain.mapper.PaymentMapper;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.trade.dto.PaymentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 支付服务（模拟渠道）。
 *
 * <h3>真实支付的两个核心问题，这里都正面处理了</h3>
 *
 * <p><b>1. 重复回调</b>：支付渠道的回调是"至少一次"，同一笔支付可能回调多次、
 * 甚至并发回调。处理方式不是加锁，而是<b>带前驱状态的条件 UPDATE</b>：
 * <pre>
 *   UPDATE t_payment SET status = 1 WHERE payment_no = ? AND status = 0
 * </pre>
 * 只有第一个请求能把 PENDING 改成 SUCCESS，其余全部拿到 0 行。
 * 拿到 0 行时再读一次，如果已经是 SUCCESS 就返回成功——这是"幂等"，
 * 不是"重复处理"。
 *
 * <p><b>2. 金额篡改</b>：回调里的金额必须与支付单金额比对，
 * 不能信任渠道传来的任何数字。金额不一致直接拒绝并告警。
 *
 * <h3>为什么先建支付单再跳转渠道</h3>
 * 支付单是我们自己的"待办事项"。先落库再唤起渠道，渠道回调时才有东西可对：
 * 没有支付单，回调回来只能凭订单号猜；有了支付单，paymentNo 就是幂等键。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final GroupBuyService groupBuyService;
    private final Clock clock;

    /** 发起支付：创建（或复用）支付单。 */
    @Transactional(rollbackFor = Exception.class)
    public PaymentVO createPayment(String orderNo, Long userId) {
        Order order = findOrder(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该订单");
        }

        // uk_order_no 保证一个订单只有一笔支付单：重复点击"去支付"时直接复用，
        // 而不是生成第二笔——否则用户可能被扣两次钱。
        Payment existing = paymentMapper.selectOne(
                Wrappers.<Payment>lambdaQuery().eq(Payment::getOrderNo, orderNo));
        if (existing != null) {
            return PaymentVO.from(existing);
        }

        if (order.getStatus() != OrderStatus.CREATED) {
            throw new BusinessException(ResultCode.ORDER_STATUS_CONFLICT,
                    "订单当前状态为[" + order.getStatus().getDesc() + "]，无法发起支付");
        }

        Payment payment = new Payment();
        payment.setPaymentNo("PY" + IdWorker.getIdStr());
        payment.setOrderNo(orderNo);
        payment.setChannel(1);
        payment.setAmount(order.getPayAmount());
        payment.setStatus(PaymentStatus.PENDING);
        paymentMapper.insert(payment);

        log.info("支付单已创建 paymentNo={} orderNo={} amount={}",
                payment.getPaymentNo(), orderNo, payment.getAmount());
        return PaymentVO.from(payment);
    }

    /**
     * 渠道回调。
     *
     * <p>真实实现里这里第一步应该是<b>验签</b>（RSA/SM2），验签失败直接拒绝。
     * 演示项目省略了验签，但保留了金额校验和幂等语义——这两件事
     * 即使没有真实渠道也必须做对。
     *
     * @return true 表示支付单最终处于成功状态（包括"之前已经成功"）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handleCallback(String paymentNo, String channelTradeNo, BigDecimal amount) {
        Payment payment = paymentMapper.selectOne(
                Wrappers.<Payment>lambdaQuery().eq(Payment::getPaymentNo, paymentNo));
        if (payment == null) {
            throw new BusinessException(ResultCode.PAYMENT_NOT_FOUND);
        }

        // 已经成功：渠道重试或网络重发，直接返回成功，不重复记账
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.info("支付回调重复抵达，幂等返回成功 paymentNo={}", paymentNo);
            return true;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(ResultCode.PAYMENT_STATUS_CONFLICT,
                    "支付单当前状态为[" + payment.getStatus().getDesc() + "]");
        }

        if (amount == null || amount.compareTo(payment.getAmount()) != 0) {
            log.error("支付金额不一致，拒绝回调 paymentNo={} 期望={} 实际={}",
                    paymentNo, payment.getAmount(), amount);
            throw new BusinessException(ResultCode.PAYMENT_AMOUNT_MISMATCH);
        }

        int rows = paymentMapper.markSuccess(paymentNo, channelTradeNo, LocalDateTime.now(clock));
        if (rows == 0) {
            // 并发回调：另一个线程抢先改成功了
            Payment latest = paymentMapper.selectOne(
                    Wrappers.<Payment>lambdaQuery().eq(Payment::getPaymentNo, paymentNo));
            return latest != null && latest.getStatus() == PaymentStatus.SUCCESS;
        }

        // 支付单落定后再推进订单状态。
        // OrderService.pay 内部同样是条件更新，重复调用是幂等的。
        try {
            orderService.pay(payment.getOrderNo());
        } catch (BusinessException e) {
            // 订单在支付过程中被超时关单了：真实系统会发起原路退回。
            // 这里显式记录，让对账/告警能看到这类"付了钱但订单已关"的单子。
            log.error("支付成功但订单已不可支付，需人工退款 paymentNo={} orderNo={}",
                    paymentNo, payment.getOrderNo(), e);
            throw e;
        }

        // 拼团订单：支付成功要推进"成团"判定。
        // 放在这里而不是订单状态机里，是因为"成团"是拼团域的概念，
        // 订单本身不知道自己属于哪个团。
        Order paidOrder = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, payment.getOrderNo()));
        if (paidOrder != null && OrderBizType.of(paidOrder.getBizType()) == OrderBizType.GROUP) {
            groupBuyService.onMemberPaid(payment.getOrderNo());
        }
        return true;
    }

    private Order findOrder(String orderNo) {
        Order order = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
