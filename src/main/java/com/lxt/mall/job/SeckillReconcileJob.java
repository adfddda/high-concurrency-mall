package com.lxt.mall.job;

import com.lxt.mall.seckill.SeckillReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 库存对账任务。
 *
 * <p>这是整个最终一致性方案的<b>最后一道防线</b>。
 * 前面所有的补偿逻辑（Confirm 回调、超时扫描、死信处理）都建立在
 * "进程还活着、代码逻辑没 bug"的前提上；而对账任务建立在
 * "数据库是权威、Redis 是可重建投影"这个更弱也更可靠的前提上。
 *
 * <p>换句话说：补偿逻辑负责把 99% 的异常处理掉，
 * 对账任务负责兜住剩下 1%——包括进程崩溃、代码 bug、
 * 有人直接连 Redis 改了数据这些补偿逻辑根本覆盖不到的情况。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class SeckillReconcileJob {

    private final SeckillReconcileService reconcileService;

    @Scheduled(cron = "${mall.reconcile.cron:0 */5 * * * ?}")
    public void reconcile() {
        try {
            List<SeckillReconcileService.ReconcileReport> reports = reconcileService.reconcileAll();
            if (reports.isEmpty()) {
                return;
            }
            long inconsistent = reports.stream().filter(r -> !r.consistent()).count();
            long repaired = reports.stream().filter(SeckillReconcileService.ReconcileReport::repaired).count();

            if (inconsistent == 0) {
                log.debug("库存对账完成，{} 个活动全部一致", reports.size());
                return;
            }
            log.warn("库存对账完成：检查 {} 个活动，发现 {} 个不一致，修复 {} 个",
                    reports.size(), inconsistent, repaired);
            reports.stream().filter(r -> !r.consistent())
                    .forEach(r -> log.warn("  活动 {} ({}): redis={} db={} inflight={} drift={} note={}",
                            r.activityId(), r.activityName(), r.redisStock(),
                            r.dbAvailableStock(), r.inflight(), r.drift(), r.note()));
        } catch (Exception e) {
            log.error("库存对账任务异常", e);
        }
    }
}
