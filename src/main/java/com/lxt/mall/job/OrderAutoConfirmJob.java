package com.lxt.mall.job;

import com.lxt.mall.order.FulfillmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动确认收货任务。
 *
 * <p>为什么要按批处理（默认每次 200 单）而不是一次扫完：
 * 大促之后可能积压几十万笔待确认订单，一次性全捞出来会把内存和事务撑爆。
 * 批量处理 + 定时反复执行，是"长尾任务"的标准做法——
 * <b>能慢但不能抖</b>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.auto-confirm.enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderAutoConfirmJob {

    private static final int BATCH_SIZE = 200;

    private final FulfillmentService fulfillmentService;

    @Scheduled(fixedDelayString = "${mall.job.auto-confirm.interval:1h}")
    public void autoConfirm() {
        try {
            int finished = fulfillmentService.autoConfirmExpired(BATCH_SIZE);
            if (finished > 0) {
                log.info("自动确认收货任务完成一批：{} 笔", finished);
            }
        } catch (Exception e) {
            // 定时任务必须自己吞异常，否则调度线程会停止后续执行
            log.error("自动确认收货任务异常", e);
        }
    }
}
