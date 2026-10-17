package com.lxt.mall.order;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.mq.ReliableMessageSender;
import com.lxt.mall.order.event.OrderCreatedEvent;
import com.lxt.mall.order.message.OrderTimeoutMessage;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.seckill.SeckillResultService;
import com.lxt.mall.seckill.SeckillStockService;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单创建成功后的收尾动作（全部在事务提交之后执行）。
 *
 * <p>这三个动作的性质不一样，处理方式也不一样：
 * <ul>
 *   <li><b>归还在途计数</b>：只是监控指标，失败只记 WARN，绝不影响主流程</li>
 *   <li><b>写结果缓存</b>：影响用户体验（前端轮询结果），失败会退化成查库，
 *       也不会丢数据</li>
 *   <li><b>投递延迟关单消息</b>：<b>这个最重要</b>。消息丢了订单就永远不会
 *       被自动关闭，库存会被一直占用。所以它除了延迟消息之外，
 *       还有 {@code OrderTimeoutFallbackJob} 按 {@code expire_time} 扫描兜底——
 *       <b>任何"靠消息触发的关键动作"都必须有一个兜底的扫描任务</b>，
 *       因为消息系统只能做到"至少一次"，不能保证"一定送到"。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedListener {

    private final SeckillStockService stockService;
    private final SeckillResultService resultService;
    private final ReliableMessageSender messageSender;
    private final MallProperties properties;
    private final MallMetrics metrics;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCreated(OrderCreatedEvent event) {
        Order order = event.getOrder();
        SeckillOrderMessage msg = event.getSourceMessage();

        // 1. 归还在途计数
        try {
            stockService.inflightDec(order.getActivityId());
        } catch (Exception e) {
            log.warn("归还在途计数失败 activityId={}（不影响订单）", order.getActivityId(), e);
        }

        // 2. 写入"抢购成功"结果 + 订单快照，前端轮询能立刻拿到且不落库
        try {
            resultService.markSuccess(order.getOrderNo(), order.getUserId(), OrderVO.from(order));
        } catch (Exception e) {
            log.warn("写入秒杀成功结果失败 orderNo={}（轮询会回落到数据库）", order.getOrderNo(), e);
        }

        // 3. 投递延迟关单消息
        OrderTimeoutMessage timeoutMessage = new OrderTimeoutMessage(
                IdWorker.getIdStr(),
                order.getOrderNo(),
                order.getId(),
                order.getActivityId(),
                order.getUserId(),
                order.getQuantity(),
                msg == null ? null : msg.getTotalStock(),
                order.getExpireTime());
        messageSender.sendOrderTimeout(timeoutMessage);

        metrics.mqEvent("seckill", "order_created");
        log.debug("订单收尾完成 orderNo={} expireTime={} 超时配置={}",
                order.getOrderNo(), order.getExpireTime(), properties.getSeckill().getOrderExpire());
    }
}
