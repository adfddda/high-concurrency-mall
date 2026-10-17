package com.lxt.mall.trade;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.entity.Refund;
import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.domain.mapper.PaymentMapper;
import com.lxt.mall.domain.mapper.RefundMapper;
import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import com.lxt.mall.domain.mapper.SkuMapper;
import com.lxt.mall.seckill.SeckillStockService;
import com.lxt.mall.trade.dto.RefundVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 退款服务。
 *
 * <h3>退款的幂等为什么比支付更难</h3>
 * 支付是"加钱"，重复执行用户能感知（而且渠道侧会挡住）；
 * 退款是"还库存 + 退钱"，重复执行会导致<b>库存多还</b>——
 * 用户退了 1 件却还了 2 件库存，就是超卖。
 *
 * <p>这里用了三道防线：
 * <ol>
 *   <li>订单状态机 {@code PAID -> REFUNDED}，条件更新只有一个赢家；</li>
 *   <li>退款单 {@code uk_order_no}，同一订单最多一条退款记录；</li>
 *   <li>库存回补挂在"状态流转成功"的分支上，没抢到状态就不回补。</li>
 * </ol>
 *
 * <h3>退款金额从哪来</h3>
 * 用订单的 {@code pay_amount}，而不是让调用方传。金额由客户端决定
 * 是资金类接口的经典漏洞。演示项目支持全额退款；部分退款需要
 * {@code t_refund} 上再加"已退金额"聚合，属于下一步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundMapper refundMapper;
    private final OrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final SkuMapper skuMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public RefundVO refund(String orderNo, String reason, Long userId) {
        Order order = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该订单");
        }

        // 幂等：已经退过就直接返回原退款单
        Refund existing = refundMapper.selectOne(
                Wrappers.<Refund>lambdaQuery().eq(Refund::getOrderNo, orderNo));
        if (existing != null) {
            return RefundVO.from(existing);
        }

        // 状态机是唯一的并发闸门：PAID 才能退，且只有一个线程能改到行
        int rows = orderMapper.markRefunded(orderNo);
        if (rows == 0) {
            throw new BusinessException(ResultCode.REFUND_NOT_ALLOWED,
                    "只有已支付的订单可以退款，当前状态[" + order.getStatus().getDesc() + "]");
        }

        int quantity = order.getQuantity() == null ? 1 : order.getQuantity();
        if (order.getActivityId() != null) {
            // 秒杀订单：回补活动库存（DB 侧带上限），再回补 Redis 侧
            activityMapper.rollbackStock(order.getActivityId(), quantity);
            SeckillActivity activity = activityMapper.selectById(order.getActivityId());
            stockService.rollback(order.getActivityId(), order.getUserId(),
                    activity == null ? Integer.MAX_VALUE : activity.getTotalStock(),
                    // 退款不回收购买资格：用户确实买过，一人一单的语义不变
                    false, false);
        } else if (order.getSkuId() != null) {
            skuMapper.rollbackStock(order.getSkuId(), quantity);
        }

        paymentMapper.markRefunded(orderNo);

        Refund refund = new Refund();
        refund.setRefundNo("RF" + IdWorker.getIdStr());
        refund.setOrderNo(orderNo);
        refund.setUserId(order.getUserId());
        refund.setAmount(order.getPayAmount());
        refund.setReason(reason);
        refund.setStatus(1);
        refund.setRefundTime(LocalDateTime.now(clock));
        refundMapper.insert(refund);

        log.info("退款完成 orderNo={} refundNo={} amount={} 库存已回补 {} 件",
                orderNo, refund.getRefundNo(), refund.getAmount(), quantity);
        return RefundVO.from(refund);
    }
}
