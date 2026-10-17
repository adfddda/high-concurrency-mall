package com.lxt.mall.seckill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Product;
import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.domain.mapper.ProductMapper;
import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动预热。
 *
 * <h3>预热到底预热了什么</h3>
 * <pre>
 *   seckill:stock:{act:N}       ← 剩余库存（Lua 原子 DECR 的目标）
 *   seckill:activity:N          ← 活动 + 商品快照（热路径不碰数据库）
 *   seckill:bought:{act:N}      ← 已购集合的 TTL（资格判定不能提前失效）
 * </pre>
 *
 * <h3>为什么必须"提前"预热</h3>
 * 秒杀的特点是<b>瞬时并发</b>：活动开始的那一秒可能有几万个请求同时到达。
 * 如果第一个请求才去建缓存，那么这一秒内所有请求都会去查数据库建缓存——
 * 缓存不是被"用"坏的，是被"建"坏的。提前预热把这份成本挪到了
 * 流量到来之前，活动开始时 Redis 里已经万事俱备。
 *
 * <h3>为什么预热只写 available_stock 而不是 total_stock</h3>
 * 重新预热（比如活动进行中修数据、实例重启后重新加载）时，
 * available_stock 才是当前真实剩余量。用 total_stock 会把已经卖出去的
 * 库存"复活"，直接导致超卖。首次预热时两者本来就相等，所以统一用
 * available_stock 既正确又不需要区分两套逻辑。
 *
 * <h3>为什么预热不清理已购集合</h3>
 * 同理：活动中重新预热时清空已购集合，会让所有买过的用户重新获得资格，
 * 然后他们会在数据库层撞 {@code uk_user_activity} 唯一键，
 * 触发大量无意义的补偿。活动 ID 是一次性的，不存在复用旧集合的问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillPreheatService {

    private final SeckillActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final SeckillStockService stockService;
    private final SeckillActivityService activityService;
    private final MallProperties properties;
    private final Clock clock;

    /**
     * 预热单个活动。
     *
     * @return true 表示执行了预热
     */
    public boolean preheat(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            log.warn("预热失败，活动不存在 activityId={}", activityId);
            return false;
        }

        Duration ttl = ttlFor(activity);

        // 1. 活动快照（含商品标题、价格）
        Product product = productMapper.selectById(activity.getProductId());
        SeckillActivitySnapshot snapshot = new SeckillActivitySnapshot(
                activity.getId(), activity.getActivityName(), activity.getProductId(),
                product == null ? "未知商品" : product.getTitle(),
                activity.getSeckillPrice(),
                product == null ? null : product.getPrice(),
                activity.getTotalStock(), activity.getStartTime(), activity.getEndTime(),
                activity.getStatus(), LocalDateTime.now(clock));
        stockService.setActivitySnapshot(activityId, JsonUtils.toJson(snapshot), ttl);

        // 2. 库存
        stockService.preheatStock(activityId, activity.getAvailableStock(), ttl);

        // 3. 已购集合的 TTL：必须覆盖活动剩余周期，
        //    否则活动还没结束、资格判定就已经失效，用户能重复抢购。
        Duration boughtTtl = max(properties.getSeckill().getBoughtSetTtl(), ttl);
        stockService.setBoughtSetExpire(activityId, boughtTtl);

        // 4. 清掉本地缓存，让新快照立刻对热路径可见
        activityService.evictLocal(activityId);

        log.info("活动预热完成 activityId={} 剩余库存={} TTL={}分钟",
                activityId, activity.getAvailableStock(), ttl.toMinutes());
        return true;
    }

    /**
     * 预热即将开始 / 正在进行中的活动。启动时与定时任务调用。
     *
     * @param ahead 提前多久开始预热
     * @return 预热的活动数量
     */
    public int preheatUpcoming(Duration ahead) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SeckillActivity> activities = activityMapper.selectList(
                Wrappers.<SeckillActivity>lambdaQuery()
                        .gt(SeckillActivity::getEndTime, now)
                        .lt(SeckillActivity::getStartTime, now.plus(ahead))
                        .orderByAsc(SeckillActivity::getStartTime));

        int count = 0;
        for (SeckillActivity activity : activities) {
            try {
                if (preheat(activity.getId())) {
                    count++;
                }
            } catch (Exception e) {
                // 单个活动预热失败不能影响其它活动
                log.error("预热活动失败 activityId={}", activity.getId(), e);
            }
        }
        if (count > 0) {
            log.info("本轮共预热 {} 个活动（窗口 {}）", count, ahead);
        }
        return count;
    }

    private Duration ttlFor(SeckillActivity activity) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 多留 1 小时缓冲：活动结束瞬间可能还有正在处理的请求，
        // 缓存提前消失会让这些请求失去保护、一起打到数据库。
        Duration remaining = Duration.between(now, activity.getEndTime()).plusHours(1);
        return remaining.isNegative() || remaining.toMinutes() < 30 ? Duration.ofHours(1) : remaining;
    }

    private Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
