package com.lxt.mall.admin;

import com.lxt.mall.cache.ProductCacheService;
import com.lxt.mall.common.api.Result;
import com.lxt.mall.mq.MessageSendTracker;
import com.lxt.mall.seckill.LocalSoldOutCache;
import com.lxt.mall.seckill.SeckillReconcileService;
import com.lxt.mall.seckill.SeckillStockService;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维 / 演示接口。
 *
 * <p>存在的价值：让"缓存命中率"、"Redis 与 DB 是否一致"、"有多少消息在途"
 * 这些内部状态<b>可以被直接观察</b>，而不是只能靠读代码推测。
 * 面试演示时能当场把数据拉出来，说服力远强于口头描述。
 *
 * <p>生产环境这些接口必须收敛到内网 + 角色鉴权，
 * 因为它们会暴露系统内部结构。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ProductCacheService productCacheService;
    private final SeckillStockService stockService;
    private final SeckillReconcileService reconcileService;
    private final MessageSendTracker sendTracker;
    private final LocalSoldOutCache localSoldOutCache;
    private final com.lxt.mall.order.FulfillmentService fulfillmentService;
    private final com.lxt.mall.seckill.ReservationService reservationService;
    private final com.lxt.mall.domain.mapper.SeckillRiskHitMapper riskHitMapper;
    private final com.lxt.mall.domain.mapper.SeckillActivityMapper activityMapper;
    private final com.lxt.mall.seckill.SeckillActivityService activityService;
    private final com.lxt.mall.seckill.SeckillPreheatService preheatService;
    private final java.time.Clock clock;

    /** 商品缓存的 L1 命中统计。 */
    @GetMapping("/cache/product/stats")
    public Result<Map<String, Object>> cacheStats() {
        CacheStats stats = productCacheService.localCacheStats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("l1RequestCount", stats.requestCount());
        result.put("l1HitCount", stats.hitCount());
        result.put("l1HitRate", String.format("%.2f%%", stats.hitRate() * 100));
        result.put("l1MissCount", stats.missCount());
        result.put("l1EvictionCount", stats.evictionCount());
        result.put("l1LoadSuccessCount", stats.loadSuccessCount());
        return Result.ok(result);
    }

    @PostMapping("/cache/product/local/flush")
    public Result<Void> flushLocalCache() {
        productCacheService.invalidateAllLocal();
        return Result.ok();
    }

    /**
     * 清除指定商品的两级缓存（L1 + L2）。
     *
     * <p>用途：
     * <ul>
     *   <li>运维手动修正脏缓存；</li>
     *   <li>让"缓存命中链路"的验证变得可重复——否则第二次跑冒烟脚本时
     *       L2 里还留着上次的数据，断言"首次应当回源数据库"必然失败。</li>
     * </ul>
     */
    @PostMapping("/cache/product/{productId}/evict")
    public Result<Void> evictProductCache(@PathVariable Long productId) {
        productCacheService.invalidate(productId);
        return Result.ok();
    }

    /** 查看某个活动的 Redis 侧状态（库存 / 在途 / 已购人数），用于验证预热结果。 */
    @GetMapping("/seckill/{activityId}/redis-state")
    public Result<Map<String, Object>> redisState(@PathVariable Long activityId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", activityId);
        result.put("redisStock", stockService.getStock(activityId));
        result.put("inflight", stockService.getInflight(activityId));
        result.put("boughtCount", stockService.boughtCount(activityId));
        result.put("localSoldOut", localSoldOutCache.isSoldOut(activityId));
        return Result.ok(result);
    }

    /**
     * 对账：对比 Redis 库存与数据库库存。
     *
     * <p>这是验证"最终一致性"是否真的做到了的核心接口：
     * 压测结束后调用一次，drift 应该恒为 0。
     */
    @GetMapping("/seckill/reconcile")
    public Result<List<SeckillReconcileService.ReconcileReport>> reconcile() {
        return Result.ok(reconcileService.reconcileAll());
    }

    /** 指定活动对账。{@code repair=false} 时只观测不修改，适合排查阶段使用。 */
    @GetMapping("/seckill/{activityId}/reconcile")
    public Result<SeckillReconcileService.ReconcileReport> reconcileOne(
            @PathVariable Long activityId,
            @RequestParam(defaultValue = "false") boolean repair) {
        return Result.ok(reconcileService.reconcileOne(activityId, repair));
    }

    /** 待确认投递数量。持续 > 0 说明 broker 或网络有问题。 */
    @GetMapping("/mq/pending")
    public Result<Map<String, Object>> pendingMessages() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingConfirms", sendTracker.pendingCount());
        return Result.ok(result);
    }

    /**
     * 发货（管理端）。
     *
     * <p>放在 {@code /api/admin/**} 下，自动被管理员拦截器保护——
     * 发货会触发商家结算与售后时效，绝不能由普通用户调用。
     */
    @PostMapping("/orders/{orderNo}/ship")
    public Result<Boolean> ship(@PathVariable String orderNo) {
        return Result.ok(fulfillmentService.ship(orderNo));
    }

    /**
     * 手动开奖（抽签活动）。
     *
     * <p>生产上由 {@code SeckillDrawJob} 在活动开始前自动触发；
     * 这个接口用于演示与补抽——运营在活动开始前发现中签率异常时可以手动重跑。
     *
     * @param ratio 中签名额 = 可售库存 × ratio，默认 1.0
     * @return 本次中签人数
     */
    @PostMapping("/seckill/{activityId}/draw")
    public Result<Integer> draw(@PathVariable Long activityId,
                                @RequestParam(defaultValue = "1.0") double ratio) {
        return Result.ok(reservationService.draw(activityId, ratio));
    }

    /**
     * 风控拦截统计。
     *
     * <p>回答"这套风控到底挡住了多少"：按原因分组返回拦截次数。
     * 面试演示时可以现场展示"同一设备第二单被拦"的数据。
     */
    @GetMapping("/seckill/{activityId}/risk-hits")
    public Result<List<Map<String, Object>>> riskHits(@PathVariable Long activityId) {
        return Result.ok(riskHitMapper.countByReason(activityId));
    }

    /**
     * 立即开抢（运营工具 / 演示用）。
     *
     * <p>把开始时间提前到"现在"。抽签活动必须先开奖、再开抢，
     * 一条命令就能把整条演示链路跑通，不用真的等十分钟。
     */
    @PostMapping("/seckill/{activityId}/open")
    public Result<Boolean> openNow(@PathVariable Long activityId) {
        int rows = activityMapper.openNow(activityId, java.time.LocalDateTime.now(clock));
        if (rows > 0) {
            // 本地快照缓存里还是旧的开始时间，必须失效
            activityService.evictLocal(activityId);
            // 只失效本地还不够：Redis 里的活动快照仍然是"未来开始"，
            // 秒杀入口读到的是旧时间，会直接返回"尚未开始"。
            // 所以开抢之后必须重新预热，让快照跟上新的开始时间。
            preheatService.preheat(activityId);
        }
        return Result.ok(rows > 0);
    }
}
