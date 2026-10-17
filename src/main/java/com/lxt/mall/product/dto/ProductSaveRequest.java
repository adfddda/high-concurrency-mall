package com.lxt.mall.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSaveRequest {

    @NotBlank(message = "商品标题不能为空")
    @Size(max = 128, message = "标题过长")
    private String title;

    @Size(max = 256, message = "副标题过长")
    private String subTitle;

    @Size(max = 255, message = "封面地址过长")
    private String coverImg;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    /** 1 上架 / 0 下架 */
    private Integer status = 1;

    private String detail;
}
