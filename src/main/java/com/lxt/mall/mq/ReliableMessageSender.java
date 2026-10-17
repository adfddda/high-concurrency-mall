package com.lxt.mall.mq;

import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.order.message.OrderTimeoutMessage;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 可靠消息发送器。
 *
 * <h3>为什么消息体用 JSON 字符串，而不是让框架做对象序列化</h3>
 * {@code Jackson2JsonMessageConverter} 会在消息头里写入
 * {@code __TypeId__ = com.lxt.mall.seckill.message.SeckillOrderMessage}。
 * 这带来两个长期隐患：
 * <ol>
 *   <li><b>类名与消息格式被绑定死</b>。将来把消息类挪个包、改个名，
 *       所有还没消费完的旧消息立刻反序列化失败——而 MQ 里积压的旧消息
 *       可能存活几天。</li>
 *   <li><b>反序列化任意类型的攻击面</b>。消息头里的类型是生产者写的，
 *       消费端如果无脑信任，配合白名单缺失就是一个远程代码执行入口
 *       （Spring AMQP 的历史 CVE 大多与它相关）。</li>
 * </ol>
 * 自己用 {@code JsonUtils} 控制序列化，消息体就是一份纯粹的、自描述的 JSON，
 * 消费端显式指定目标类型。跨语言、跨版本都更稳。
 *
 * <h3>消息属性</h3>
 * <ul>
 *   <li>{@code messageId} —— 幂等与补偿的锚点，必须设置</li>
 *   <li>{@code persistent} —— 持久化，broker 重启不丢</li>
 *   <li>{@code contentType=application/json} —— 让控制台/工具能正确展示</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableMessageSender {

    private final RabbitTemplate rabbitTemplate;
    private final MessageSendTracker sendTracker;
    private final MallProperties properties;
    private final MallMetrics metrics;

    /**
     * 发送秒杀下单消息。
     *
     * <p>投递结果<b>不在这里同步等待</b>，而是通过 Confirm/Return 回调 +
     * 超时扫描异步收敛。这是有意为之：秒杀接口的 RT 必须与 broker 无关，
     * 等一次 confirm 就意味着 RT 增加一个网络往返。
     */
    public void sendSeckillOrder(SeckillOrderMessage msg) {
        MallProperties.Mq mq = properties.getMq();
        CorrelationData correlationData = sendTracker.register(msg);
        try {
            rabbitTemplate.convertAndSend(mq.getSeckillExchange(), mq.getSeckillRoutingKey(),
                    JsonUtils.toJson(msg),
                    message -> {
                        applyCommonProperties(message, msg.getMessageId());
                        message.getMessageProperties().setHeader("bizType", "SECKILL_ORDER");
                        message.getMessageProperties().setHeader("retryCount", 0);
                        return message;
                    },
                    correlationData);
            metrics.mqEvent("seckill", "send");
        } catch (Exception e) {
            // 发送本身抛异常（连接不可用、序列化失败等）：
            // 消息确定没有发出去，立刻补偿，不能等 Confirm 超时——
            // 用户等着结果，等 5 秒再失败体验太差。
            metrics.mqEvent("seckill", "send_exception");
            log.error("秒杀消息发送异常，立即补偿 messageId={} orderNo={}",
                    msg.getMessageId(), msg.getOrderNo(), e);
            sendTracker.compensateByMessageId(msg.getMessageId(), "生产端发送异常: " + e.getMessage());
            throw new IllegalStateException("消息发送失败", e);
        }
    }

    /** 发送订单超时关单延迟消息。 */
    public void sendOrderTimeout(OrderTimeoutMessage msg) {
        MallProperties.Mq mq = properties.getMq();
        try {
            rabbitTemplate.convertAndSend(mq.getOrderDelayExchange(), mq.getOrderDelayRoutingKey(),
                    JsonUtils.toJson(msg),
                    message -> {
                        applyCommonProperties(message, msg.getMessageId());
                        message.getMessageProperties().setHeader("bizType", "ORDER_TIMEOUT");
                        return message;
                    });
            metrics.mqEvent("order-timeout", "send");
        } catch (Exception e) {
            // 延迟消息发不出去不会导致资损：订单会一直停在待支付状态。
            // 有 OrderTimeoutFallbackJob 按 expire_time 扫描兜底关闭，
            // 所以这里只记录，不影响下单主流程。
            metrics.mqEvent("order-timeout", "send_exception");
            log.error("订单超时消息发送失败，将由兜底扫描任务处理 orderNo={}", msg.getOrderNo(), e);
        }
    }

    /**
     * 重新投递（用于延迟重试 / 死信）。
     *
     * <p>关键点：<b>保持原 messageId 不变</b>。
     * 消费幂等依赖 messageId，换了 ID 就等于把同一条消息变成一条新消息，
     * 幂等直接失效。重试次数用消息头 {@code retryCount} 传递。
     */
    public void republish(Message original, String exchange, String routingKey, int retryCount) {
        byte[] body = original.getBody();
        MessageProperties originalProps = original.getMessageProperties();
        String messageId = originalProps.getMessageId();
        if (messageId == null) {
            messageId = UUID.randomUUID().toString().replace("-", "");
        }

        final String finalMessageId = messageId;
        final String bizType = String.valueOf(originalProps.getHeaders().getOrDefault("bizType", "UNKNOWN"));
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey,
                    new String(body, StandardCharsets.UTF_8),
                    message -> {
                        applyCommonProperties(message, finalMessageId);
                        message.getMessageProperties().setHeader("bizType", bizType);
                        message.getMessageProperties().setHeader("retryCount", retryCount);
                        return message;
                    });
            metrics.mqEvent(bizType.toLowerCase(), "republish");
        } catch (Exception e) {
            log.error("重新投递失败 messageId={} exchange={} routingKey={}", finalMessageId, exchange, routingKey, e);
            throw new IllegalStateException("重新投递失败", e);
        }
    }

    private void applyCommonProperties(Message message, String messageId) {
        MessageProperties props = message.getMessageProperties();
        props.setMessageId(messageId);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        // 持久化：默认是 PERSISTENT，但显式写出来是为了防止有人改配置后静默变成非持久化
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    }
}
