package com.lxt.mall.config;

import com.lxt.mall.mq.MessageSendTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 拓扑与可靠性配置。
 *
 * <h3>拓扑</h3>
 * <pre>
 *  ┌───────────────────────────── 秒杀下单链路 ─────────────────────────────┐
 *  │                                                                       │
 *  │  mall.seckill.exchange (direct)                                       │
 *  │        │ rk=seckill.order                                            │
 *  │        ▼                                                              │
 *  │  mall.seckill.order.queue ──消费失败(重试未耗尽)──► retry.queue        │
 *  │        │                                        (TTL=5s, DLX 回主队列)│
 *  │        │ 重试耗尽 / 业务不可重试                                        │
 *  │        ▼                                                              │
 *  │  mall.dlx.exchange ──► mall.seckill.order.dlq（死信，人工/自动补偿）    │
 *  └───────────────────────────────────────────────────────────────────────┘
 *
 *  ┌───────────────────────────── 订单超时关单 ─────────────────────────────┐
 *  │  mall.order.delay.exchange ──► mall.order.delay.queue                 │
 *  │                                 (TTL=订单超时时间, DLX 指向下一跳)      │
 *  │                                        │                              │
 *  │                                        ▼                              │
 *  │  mall.order.close.exchange ──► mall.order.close.queue                 │
 *  └───────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>三个必须讲清楚的设计点</h3>
 *
 * <p><b>1. 为什么重试用「延迟队列」而不是 {@code basicNack(requeue=true)}？</b>
 * requeue=true 会把消息立刻重新投回队首，如果失败原因是下游瞬时故障
 * （比如数据库连接池满了），立刻重投等于<b>零间隔自旋</b>，
 * 只会让下游更没机会恢复，还会把日志刷爆。
 * 用 TTL + DLX 构造的延迟队列，能让每次重试之间有确定的间隔（本项目 5 秒），
 * 给下游留出恢复时间。
 *
 * <p><b>2. 为什么用「队列级 TTL」而不是「消息级 TTL」？</b>
 * RabbitMQ 的<b>消息级 TTL 会造成队头阻塞</b>：队列只检查队头消息是否过期，
 * 一条 TTL=30 分钟的消息堵在队头，后面 TTL=5 秒的消息也得等它过期才能出来。
 * 本项目同一队列内所有消息的过期时间一致（订单超时时间统一），
 * 用队列级 TTL 既简单又没有阻塞问题。
 * 如果将来要支持"不同订单不同超时"，必须换成 rabbitmq_delayed_message_exchange
 * 插件或按超时档位拆分多个队列。
 *
 * <p><b>3. 为什么 {@code mandatory=true} + ReturnCallback 缺一不可？</b>
 * Confirm 只能告诉生产者"broker 收到了"，<b>不能</b>告诉你
 * "消息被路由到了队列"。如果 routingKey 写错、队列被误删，
 * broker 会 ack 这条消息然后直接丢弃它——生产者以为发送成功，
 * 消息却消失了。开启 mandatory 后，无法路由的消息会通过
 * basic.return 送回生产者，这才补上了可靠性链条的最后一环。
 * <b>注意 basic.return 会先于 basic.ack 到达</b>，回调实现必须能处理这个顺序。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final MallProperties properties;
    private final MessageSendTracker sendTracker;

    // ========================================================================
    // 秒杀下单链路
    // ========================================================================

    @Bean
    public DirectExchange seckillExchange() {
        // durable=true：broker 重启后交换机依然存在，
        // 否则重启会导致生产者发送失败、消费者订阅丢失。
        return new DirectExchange(properties.getMq().getSeckillExchange(), true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        Map<String, Object> args = new HashMap<>();
        // 没有消费者能处理、且重试耗尽的"毒消息"会被投到死信交换机，
        // 而不是无限 requeue 把队列堵死。
        args.put("x-dead-letter-exchange", properties.getMq().getDlxExchange());
        args.put("x-dead-letter-routing-key", properties.getMq().getSeckillDlqRoutingKey());
        return QueueBuilder.durable(properties.getMq().getSeckillQueue())
                .withArguments(args)
                .build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(properties.getMq().getSeckillRoutingKey());
    }

    /**
     * 延迟重试队列。
     *
     * <p>本身没有消费者：消息进来后"躺着"直到 TTL 到期，
     * 由 DLX 重新投回业务队列。这是用原生能力模拟延迟队列的标准做法，
     * 不需要安装额外插件。
     */
    @Bean
    public Queue seckillRetryQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", (int) properties.getMq().getRetryDelay().toMillis());
        args.put("x-dead-letter-exchange", properties.getMq().getSeckillExchange());
        args.put("x-dead-letter-routing-key", properties.getMq().getSeckillRoutingKey());
        return QueueBuilder.durable(properties.getMq().getSeckillRetryQueue())
                .withArguments(args)
                .build();
    }

    @Bean
    public Binding seckillRetryBinding() {
        // 重试队列挂在同一个 direct exchange 上，用不同的 routingKey 区分。
        return BindingBuilder.bind(seckillRetryQueue())
                .to(seckillExchange())
                .with(properties.getMq().getSeckillRetryRoutingKey());
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(properties.getMq().getDlxExchange(), true, false);
    }

    @Bean
    public Queue seckillDlq() {
        return QueueBuilder.durable(properties.getMq().getSeckillDlq()).build();
    }

    @Bean
    public Binding seckillDlqBinding() {
        return BindingBuilder.bind(seckillDlq())
                .to(dlxExchange())
                .with(properties.getMq().getSeckillDlqRoutingKey());
    }

    // ========================================================================
    // 订单超时关单链路
    // ========================================================================

    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(properties.getMq().getOrderDelayExchange(), true, false);
    }

    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", (int) properties.getSeckill().getOrderExpire().toMillis());
        args.put("x-dead-letter-exchange", properties.getMq().getOrderCloseExchange());
        args.put("x-dead-letter-routing-key", properties.getMq().getOrderCloseRoutingKey());
        return QueueBuilder.durable(properties.getMq().getOrderDelayQueue())
                .withArguments(args)
                .build();
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue())
                .to(orderDelayExchange())
                .with(properties.getMq().getOrderDelayRoutingKey());
    }

    @Bean
    public DirectExchange orderCloseExchange() {
        return new DirectExchange(properties.getMq().getOrderCloseExchange(), true, false);
    }

    @Bean
    public Queue orderCloseQueue() {
        return QueueBuilder.durable(properties.getMq().getOrderCloseQueue()).build();
    }

    @Bean
    public Binding orderCloseBinding() {
        return BindingBuilder.bind(orderCloseQueue())
                .to(orderCloseExchange())
                .with(properties.getMq().getOrderCloseRoutingKey());
    }

    // ========================================================================
    // 生产者可靠性回调
    // ========================================================================

    /**
     * 给自动装配出来的 RabbitTemplate 挂上 Confirm / Return 回调。
     *
     * <p>用 {@link RabbitTemplateCustomizer} 而不是自己 new 一个 RabbitTemplate：
     * 自动装配的 RabbitTemplate 已经绑好了连接工厂、消息转换器、
     * 重试策略、observation 等一大堆东西，自己 new 会把这些全部丢掉。
     * Customizer 是官方提供的、唯一"正确"的扩展点。
     */
    @Bean
    public RabbitTemplateCustomizer rabbitTemplateCustomizer() {
        return template -> {
            // 消息无法路由时通过 basic.return 回调通知生产者
            template.setMandatory(true);

            template.setConfirmCallback((correlationData, ack, cause) -> {
                try {
                    sendTracker.onConfirm(correlationData, ack, cause);
                } catch (Exception e) {
                    // 回调里抛异常会被 Spring AMQP 吞掉或打到日志，
                    // 但保险起见自己兜住，绝不能让回调异常影响连接。
                    log.error("处理 Confirm 回调异常", e);
                }
            });

            template.setReturnsCallback(returned -> {
                try {
                    sendTracker.onReturn(returned);
                } catch (Exception e) {
                    log.error("处理 Return 回调异常", e);
                }
            });
        };
    }
}
