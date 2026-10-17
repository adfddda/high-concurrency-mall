package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 库存变更类型。配合 t_stock_flow 的 uk_order_change 唯一键实现防重复回补。 */
public enum StockChangeType {

    DEDUCT(1, "扣减"),
    ROLLBACK(2, "回补");

    @EnumValue
    private final int code;
    private final String desc;

    StockChangeType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
