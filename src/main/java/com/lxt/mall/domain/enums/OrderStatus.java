package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 订单状态。
 *
 * <p>状态机（只有这些流转是合法的，其余一律拒绝）：
 * <pre>
 *   CREATED(0) --支付成功--> PAID(1) --履约完成--> FINISHED(3)
 *   CREATED(0) --超时/用户取消--> CANCELED(2)
 * </pre>
 *
 * <p>所有状态流转都必须走「条件更新」：
 * {@code UPDATE t_order SET status = ? WHERE order_no = ? AND status = ?}，
 * 用影响行数判断是否竞争成功，绝不能先 SELECT 再 UPDATE。
 */
public enum OrderStatus {

    CREATED(0, "待支付"),
    PAID(1, "已支付"),
    CANCELED(2, "已取消"),
    FINISHED(3, "已完成");

    @EnumValue
    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static OrderStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知订单状态: " + code);
    }
}
