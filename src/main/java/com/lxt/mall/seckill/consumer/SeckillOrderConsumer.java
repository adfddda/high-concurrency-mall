package com.lxt.mall.seckill.consumer;

import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.mq.ReliableMessageSender;
import com.lxt.mall.mq.exception.DuplicateMessageException;
import com.lxt.mall.mq.exception.DuplicateOrderException;
import com.lxt.mall.mq.exception.NonRetryableMessageException;
import com.lxt.mall.order.OrderCreationService;
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
 * 秒杀下单消费者。
 *
 * <h3>为什么要「手动 ACK」而不是自动 ACK</h3>
 * 自动 ACK（{@code acknowledge-mode=auto}）下，Spring 在监听方法<b>正常返回</b>时
 * 就确认消息，抛异常时按 {@code default-requeue-rejected} 决定是否重投。
 * 这在简单场景够用，但本项目需要区分至少五种结果：
 * <pre>
 *   幂等命中           → ACK（这是正常情况，不该重试）
 *   订单唯一键冲突      → 补偿后 ACK（重试一万次结果一样）
 *   业务不可重试失败    → 补偿后 nack(requeue=false) 进死信（需要人看）
 *   瞬时故障（DB 抖动） → 投递到延迟重试队列后 ACK
 *   重试耗尽           → nack(requeue=false) 进死信
 * </pre>
 * 自动 ACK 表达不了这么细的语义，只能手动控制。
 *
 * <h3>为什么 ACK 一定要放在 try 的最后一个语句</h3>
 * 如果先 ACK 再写结果缓存，缓存写失败时消息已经确认，重投不会发生，
 * 用户永远查不到结果。原则是：<b>所有可能失败的动作都必须发生在 ACK 之前</b>。
 * 反过来，ACK 之后也不能再做任何业务动作——那一瞬间消息已经不可恢复了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final OrderCreationService orderCreationService;
    private final SeckillCompensationService compensationService;
    private final ReliableMessageSender messageSender;
    private final MallProperties properties;
    private final MallMetrics metrics;

    @RabbitListener(queues = "#{mallProperties.mq.seckillQueue}")
    public void onSeckillOrder(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        SeckillOrderMessage payload = null;

        try {
            payload = parse(message);

            orderCreationService.createFromSeckill(payload);

            channel.basicAck(deliveryTag, false);
            metrics.mqEvent("seckill", "consume_success");

        } catch (DuplicateMessageException e) {
            // 幂等命中：这条消息之前已经成功处理过。
            // 这是"至少一次投递"语义下的正常现象，不是错误，直接 ACK。
            metrics.mqEvent("seckill", "consume_duplicate");
            log.info("重复消息，直接确认 messageId={}", e.getMessage());
            channel.basicAck(deliveryTag, false);

        } catch (DuplicateOrderException e) {
            // 订单唯一键冲突：用户已经有订单了。
            // 回补 Redis 库存（这一条消息的扣减是多余的），然后 ACK。
            // 重试没有意义——唯一键永远冲突。
            metrics.mqEvent("seckill", "consume_duplicate_order");
            log.error("订单唯一键冲突，回补 Redis 库存并确认消息: {}", e.getMessage());
            compensateQuietly(payload, "订单已存在（唯一键冲突）");
            channel.basicAck(deliveryTag, false);

        } catch (NonRetryableMessageException e) {
            // 业务性失败（DB 库存不足、活动不存在等），重试无意义。
            // 补偿后送进死信队列，保留现场供人工/对账处理。
            metrics.mqEvent("seckill", "consume_non_retryable");
            log.error("消息不可重试，补偿后送入死信: {}", e.getMessage());
            compensateQuietly(payload, e.getMessage());
            channel.basicNack(deliveryTag, false, false);

        } catch (Exception e) {
            if (payload == null) {
                // 消息体本身无法解析，重试永远不会成功。
                // 送死信而不是丢弃，保留现场用于定位是生产端 bug 还是人为误发。
                log.error("消息体解析失败，送入死信队列。messageId={}",
                        message.getMessageProperties().getMessageId(), e);
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            handleRetryable(message, deliveryTag, channel, e);
        }
    }

    /**
     * 可重试失败：投递到延迟重试队列，并确认原消息。
     *
     * <p>这里有个容易忽略的坑：<b>如果 republish 成功但 basicAck 失败</b>
     * （比如 channel 刚好断开），broker 会把原消息重投，同时重试队列里
     * 也有一份——同一条消息被处理两次。这正是幂等必须做到
     * "数据库唯一键级别"而不是"应用层 if 判断"的原因。
     */
    private void handleRetryable(Message message, long deliveryTag, Channel channel, Exception e)
            throws IOException {
        int retryCount = retryCountOf(message);
        int maxRetry = properties.getMq().getMaxRetry();

        if (retryCount < maxRetry) {
            log.warn("消息消费失败，准备第 {} 次重试 messageId={} cause={}",
                    retryCount + 1, message.getMessageProperties().getMessageId(), e.getMessage());
            try {
                messageSender.republish(message,
                        properties.getMq().getSeckillExchange(),
                        properties.getMq().getSeckillRetryRoutingKey(),
                        retryCount + 1);
                channel.basicAck(deliveryTag, false);
                metrics.mqEvent("seckill", "consume_retry");
            } catch (Exception republishError) {
                // 连重试队列都投不进去，说明 broker 本身有问题。
                // requeue=true 让 broker 保留消息，等连接恢复后重新投递，
                // 这条消息不会丢。
                log.error("投递重试队列失败，requeue 等待 broker 恢复 messageId={}",
                        message.getMessageProperties().getMessageId(), republishError);
                channel.basicNack(deliveryTag, false, true);
            }
            return;
        }

        // 重试耗尽：nack(requeue=false) 会触发队列上配置的 DLX，
        // 由 broker 把消息路由到死信队列——比应用自己 publish 到 DLQ 更可靠，
        // 因为它是 broker 内部完成的，不受应用崩溃影响。
        metrics.mqEvent("seckill", "consume_dlq");
        log.error("重试次数已达上限 {}，消息进入死信队列 messageId={}",
                maxRetry, message.getMessageProperties().getMessageId(), e);
        channel.basicNack(deliveryTag, false, false);
    }

    private void compensateQuietly(SeckillOrderMessage payload, String reason) {
        if (payload == null) {
            return;
        }
        try {
            compensationService.compensate(payload, reason, true);
        } catch (Exception e) {
            // 补偿失败不能影响消息确认：否则会陷入
            // "补偿失败 → 不确认 → 重投 → 补偿失败" 的死循环。
            // 补偿兜底交给对账任务。
            log.error("补偿执行异常，交由对账任务处理 orderNo={}", payload.getOrderNo(), e);
        }
    }

    private SeckillOrderMessage parse(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        return JsonUtils.fromJson(body, SeckillOrderMessage.class);
    }

    private int retryCountOf(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("retryCount");
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
