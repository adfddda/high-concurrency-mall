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

/** 拼团。 */
@Data
@TableName("t_group_buy")
public class GroupBuy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long leaderUserId;

    private Long skuId;

    /** 成团所需人数。 */
    private Integer requiredMembers;

    /** 已支付人数（冗余字段，让成团判定变成一次原子自增）。 */
    private Integer paidCount;

    /** 0 进行中 / 1 已成团 / 2 已失败 */
    private Integer status;

    private LocalDateTime expireTime;

    private LocalDateTime completeTime;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
