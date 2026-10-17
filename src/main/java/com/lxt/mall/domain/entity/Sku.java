package com.lxt.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SKU（商品的可售单元）。
 *
 * <p>与 {@link Product} 的关系：Product 是"这个商品是什么"（标题、图文详情），
 * Sku 是"具体卖哪一个"（颜色、容量、价格、库存）。一个商品多个 SKU，
 * 价格与库存都挂在 SKU 上——这是电商最基础的一次建模，
 * 也是"商品详情页选规格"这个交互背后的数据结构。
 */
@Data
@TableName("t_sku")
public class Sku implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long productId;

    /** 对外展示 / 对接上游的 SKU 编码。 */
    private String skuCode;

    /** 规格快照，如 {"color":"黑","storage":"512G"}。存 JSON 而不是拆表，见 README 的取舍说明。 */
    private String specJson;

    private BigDecimal price;

    private Integer stock;

    /** 1 在售 / 0 下架 */
    private Integer status;

    private Integer version;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
