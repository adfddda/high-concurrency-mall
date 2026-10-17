package com.lxt.mall.trade.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 普通下单请求。 */
@Data
public class CreateOrderRequest {

    @NotNull(message = "skuId 不能为空")
    private Long skuId;

    @Min(value = 1, message = "购买数量至少为 1")
    @Max(value = 99, message = "单次最多购买 99 件")
    private Integer quantity = 1;
}
