package com.lxt.mall.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.Duration;

/**
 * 带「逻辑过期时间」的缓存值包装。
 *
 * <p><b>为什么需要它？</b>
 * 常规的 Redis TTL 是「物理过期」：时间一到，key 直接消失。
 * 消失的那一瞬间，所有并发请求同时扑向数据库——这就是缓存击穿。
 * 加互斥锁能解决，但锁意味着大量请求要排队等待重建完成，
 * 对一个热门商品的详情接口来说，等于把 Redis 的 QPS 能力
 * 直接退化成数据库的 QPS 能力。
 *
 * <p>「逻辑过期」换了个思路：<b>Redis 里的 key 永远不过期</b>（物理 TTL 设得很长），
 * 过期信息由我们自己在 value 里记。判定为逻辑过期时：
 * <ol>
 *   <li>当前请求<b>立刻返回旧数据</b>（用户拿到的是稍旧但可用的商品信息）</li>
 *   <li>只有拿到互斥锁的那一个请求去异步重建缓存</li>
 * </ol>
 * 结果是：重建期间没有请求阻塞在锁上，也没有请求打到数据库，
 * 用极小的数据新鲜度代价换来了完全的可用性。
 *
 * <p>代价（面试一定会追问）：<b>会读到脏数据</b>，
 * 而且脏数据的持续时间取决于重建耗时。所以这套方案只适用于
 * 「能容忍秒级延迟」的读多写少场景（商品详情、活动信息），
 * 绝不能用在库存、余额这类强一致字段上。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheValue<T> {

    /** 真正的业务数据。 */
    private T data;

    /** 逻辑过期时间点（epoch millis）。 */
    private long logicalExpireAt;

    public static <T> CacheValue<T> of(T data, Duration logicalTtl, Clock clock) {
        return new CacheValue<>(data, clock.millis() + logicalTtl.toMillis());
    }

    public boolean logicallyExpired(Clock clock) {
        return clock.millis() >= logicalExpireAt;
    }
}
