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
 * 秒杀预约记录（抽签活动的参与凭证）。
 *
 * <p>状态机：
 * <pre>
 *   预约(0) ──抽签──┬─► 中签(1) ──下单成功──► 已使用(3)
 *                   └─► 未中签(2)
 * </pre>
 * {@code uk_activity_user} 保证一个人对一个活动只有一条记录，
 * 这同时也是"抽签时不会重复抽到同一个人"的依据。
 */
@Data
@TableName("t_seckill_reservation")
public class SeckillReservation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;

    private Long userId;

    /** 0 已预约 / 1 已中签 / 2 未中签 / 3 资格已使用 */
    private Integer status;

    /** 中签后生成的资格码，下单时作为一次性凭证。 */
    private String qualifyCode;

    private Integer drawBatch;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
