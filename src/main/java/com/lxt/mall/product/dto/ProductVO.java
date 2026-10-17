package com.lxt.mall.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/** 商品列表项（不含详情大字段）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String subTitle;
    private String coverImg;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
}
