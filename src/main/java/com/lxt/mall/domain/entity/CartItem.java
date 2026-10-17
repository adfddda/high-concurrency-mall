package com.lxt.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车条目。
 *
 * <p>刻意只存 "user + sku + 数量 + 是否勾选"，<b>不存价格快照</b>。
 * 价格是 SKU 的属性，展示时实时读 SKU：商品调价后购物车必须立刻反映新价，
 * 否则会出现"购物车显示 99 元、结算却是 129 元"的投诉。
 * 订单侧则相反——订单必须存快照，因为订单是合同。
 */
@Data
@TableName("t_cart_item")
public class CartItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long skuId;

    private Integer quantity;

    /** 1 勾选 / 0 未勾选（结算时只结算勾选项）。 */
    private Integer checked;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
