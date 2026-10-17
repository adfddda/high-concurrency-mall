package com.lxt.mall.product.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品详情视图。这是被缓存的对象。
 *
 * <p>{@code source} 字段标识本次数据来自哪一层（L1 本地 / L2 Redis / DB），
 * 它<b>不是业务字段</b>，而是为了让缓存效果可观测：
 * 压测时统计 {@code source} 的分布，就能直接算出 L1/L2 命中率，
 * 而不是靠猜或者读代码。生产环境通常用日志/埋点而不是响应体暴露这个信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String subTitle;
    private String coverImg;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private String detail;
    private LocalDateTime updateTime;

    private String source;

    public ProductDetailVO copyWithSource(String source) {
        return new ProductDetailVO(id, title, subTitle, coverImg, price, stock, status,
                detail, updateTime, source);
    }
}
