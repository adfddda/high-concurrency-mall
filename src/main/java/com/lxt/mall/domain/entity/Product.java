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

/** 商品。 */
@Data
@TableName("t_product")
public class Product implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String subTitle;

    private String coverImg;

    /** 金额一律 BigDecimal —— float/double 的二进制表示没法精确表达 0.1。 */
    private BigDecimal price;

    private Integer stock;

    /** 1 上架 / 0 下架 */
    private Integer status;

    /** 大字段：缓存到 Redis 时按需裁剪，避免把几十 KB 的详情塞进热 key。 */
    private String detail;

    private Integer version;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
