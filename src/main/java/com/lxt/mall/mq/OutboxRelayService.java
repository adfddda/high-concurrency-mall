package com.lxt.mall.mq;

import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.domain.entity.OutboxMessage;
import com.lxt.mall.domain.mapper.OutboxMessageMapper;
import com.lxt.mall.order.message.OrderTimeoutMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 投递器：把"数据库里已提交的待发消息"真正送到 MQ。
 *
 * <h3>它解决的是一个事务边界问题</h3>
 * "订单落库"在 MySQL，"延迟关单消息投递"在 RabbitMQ——两者不可能在同一个事务里。
 * 如果先提交订单、再发消息，进程在中间崩溃就丢消息：订单永远不会被自动关掉，
 * 库存在整个活动周期里被占用。
 *
 * <p>改成 Outbox 之后，<b>"消息待发送"这件事由数据库事务保证</b>：
 * 订单提交成功 ⇒ outbox 记录一定在；订单回滚 ⇒ 一定不在。
 * 剩下的事交给这个轮询任务，它失败可以重试、可以人工排查。
 *
 * <h3>为什么 markSent 在 publish 之后而不是等 Confirm</h3>
 * 投递语义是<b>至少一次</b>：宁可重复投递（消费端有幂等），不可漏投。
 * 如果等 Confirm 才置 1，Confirm 永不返回时消息会被反复重投；
 * 而当前顺序下最坏情况是"已发送但由于进程崩溃没置 1，重启后重发一次"——
 * 由消费端幂等兜住。**重复的成本远低于丢失。**
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    /** 单轮最多取多少条，避免一次把内存和 broker 打满。 */
    private static final int BATCH = 200;

    /** 重试上限：超过就置为 2（重试耗尽），等人看，而不是无限刷。 */
    private static final int MAX_RETRY = 5;

    private final OutboxMessageMapper outboxMapper;
    private final MessagePublisher messagePublisher;
    private final Clock clock;

    /**
     * 投递一轮，返回成功条数。
     *
     * <p>每条消息独立处理：一条发送失败不影响同批其它消息，
     * 否则一条毒丸会让整个队列卡住。
     */
    public int relayOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<OutboxMessage> pending = outboxMapper.selectPending(now, BATCH);
        int sent = 0;
        for (OutboxMessage message : pending) {
            try {
                OrderTimeoutMessage payload =
                        JsonUtils.fromJson(message.getPayload(), OrderTimeoutMessage.class);
                messagePublisher.sendOrderTimeout(payload);
                outboxMapper.markSent(message.getId());
                sent++;
            } catch (Exception e) {
                int retry = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
                // 线性退避：5s, 10s, 15s… 简单够用；生产可换指数退避
                outboxMapper.markFailed(message.getId(), now.plusSeconds(5L * retry), MAX_RETRY,
                        truncate(e.toString()));
                log.warn("Outbox 投递失败 messageId={} retry={} err={}",
                        message.getMessageId(), retry, e.toString());
            }
        }
        if (sent > 0) {
            log.debug("Outbox 本轮投递成功 {} 条", sent);
        }
        return sent;
    }

    private String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
