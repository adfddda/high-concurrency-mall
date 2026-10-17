package com.lxt.mall.mq;

import com.lxt.mall.order.message.OrderTimeoutMessage;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 实现：委托给 {@link ReliableMessageSender}。
 *
 * <p>这一层是"接口"与"可靠性实现"之间的薄适配。看起来多了一层，
 * 但它的价值在于：**业务代码从此只依赖 {@link MessagePublisher}**。
 * 将来要换 RocketMQ，只需要新增一个 {@code RocketMessagePublisher}，
 * 业务侧一行都不用改——这正是"依赖倒置"在真实项目里的样子。
 */
@Component
@RequiredArgsConstructor
public class RabbitMessagePublisher implements MessagePublisher {

    private final ReliableMessageSender sender;

    @Override
    public void sendSeckillOrder(SeckillOrderMessage message) {
        sender.sendSeckillOrder(message);
    }

    @Override
    public void sendOrderTimeout(OrderTimeoutMessage message) {
        sender.sendOrderTimeout(message);
    }
}
