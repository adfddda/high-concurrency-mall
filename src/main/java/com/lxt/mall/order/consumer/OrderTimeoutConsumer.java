package com.lxt.mall.order.consumer;

import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.order.message.OrderTimeoutMessage;
import com.lxt.mall.seckill.SeckillStockService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 订单超时关单消费者。
 *
 * <pre>
 *   延迟队列(TTL=orderExpire) ──DLX──► 本消费者
 *        │
 *        ├─ 条件更新 status: 0 → 2   （只有一个人能成功）
 *        │       │
 *        │       ├─ 成功 → 回补 DB 库存 + 流水，提交后再回补 Redis 库存
 *        │       └─ 失败 → 订单已支付或已取消，什么都不做
 *        └─ ACK
 * </pre>
 *
 * <h3>为什么"回补 Redis"要放在数据库事务提交之后</h3>
 * Redis 不参与数据库事务。如果把它放在事务内：
 * <pre>
 *   回补 Redis 库存 → 后续语句失败 → 数据库回滚 → 但 Redis 已经加回去了
 *   结果：库存凭空多出来 → 超卖
 * </pre>
 * 反过来（先提交数据库、再回补 Redis）最坏情况是 Redis 没来得及加回去，
 * 这时对账任务会把库存修回来。<b>两种失败模式的后果完全不对称</b>：
 * 一个导致超卖（资损），一个导致少卖（可由对账自愈）。工程上永远选后者。
 *
 * <h3>为什么不重试</h3>
 * 关单消息不是唯一保障——{@code OrderTimeoutFallbackJob} 会按
 * {@code expire_time} 扫描兜底。所以这里的消息只是一条"加速通道"，
 * 处理失败时直接拒绝（丢弃）并告警，比无限重投更安全。
 * <b>凡是"消息触发的关键动作"，都必须有一个扫描兜底</b>，
 * 因为消息系统的语义是"至少一次"而不是"一定送达"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer {

    private final OrderService orderService;
    private final SeckillStockService stockService;
    private final MallMetrics metrics;

    @RabbitListener(queues = "#{mallProperties.mq.orderCloseQueue}")
    public void onOrderTimeout(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        OrderTimeoutMessage payload;
        try {
            payload = JsonUtils.fromJson(new String(message.getBody(), StandardCharsets.UTF_8),
                    OrderTimeoutMessage.class);
        } catch (Exception e) {
            log.error("超时关单消息解析失败，丢弃", e);
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (payload == null || payload.getOrderNo() == null) {
            log.error("超时关单消息内容不完整，丢弃");
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            OrderService.CancelResult result = orderService.cancelIfUnpaid(
                    payload.getOrderNo(), "超时未支付自动关单");

            if (result.canceled()) {
                // 数据库事务已提交，现在回补 Redis 库存。
                // releaseQualification=false：一人一单是硬规则，
                // 资格不回收，否则用户会在 Redis 通过、在 DB 撞唯一键。
                // decInflight=false：订单早已落库，在途计数在下单成功时就归还过了。
                stockService.rollback(result.activityId(), result.userId(),
                        result.totalStock() == null ? Integer.MAX_VALUE : result.totalStock(),
                        false, false);
                metrics.mqEvent("order-timeout", "closed");
                log.info("订单超时关单完成 orderNo={} 库存已归还", payload.getOrderNo());
            } else {
                // 订单已支付 / 已取消：这是并发下的正常分支
                metrics.mqEvent("order-timeout", "skipped");
            }
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            metrics.mqEvent("order-timeout", "error");
            log.error("处理超时关单失败，交由兜底扫描任务处理 orderNo={}", payload.getOrderNo(), e);
            // requeue=false：没有配置死信交换机的队列会直接丢弃。
            // 这是有意的——关单有扫描任务兜底，不需要在这里重试。
            // 用 nack 而不是 ack，是为了让 broker 的统计里能看到这条消息被拒绝了。
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
