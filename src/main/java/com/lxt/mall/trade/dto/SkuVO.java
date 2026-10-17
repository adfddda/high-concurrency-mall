package com.lxt.mall.trade.dto;

import com.lxt.mall.domain.entity.Sku;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/** SKU 视图。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkuVO implements Serializable {

    private Long id;

    private Long productId;

    private String skuCode;

    /** 规格 JSON，例如 {"color":"黑","storage":"512G"}。 */
    private String specJson;

    private BigDecimal price;

    private Integer stock;

    private Integer status;

    public static SkuVO from(Sku sku) {
        return new SkuVO(sku.getId(), sku.getProductId(), sku.getSkuCode(),
                sku.getSpecJson(), sku.getPrice(), sku.getStock(), sku.getStatus());
    }
}
