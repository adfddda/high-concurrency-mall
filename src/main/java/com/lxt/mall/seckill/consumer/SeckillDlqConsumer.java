package com.lxt.mall.seckill.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.seckill.SeckillCompensationService;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 死信消费者（最后一道自动防线）。
 *
 * <h3>处理流程：先查订单状态，再决定是否回补</h3>
 * <pre>
 *   死信消息进来
 *        │
 *        ├─ 订单已落库 ──► 什么都不做，只记录
 *        │                （说明失败发生在 ACK 阶段，业务其实成功了）
 *        │
 *        └─ 订单未落库 ──► Lua 回补库存 + 回收购买资格 + 记录失败
 * </pre>
 *
 * <p><b>为什么"先查订单状态"这一步不能省？</b>
 * 死信不等于业务失败。消息可能已经成功创建了订单，只是在 basicAck
 * 那一刻 channel 断了，broker 于是把它当失败消息走了死信。
 * 如果死信消费者无脑回补库存，就会出现"订单在、库存也还回去了"——
 * 直接超卖。判断依据必须是<b>数据库里的真实结果</b>，而不是"消息进了死信"
 * 这个间接信号。
 *
 * <p>回补本身的多重防重（订单存在性检查 / SETNX 守卫 / Lua 总库存封顶）
 * 统一收敛在 {@link SeckillCompensationService}，这里不重复实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillDlqConsumer {

    private final OrderMapper orderMapper;
    private final SeckillCompensationService compensationService;
    private final MallMetrics metrics;

    @RabbitListener(queues = "#{mallProperties.mq.seckillDlq}")
    public void onDeadLetter(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = message.getMessageProperties().getMessageId();

        SeckillOrderMessage payload;
        try {
            payload = JsonUtils.fromJson(new String(message.getBody(), StandardCharsets.UTF_8),
                    SeckillOrderMessage.class);
        } catch (Exception e) {
            log.error("死信消息无法解析，丢弃。messageId={}", messageId, e);
            metrics.mqEvent("seckill", "dlq_unparsable");
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (payload == null || payload.getOrderNo() == null) {
            log.error("死信消息内容不完整，丢弃。messageId={}", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // ---- 关键：以数据库的真实结果为准 ----
            boolean orderExists = orderMapper.exists(
                    Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, payload.getOrderNo()));

            if (orderExists) {
                log.warn("死信消息对应的订单已存在，无需回补库存。orderNo={} messageId={}",
                        payload.getOrderNo(), messageId);
                metrics.mqEvent("seckill", "dlq_order_exists");
            } else {
                log.error("死信消息对应订单不存在，回补库存与购买资格。orderNo={} messageId={}",
                        payload.getOrderNo(), messageId);
                metrics.mqEvent("seckill", "dlq_compensate");
                compensationService.compensate(payload, "消息进入死信队列且订单未落库", true);
            }

            // 死信队列的消息确认后才会被删除，便于在控制台观察处理进展。
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 处理死信本身失败（最典型的是"数据库不可用，连订单状态都查不了"）。
            //
            // 这里【绝不能】ACK —— ACK 意味着消息被永久丢弃，而这次失败
            // 说明我们根本没能力判断订单到底有没有落库。把它丢掉的结果是：
            // 订单永远不会创建、库存不会归还、用户拿不到任何结果，
            // 而且没有任何痕迹（日志里只有一行 ERROR）。
            //
            // 正确做法是 requeue，等下游恢复后重新处理。
            // 代价是：如果消息本身是"毒丸"（比如结构性非法），会反复重投。
            // 为了不给 broker 造成瞬时自旋，这里先短暂退避再 nack。
            // 生产环境更完善的做法是给死信消息也加 retryCount 头，
            // 超过阈值后投递到一个"人工处理队列"（parking queue）并告警。
            log.error("处理死信消息失败，将 requeue 等待下游恢复。orderNo={} messageId={}",
                    payload.getOrderNo(), messageId, e);
            metrics.mqEvent("seckill", "dlq_requeue");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
