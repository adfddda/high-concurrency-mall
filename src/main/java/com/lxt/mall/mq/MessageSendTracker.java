package com.lxt.mall.mq;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.seckill.SeckillCompensationService;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 投递确认跟踪器。
 *
 * <h3>它解决的三个问题</h3>
 * <ol>
 *   <li><b>Confirm 回调只能拿到一个 CorrelationData</b>，拿不到业务上下文。
 *       把 messageId → 业务上下文 的映射登记在这里，回调触发时才知道
 *       "是哪一单、该回补哪个活动的库存"。</li>
 *   <li><b>Return 回调与 Confirm 回调是两条独立路径</b>，而且 basic.return
 *       <b>先于</b> basic.ack 到达。两条路径都可能触发补偿，
 *       真正的防重逻辑统一收敛在 {@link SeckillCompensationService}。</li>
 *   <li><b>broker 静默失联时 Confirm 回调永远不会来</b>。
 *       没有超时兜底，这次抢购就变成"Redis 扣了库存、订单永远不创建"，
 *       库存凭空消失。定时扫描补上这个缺口。</li>
 * </ol>
 *
 * <h3>为什么上下文放内存而不是数据库（重要权衡）</h3>
 * <p><b>方案 A（本项目）：内存台账 + 超时补偿 + 对账兜底</b>
 * <ul>
 *   <li>优点：秒杀热路径上<b>零额外数据库写入</b>。这是决定性的——
 *       秒杀接口能扛住高并发，正是因为把数据库操作全部异步化了，
 *       为了"可靠"再塞一次 INSERT 进去，等于自毁前提。</li>
 *   <li>缺点：进程崩溃会丢失未确认的上下文，Redis 库存已扣、订单没产生。</li>
 *   <li>兜底：{@code SeckillReconcileJob} 通过比对
 *       "Redis 库存 / DB 剩余库存 / 在途计数"把这类漂移修回来。
 *       也就是<b>用对账代替同步写库来保证最终一致</b>。</li>
 * </ul>
 * <p><b>方案 B：本地消息表（Outbox）</b>
 * 业务与消息记录同事务落库，强可靠，但热路径多一次数据库写入。
 * 它更适合"量不大但绝不能丢"的场景——本项目订单超时关单那条链路
 * 就用了类似的思路（落库 + 延迟消息 + 兜底扫描三重保障）。
 *
 * <h3>已知风险（面试可以主动提）</h3>
 * Spring AMQP 的 {@code CachingConnectionFactory} 会在内存里保存待确认的
 * CorrelationData。如果 broker 长时间不可达、confirm 一直不来，
 * 这个 Map 会持续增长直到 OOM。缓解手段：一是本类用<b>有界 + 有 TTL</b>
 * 的 Caffeine 自己管理上下文；二是定时扫描清掉超时条目并触发补偿；
 * 三是生产上必须对这个 Map 的大小做监控告警。
 */
@Slf4j
@Component
public class MessageSendTracker {

    private final Cache<String, PendingSend> pending;
    private final SeckillCompensationService compensationService;
    private final MallProperties properties;
    private final MallMetrics metrics;

    public MessageSendTracker(SeckillCompensationService compensationService,
                              MallProperties properties,
                              MallMetrics metrics) {
        this.compensationService = compensationService;
        this.properties = properties;
        this.metrics = metrics;

        // 30 分钟：远超任何合理的 confirm 延迟，同时保证内存占用有上界。
        // maximumSize 是第二道保险，防止极端情况下把堆撑爆。
        this.pending = Caffeine.newBuilder()
                .maximumSize(200_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
    }

    /** 登记一条待确认消息，返回携带 messageId 的 CorrelationData。 */
    public CorrelationData register(SeckillOrderMessage msg) {
        pending.put(msg.getMessageId(), PendingSend.of(msg));
        return new CorrelationData(msg.getMessageId());
    }

    /** Confirm 回调：broker 是否接收了消息。 */
    public void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
        String messageId = correlationData == null ? null : correlationData.getId();
        if (messageId == null) {
            log.warn("收到不带 CorrelationData 的 Confirm 回调，无法关联业务消息 ack={}", ack);
            return;
        }

        if (ack) {
            // ACK 只代表"broker 收下了"，不代表"路由到了队列"——
            // 路由失败由 Return 回调负责。两者必须都处理，可靠性链条才完整。
            pending.invalidate(messageId);
            metrics.mqEvent("seckill", "confirm_ack");
            return;
        }

        metrics.mqEvent("seckill", "confirm_nack");
        PendingSend send = pending.asMap().remove(messageId);
        compensate(send, "broker 拒绝接收(nack): " + cause);
    }

    /**
     * Return 回调：消息到达交换机但<b>无法路由到任何队列</b>。
     *
     * <p>broker 对这类消息是先 return、再 ack 的，所以补偿很可能发生在
     * Confirm 之前。补偿后 pending 里已无此记录，随后的 Confirm(ack=true)
     * 只是空操作，不会重复补偿。
     */
    public void onReturn(ReturnedMessage returned) {
        metrics.mqEvent("seckill", "return");
        String messageId = returned.getMessage().getMessageProperties().getMessageId();
        if (messageId == null) {
            log.error("Return 回调的消息缺少 messageId，无法补偿。replyText={}", returned.getReplyText());
            return;
        }
        PendingSend send = pending.getIfPresent(messageId);
        compensate(send, "路由失败(return): " + returned.getReplyText()
                + " exchange=" + returned.getExchange() + " routingKey=" + returned.getRoutingKey());
    }

    /**
     * 找出超过确认超时的待确认消息（broker 静默失联场景），
     * 由 {@code PendingMessageRetryJob} 周期调用。
     */
    public List<PendingSend> findTimedOut() {
        long timeout = properties.getMq().getConfirmTimeout().toMillis();
        List<PendingSend> timedOut = new ArrayList<>();
        for (PendingSend send : pending.asMap().values()) {
            if (send.isOlderThan(timeout)) {
                timedOut.add(send);
            }
        }
        return timedOut;
    }

    /** 供发送环节在抛异常时调用。 */
    public void compensateByMessageId(String messageId, String reason) {
        compensate(pending.asMap().remove(messageId), reason);
    }

    /**
     * 扫描并补偿所有"确认超时"的消息。
     *
     * <p>由 {@code PendingMessageRetryJob} 周期调用。
     * 这是"broker 静默失联"场景下唯一能兜住的机制——
     * 没有它，那些消息对应的库存会被永久占用，用户看不到任何结果。
     *
     * @return 本次补偿的消息数
     */
    public int compensateTimedOut() {
        List<PendingSend> timedOut = findTimedOut();
        for (PendingSend send : timedOut) {
            metrics.mqEvent("seckill", "confirm_timeout");
            compensate(pending.asMap().remove(send.getMessageId()),
                    "Confirm 超时未收到 broker 确认（超过 " + properties.getMq().getConfirmTimeout() + "）");
        }
        return timedOut.size();
    }

    public int pendingCount() {
        return (int) pending.estimatedSize();
    }

    private void compensate(PendingSend send, String reason) {
        if (send == null) {
            // 上下文已被清理（比如 confirm 已经 ACK 过），无需补偿
            log.debug("补偿时找不到待确认上下文，跳过 reason={}", reason);
            return;
        }
        compensationService.compensate(send.getPayload(), reason, true);
    }
}
