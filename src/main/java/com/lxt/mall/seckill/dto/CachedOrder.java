package com.lxt.mall.seckill.dto;

import com.lxt.mall.order.dto.OrderVO;

import java.io.Serializable;

/**
 * 抢购结果的订单快照（缓存在 Redis，供轮询直接命中）。
 *
 * <p>带上 {@code userId} 是为了在缓存层就能完成归属校验：
 * 如果只缓存 {@link OrderVO}，返回给用户前还得查库确认"这单是不是他的"，
 * 缓存也就白做了。把归属信息一起缓存，越权校验和读加速一次完成。
 *
 * <p>用 record 而不是普通类：它是不可变的传输对象，
 * 且 Jackson 能直接反序列化 record（Java 16+），不需要无参构造。
 */
public record CachedOrder(Long userId, OrderVO order) implements Serializable {
}
