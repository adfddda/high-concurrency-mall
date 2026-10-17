package com.lxt.mall.common.enums;

/** 限流算法。 */
public enum RateLimitAlgorithm {

    /**
     * 滑动窗口（ZSET）。精度最高，严格限制"任意 window 内的请求数"。
     * 代价是内存 O(窗口内请求数)。适合秒杀这类需要强力削峰的入口。
     */
    SLIDING_WINDOW,

    /**
     * 令牌桶（HASH）。内存 O(1)，允许突发流量。
     * 适合登录、查询这类"限制长期平均速率，但别把正常连点当攻击"的接口。
     */
    TOKEN_BUCKET
}
