package com.lxt.mall.seckill;

import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import com.lxt.mall.metrics.MallMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秒杀库存对账。
 *
 * <h3>不变式（Invariant）</h3>
 * <pre>
 *   稳态（无在途请求）下：   Redis 库存  ==  DB available_stock
 *   有在途请求时：           Redis 库存  ==  DB available_stock - 在途数
 * </pre>
 * 推导：每一次成功的抢购，Redis 先减 1（进入在途），订单落库后 DB 也减 1；
 * 每一次超时关单或补偿，两边同时加回 1。所以差值只可能来自"在途"。
 *
 * <h3>为什么在途计数既是修正项，也是泄漏探测器</h3>
 * 在途计数有一个麻烦的性质：它会<b>掩盖</b>故障。
 * 如果进程在"Redis 已扣减、消息还没确认"时崩溃，Redis 库存永久少了 1，
 * 而在途计数也永久多了 1——两者相减正好抵消，{@code drift} 依然是 0，
 * 单纯比对库存根本发现不了。
 *
 * <p>破局点在于：<b>在途是瞬态量</b>。正常业务下，从扣减到落库只有几十毫秒，
 * 每 5 分钟采样一次，几乎不可能采到"在途 > 0"。所以
 * <b>连续多次采样都看到在途 > 0，就等价于发现了泄漏</b>。
 * 这时把库存对齐到 DB、在途清零，完成修复。
 *
 * <h3>修复方向为什么是"以 DB 为准"</h3>
 * 数据库有唯一索引、有事务、有 CHECK 约束，是唯一能自我保证正确性的一层。
 * Redis 里的库存只是一份可以被重建的投影。所以漂移时永远是把
 * <b>Redis 对齐到 DB</b>，而不是反过来——反过来会把已经发生的错误
 * "固化"进权威数据源。
 *
 * <h3>为什么默认只告警、不自动修</h3>
 * 自动修复的前提是"对账逻辑本身是对的"。如果对账逻辑有 bug，
 * 自动修复会把一个数据错误放大成持续的数据破坏。
 * 所以生产环境建议先跑一段时间只告警，确认 drift 恒为 0 之后再开启 auto-repair。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillReconcileService {

    /** 连续多少次采样看到"在途 > 0"就判定为泄漏。 */
    private static final int STALE_INFLIGHT_THRESHOLD = 3;

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final SeckillActivityService activityService;
    private final MallProperties properties;
    private final MallMetrics metrics;
    private final Clock clock;

    /** activityId -> 连续观察到"在途 > 0"的次数（进程内统计，重启归零）。 */
    private final Map<Long, Integer> staleInflightCounter = new ConcurrentHashMap<>();

    public record ReconcileReport(Long activityId,
                                  String activityName,
                                  Integer redisStock,
                                  Integer inflight,
                                  Integer dbAvailableStock,
                                  int drift,
                                  boolean consistent,
                                  boolean repaired,
                                  String note) {
    }

    /** 对账全部"进行中"的活动。 */
    public List<ReconcileReport> reconcileAll() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SeckillActivity> activities = activityMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SeckillActivity>lambdaQuery()
                        .gt(SeckillActivity::getEndTime, now));

        List<ReconcileReport> reports = new ArrayList<>(activities.size());
        for (SeckillActivity activity : activities) {
            try {
                reports.add(reconcile(activity, properties.getReconcile().isAutoRepair()));
            } catch (Exception e) {
                log.error("对账失败 activityId={}", activity.getId(), e);
            }
        }
        return reports;
    }

    /** 对账单个活动（观测接口用）。 */
    public ReconcileReport reconcileOne(Long activityId, boolean autoRepair) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new com.lxt.mall.common.exception.BusinessException(
                    com.lxt.mall.common.api.ResultCode.ACTIVITY_NOT_FOUND);
        }
        return reconcile(activity, autoRepair);
    }

    public ReconcileReport reconcile(SeckillActivity activity, boolean autoRepair) {        Long activityId = activity.getId();
        Integer redisStock = stockService.getStock(activityId);
        if (redisStock == null) {
            // 没有预热过的活动不参与对账（它不是"漂移"，而是"还没开始服务"）
            return new ReconcileReport(activityId, activity.getActivityName(), null, null,
                    activity.getAvailableStock(), 0, true, false, "未预热，跳过");
        }

        Integer inflight = stockService.getInflight(activityId);
        int dbAvailable = activity.getAvailableStock();
        int expected = dbAvailable - inflight;
        int drift = redisStock - expected;

        // 容忍度：活动进行中时，Redis 与 DB 之间本来就可能有一两个请求的抖动
        // （例如某次扣减刚提交、在途计数还没归零）。零容忍会让对账把正常抖动
        // 误判成故障，进而触发一次本不该发生的"自动修复"。
        int tolerance = Math.abs(properties.getReconcile().getDriftTolerance());
        boolean consistent = Math.abs(drift) <= tolerance;
        boolean repaired = false;
        String note = consistent
                ? (drift == 0 ? "一致" : "在容忍范围内（drift=" + drift + "，tolerance=" + tolerance + "）")
                : "检测到漂移";

        if (consistent) {
            staleInflightCounter.remove(activityId);
        }

        // ---------- 检查一：库存漂移 ----------
        if (!consistent) {
            metrics.reconcileDrift(activityId, drift);
            log.error("【库存对账】发现漂移 activityId={} redis={} db={} inflight={} drift={}",
                    activityId, redisStock, dbAvailable, inflight, drift);
            if (autoRepair && properties.getReconcile().isAutoRepair()) {
                repair(activityId, expected, activity, "库存漂移修复");
                repaired = true;
                note = "已修复：Redis 对齐到 DB";
            }
        }

        // ---------- 检查二：在途泄漏 ----------
        // 这是最隐蔽的一类故障：drift 恒为 0，但实际有库存被永久占用。
        if (inflight > 0) {
            int streak = staleInflightCounter.merge(activityId, 1, Integer::sum);
            if (streak >= STALE_INFLIGHT_THRESHOLD) {
                log.error("【库存对账】在途计数疑似泄漏 activityId={} inflight={} 连续采样次数={}，"
                                + "强制对齐到 DB 库存 {}",
                        activityId, inflight, streak, dbAvailable);
                metrics.reconcileDrift(activityId, -inflight);
                if (autoRepair && properties.getReconcile().isAutoRepair()) {
                    repair(activityId, dbAvailable, activity, "在途计数泄漏修复");
                    repaired = true;
                    note = "已修复：在途计数泄漏，库存对齐到 DB";
                }
                staleInflightCounter.remove(activityId);
            } else {
                note = note + "（在途=" + inflight + "，第 " + streak + " 次采样）";
            }
        }

        return new ReconcileReport(activityId, activity.getActivityName(), redisStock, inflight,
                dbAvailable, drift, consistent, repaired, note);
    }

    private void repair(Long activityId, int targetStock, SeckillActivity activity, String reason) {
        try {
            Duration ttl = Duration.between(LocalDateTime.now(clock), activity.getEndTime());
            if (ttl.isNegative() || ttl.toMinutes() < 10) {
                ttl = Duration.ofMinutes(30);
            } else {
                ttl = ttl.plusHours(1);
            }
            stockService.resetInflight(activityId);
            stockService.resetStock(activityId, Math.max(targetStock, 0), ttl);
            // 库存被改写后，本地的"售罄"标记必须清掉，否则会继续错误地拒绝请求。
            // 但这里【绝不能】调用 preheat()：预热写的是 DB 的 available_stock，
            // 而 targetStock = DB 库存 - 在途数（存在合法在途时会小于 DB 库存）。
            // 一旦预热，刚算好的预留量会被 DB 值覆盖，等于把还没落库的库存在
            // 秒杀入口重新放出去 —— 这正是"对账修了等于没修"的那个 bug。
            activityService.evictLocal(activityId);
            log.warn("【库存对账】{} activityId={} 目标库存={}（已清本地快照，不回写 DB 值）",
                    reason, activityId, targetStock);
        } catch (Exception e) {
            log.error("对账修复失败 activityId={} targetStock={}", activityId, targetStock, e);
        }
    }
}
