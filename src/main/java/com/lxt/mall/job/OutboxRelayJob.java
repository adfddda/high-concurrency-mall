package com.lxt.mall.job;

import com.lxt.mall.mq.OutboxRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 投递任务。
 *
 * <p>2 秒一轮：延迟关单消息本身就带 TTL，投递晚 2 秒对业务没有影响；
 * 但间隔太长会让"MQ 恢复后消息堆积"时间变长，2 秒是个平衡点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayJob {

    private final OutboxRelayService outboxRelayService;

    @Scheduled(fixedDelayString = "${mall.job.outbox.interval:2000}")
    public void relay() {
        try {
            outboxRelayService.relayOnce();
        } catch (Exception e) {
            // 任务本身绝不能让异常逃逸：@Scheduled 吞异常但会停止后续触发
            log.error("Outbox 轮询异常", e);
        }
    }
}
