package com.lxt.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lxt.mall.domain.enums.PaymentStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单。
 *
 * <p>为什么订单之外还要有支付单：一个订单可能对应多次支付尝试（关闭后重付、
 * 换渠道），渠道流水号与回调时间属于"这一次支付"，不属于订单。
 * 更重要的是<b>回调幂等的粒度</b>：渠道重试时带的是 paymentNo，
 * 幂等判断必须能按支付单定位，挂在订单上就丢失了这个维度。
 */
@Data
@TableName("t_payment")
public class Payment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String paymentNo;

    private String orderNo;

    /** 1 模拟渠道 / 2 支付宝 / 3 微信 */
    private Integer channel;

    private BigDecimal amount;

    private PaymentStatus status;

    /** 渠道流水号，对账时用它和渠道侧账单匹对。 */
    private String channelTradeNo;

    private LocalDateTime callbackTime;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
