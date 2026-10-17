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
 * 秒杀活动。
 *
 * <p>available_stock 是唯一的"权威库存"，Redis 里的库存只是它的一份
 * 【可重建的投影】。任何时刻都可以用
 * {@code available_stock + 已支付订单数 + 在途} 反推 Redis 应该等于多少，
 * 这正是 {@code SeckillReconcileJob} 的对账依据。
 */
@Data
@TableName("t_seckill_activity")
public class SeckillActivity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String activityName;

    private Long productId;

    private BigDecimal seckillPrice;

    /** 总库存：活动快照，只读，用于回补时封顶与对账。 */
    private Integer totalStock;

    /** 剩余可售库存：唯一会被并发扣减的字段。 */
    private Integer availableStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Integer version;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
