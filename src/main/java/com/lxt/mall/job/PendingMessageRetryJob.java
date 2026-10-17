package com.lxt.mall.job;

import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.mq.MessageSendTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Confirm 超时补偿任务。
 *
 * <h3>为什么必须有这个任务</h3>
 * 生产者的可靠性依赖三个回调，但它们都有一个共同的盲区：
 * <b>broker 静默失联时，回调一个都不会来</b>。
 * 此时 {@code PendingSend} 会一直留在内存里，对应的 Redis 库存
 * 已经被扣减，但订单永远不会创建——用户等不到结果，库存凭空消失。
 * 这个任务周期性地把这些"没人认领"的投递捞出来补偿掉。
 *
 * <h3>为什么扫描间隔是 2 秒</h3>
 * {@code confirmTimeout} 默认 5 秒，扫描间隔必须显著小于它，
 * 否则补偿会滞后得让人以为系统卡住了。2 秒的扫描成本极低
 * （只是遍历一个内存 Map），完全可以承受。
 *
 * <p>注意补偿本身是幂等的（{@code SeckillCompensationService} 三重防重），
 * 所以这个任务重复触发、或者说和 Confirm 回调"撞车"，都不会造成额外回补。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.pending-confirm.enabled", havingValue = "true", matchIfMissing = true)
public class PendingMessageRetryJob {

    private final MessageSendTracker sendTracker;
    private final MallMetrics metrics;

    @Scheduled(fixedDelayString = "${mall.job.pending-confirm.interval:2000}", initialDelay = 10_000)
    public void compensateTimedOutSends() {
        try {
            int pending = sendTracker.pendingCount();
            if (pending == 0) {
                return;
            }
            int compensated = sendTracker.compensateTimedOut();
            if (compensated > 0) {
                log.warn("Confirm 超时补偿完成：本次补偿 {} 条，当前待确认 {} 条", compensated, pending);
            }
        } catch (Exception e) {
            // 定时任务抛异常会导致后续调度被抑制（Spring 默认行为），
            // 所以必须自己兜住，保证任务永远活着。
            log.error("Confirm 超时补偿任务异常", e);
            metrics.mqEvent("seckill", "pending_job_error");
        }
    }
}
