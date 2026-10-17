package com.lxt.mall.trade.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/** 购物车条目视图（已联好 SKU 的价格与库存）。 */
@Data
public class CartItemVO implements Serializable {

    private Long skuId;

    private Long productId;

    private String skuCode;

    private String specJson;

    private BigDecimal price;

    private Integer quantity;

    private Integer checked;

    /** 小计 = 当前单价 × 数量（实时价格，不是加购时的价格）。 */
    private BigDecimal subtotal;

    private Integer availableStock;

    /** 是否可结算：SKU 在售且库存充足。 */
    private Boolean available;
}
