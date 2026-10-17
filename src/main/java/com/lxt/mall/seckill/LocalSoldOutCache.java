package com.lxt.mall.seckill;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lxt.mall.config.MallProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 本地售罄标记。
 *
 * <p><b>解决什么问题？</b>
 * 商品卖光之后，绝大多数请求注定失败。但对每一个失败请求，
 * 我们仍然要跑一次 Redis Lua——在秒杀场景下这就是几万 QPS 的无用 Redis 流量。
 * 在 JVM 内存里记一个"这个活动已经卖光"的标记，命中就直接返回，
 * 把这一整段流量挡在 Redis 之前。
 *
 * <p><b>它的正确性边界（面试必问）：</b>
 * <ul>
 *   <li>只是<b>快速否定</b>优化，不是权威数据。最终判定永远以 Redis Lua 为准。</li>
 *   <li>多实例之间<b>不同步</b>：A 实例标记售罄时，B 实例可能还在放请求。
 *       这没问题——放过去的请求会被 Redis 正确拒绝。</li>
 *   <li>反向不一致才是真问题：<b>库存被回补（超时关单/投递失败）后，
 *       本地标记必须被清掉</b>，否则本实例会一直拒绝用户，
 *       表现为"明明有库存却抢不到"。所以回补路径一定要调用 {@link #clear}。</li>
 *   <li>因此 TTL 是最后的安全网：即使某条回补路径漏了 clear，
 *       最多持续 {@code mall.seckill.local-soldout-ttl}（默认 10 秒）就自愈。
 *       用 TTL 兜底而不是靠"记得清"，是这类本地状态设计的通用原则。</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalSoldOutCache {

    private final Cache<Long, Boolean> soldOut;

    public LocalSoldOutCache(MallProperties properties) {
        MallProperties.Seckill seckill = properties.getSeckill();
        this.soldOut = Caffeine.newBuilder()
                .maximumSize(seckill.getLocalSoldoutMaxSize())
                .expireAfterWrite(seckill.getLocalSoldoutTtl().toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean isSoldOut(Long activityId) {
        return activityId != null && soldOut.getIfPresent(activityId) != null;
    }

    public void markSoldOut(Long activityId) {
        if (activityId != null) {
            soldOut.put(activityId, Boolean.TRUE);
            log.debug("活动 {} 已在本实例标记为售罄", activityId);
        }
    }

    public void clear(Long activityId) {
        if (activityId != null) {
            soldOut.invalidate(activityId);
        }
    }

    public void clearAll() {
        soldOut.invalidateAll();
    }
}
