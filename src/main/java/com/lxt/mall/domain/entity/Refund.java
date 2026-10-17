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
 * 退款单。
 *
 * <p>{@code uk_order_no} 是退款幂等的核心：一个订单最多退一次。
 * 重复调用退款接口、或渠道退款回调重复抵达时，第二次直接撞唯一键，
 * 而不是靠应用层"先查再插"（那有竞态）。
 */
@Data
@TableName("t_refund")
public class Refund implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String refundNo;

    private String orderNo;

    private Long userId;

    private BigDecimal amount;

    private String reason;

    /** 0 处理中 / 1 成功 / 2 拒绝 */
    private Integer status;

    private LocalDateTime refundTime;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
