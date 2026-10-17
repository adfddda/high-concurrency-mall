package com.lxt.mall.seckill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.mq.MqMessageService;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 秒杀补偿（全链路唯一的补偿入口）。
 *
 * <p>把补偿收敛到一个类里，是因为它要处理的是整个系统里<b>最容易出错</b>的一段逻辑：
 * 库存已经扣了，但订单没成。补偿一旦重复执行或漏执行，后果分别是超卖和少卖。
 * 散落在多处实现必然会出现不一致，所以这里集中处理，并且明确三层防重：
 *
 * <h3>第一层：先确认订单确实不存在</h3>
 * 补偿的前提是"订单没落库"。但存在这样的窗口：
 * <pre>
 *   broker 已把消息路由给消费者 → 消费者成功创建订单 → 事务提交
 *   但 Confirm 帧丢失 / 超时才到达 → 超时扫描触发补偿
 * </pre>
 * 此时订单明明已经存在，若无条件回补库存，结果就是
 * <b>订单在、库存也还回去了 = 超卖</b>。
 * 所以补偿前必须查一次订单，这也正是死信消费者要做的同一件事。
 *
 * <h3>第二层：SETNX 一次性守卫</h3>
 * 同一个 messageId 的补偿可能从发送异常、Confirm nack、Return 回调、
 * 超时扫描、死信消费五个入口触发。用 Redis SETNX 保证只有一个能执行。
 *
 * <h3>第三层：Lua 侧的总库存封顶</h3>
 * 即使前两层都被绕过，回补脚本里 {@code newStock > totalStock 则取 totalStock}
 * 也保证库存不会超过总库存——把错误限制在"少卖"而不是"超卖"。
 * <b>宁可少卖，绝不超卖</b>，这是交易系统的第一原则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillCompensationService {

    private final OrderMapper orderMapper;
    private final SeckillStockService stockService;
    private final SeckillResultService resultService;
    private final MqMessageService mqMessageService;
    private final MallMetrics metrics;

    /**
     * 执行补偿。
     *
     * @param releaseQualification 是否回收用户的购买资格。
     *                             订单没落库时为 true（用户还可以重新抢）；
     *                             订单已经存在但因其它原因补偿时为 false。
     * @return true 表示本次真正执行了回补
     */
    public boolean compensate(SeckillOrderMessage msg, String reason, boolean releaseQualification) {
        if (msg == null || msg.getOrderNo() == null) {
            log.warn("补偿参数不完整，跳过 reason={}", reason);
            return false;
        }

        // ---- 第一层：订单已存在 → 不需要也不允许补偿 ----
        boolean orderExists = orderMapper.exists(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, msg.getOrderNo()));
        if (orderExists) {
            log.warn("订单已存在，跳过库存补偿（避免超卖）orderNo={} messageId={} reason={}",
                    msg.getOrderNo(), msg.getMessageId(), reason);
            metrics.mqEvent("seckill", "compensate_skipped_order_exists");
            return false;
        }

        // ---- 第二层：一次性守卫 ----
        if (!stockService.tryAcquireCompensateGuard(msg.getMessageId())) {
            log.info("该消息已完成过补偿，跳过 messageId={}", msg.getMessageId());
            metrics.mqEvent("seckill", "compensate_skipped_guard");
            return false;
        }

        metrics.mqEvent("seckill", "compensate");
        log.error("秒杀补偿开始 orderNo={} messageId={} activityId={} userId={} reason={}",
                msg.getOrderNo(), msg.getMessageId(), msg.getActivityId(), msg.getUserId(), reason);

        try {
            // ---- 第三层在 Lua 内部：总库存封顶 ----
            stockService.rollback(
                    msg.getActivityId(),
                    msg.getUserId(),
                    msg.getTotalStock() == null ? Integer.MAX_VALUE : msg.getTotalStock(),
                    releaseQualification,
                    true);
        } catch (Exception e) {
            // 补偿本身失败是最严重的情况：库存被永久占用，只能靠对账修复。
            // 这里必须用 ERROR 级别并带上完整上下文，方便人工介入。
            log.error("回补 Redis 库存失败！将由对账任务修复 orderNo={}", msg.getOrderNo(), e);
            metrics.mqEvent("seckill", "compensate_failed");
        }

        resultService.markFailed(msg.getOrderNo(), reason);
        mqMessageService.markConsumeFailed(msg.getMessageId(), reason);
        return true;
    }
}
