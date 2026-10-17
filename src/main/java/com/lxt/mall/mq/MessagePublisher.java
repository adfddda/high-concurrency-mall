package com.lxt.mall.mq;

import com.lxt.mall.order.message.OrderTimeoutMessage;
import com.lxt.mall.seckill.message.SeckillOrderMessage;

/**
 * 消息发布口（对业务层唯一的依赖）。
 *
 * <h3>为什么要这层接口</h3>
 * 业务代码（{@code SeckillService}、{@code OrderCreatedListener}）真正关心的只有
 * "把这条消息可靠地发出去"，而不是"用 RabbitMQ 发"。把这层抽出来后：
 * <ul>
 *   <li>换 MQ（RocketMQ / Kafka）时只动实现类，业务代码零改动；</li>
 *   <li>测试里可以注入一个假实现，不需要起 broker；</li>
 *   <li>可靠投递的语义（Confirm / Return / 超时扫描）被收敛在实现里，
 *       不会泄漏到业务分支。</li>
 * </ul>
 *
 * <p><b>接口刻意只描述业务动作</b>（{@code sendSeckillOrder}），
 * 而不是 {@code send(exchange, routingKey, payload)}。
 * 后者会把交换机、路由键这些消息中间件概念泄漏给业务层——
 * 那样抽象等于没做。
 *
 * <p>实现必须保证：<b>方法抛异常 = 投递失败，且失败后的补偿已由实现或调用方处理</b>。
 * 这是 {@code SeckillService} 里 catch 后直接回补库存的依据。
 */
public interface MessagePublisher {

    /** 投递秒杀下单消息。 */
    void sendSeckillOrder(SeckillOrderMessage message);

    /** 投递订单超时关单消息（延迟队列）。 */
    void sendOrderTimeout(OrderTimeoutMessage message);
}
