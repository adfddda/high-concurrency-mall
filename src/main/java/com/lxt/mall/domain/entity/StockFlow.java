package com.lxt.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lxt.mall.domain.enums.StockChangeType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存流水。
 *
 * <p>唯一键 {@code uk_order_change(order_no, change_type)} 是「同一订单的
 * 扣减/回补各只能成功一次」的硬约束。超时关单回补和死信回补可能并发发生，
 * 靠唯一键冲突把第二次挡掉，比在应用层加分布式锁便宜得多。
 */
@Data
@TableName("t_stock_flow")
public class StockFlow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;

    private String orderNo;

    private Long userId;

    private StockChangeType changeType;

    private Integer changeNum;

    private Integer stockBefore;

    private Integer stockAfter;

    private String remark;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;
}
