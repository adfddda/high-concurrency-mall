package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 支付单状态。
 *
 * <p>状态流转只有四条合法路径：
 * <pre>
 *   PENDING --渠道回调成功--> SUCCESS --退款--> REFUNDED
 *   PENDING --超时/用户放弃--> CLOSED
 * </pre>
 * 所有流转都必须走「带前驱状态的条件 UPDATE」，靠影响行数判断竞争结果——
 * 这与订单状态机是同一套方法论：<b>不要把判断和执行分成两步</b>。
 */
public enum PaymentStatus {

    PENDING(0, "待支付"),
    SUCCESS(1, "支付成功"),
    CLOSED(2, "已关闭"),
    REFUNDED(3, "已退款");

    @EnumValue
    private final int code;
    private final String desc;

    PaymentStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static PaymentStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (PaymentStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知支付状态: " + code);
    }
}
