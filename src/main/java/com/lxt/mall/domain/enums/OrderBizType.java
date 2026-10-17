package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 订单来源。
 *
 * <p>两种来源的差异只有两点，但这两点决定了两条完全不同的链路：
 * <ul>
 *   <li><b>库存从哪扣</b>：秒杀扣活动库存（Redis 预扣 + DB 条件更新），
 *       普通订单直接扣 SKU 库存（单条条件 UPDATE，同步事务）；</li>
 *   <li><b>是否受活动约束</b>：秒杀有"一人一单 + 时间窗口 + 限购"，
 *       普通订单没有。</li>
 * </ul>
 * 把它们放进同一张订单表、用 {@code biz_type} 区分，是为了让支付、退款、
 * 履约这些<b>与来源无关</b>的逻辑只写一遍。
 */
public enum OrderBizType {

    SECKILL(0, "秒杀"),
    NORMAL(1, "普通");

    @EnumValue
    private final int code;
    private final String desc;

    OrderBizType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static OrderBizType of(Integer code) {
        if (code == null) {
            return SECKILL;
        }
        for (OrderBizType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知订单来源: " + code);
    }
}
