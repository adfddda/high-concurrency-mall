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
    FINISHED(3, "已完成"),
    /**
     * 已退款。
     *
     * <p>注意它和"已取消"的区别：取消发生在支付之前（订单作废，库存回补），
     * 退款发生在支付之后（钱退了，货不能发）。两者都会回补库存，
     * 但触发的业务链路完全不同，所以必须是两个状态。
     */
    REFUNDED(4, "已退款"),
    /**
     * 已发货。
     *
     * <p>引入它的原因：{@code PAID} 和 {@code FINISHED} 之间原本是断开的——
     * 支付完成就停在"已支付"，没有任何代码会把订单推进到"已完成"，
     * 于是 {@code FINISHED} 成了一个永远不可达的死状态。
     * 履约链路补上后，状态机才真正闭合：
     * <pre>
     *   CREATED → PAID → SHIPPED → FINISHED
     *      ↓        ↓
     *   CANCELED  REFUNDED
     * </pre>
     */
    SHIPPED(5, "已发货");

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
