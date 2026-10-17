package com.lxt.mall.order.event;

import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import org.springframework.context.ApplicationEvent;

/**
 * 订单创建成功事件。
 *
 * <p>订单落库之后要做的三件事都挂在这个事件上，并且都在<b>事务提交之后</b>执行：
 * <ol>
 *   <li>归还 Redis 在途计数</li>
 *   <li>把"抢购成功"写入结果缓存，让前端轮询能查到</li>
 *   <li>投递订单超时关单的延迟消息</li>
 * </ol>
 * 为什么必须 AFTER_COMMIT：如果事务最终回滚了，这三件事都变成"凭空发生"——
 * 在途计数白扣、前端看到"成功"但其实没有订单、还会给一个不存在的订单
 * 安排关单。这类"副作用先于事务结果"的 bug 极难复现，必须靠机制避免。
 */
public class OrderCreatedEvent extends ApplicationEvent {

    private final Order order;
    private final SeckillOrderMessage sourceMessage;

    public OrderCreatedEvent(Object source, Order order, SeckillOrderMessage sourceMessage) {
        super(source);
        this.order = order;
        this.sourceMessage = sourceMessage;
    }

    public Order getOrder() {
        return order;
    }

    public SeckillOrderMessage getSourceMessage() {
        return sourceMessage;
    }
}
