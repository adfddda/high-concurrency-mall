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
 * 本地消息表记录（Outbox）。
 *
 * <p>它与业务数据在<b>同一个数据库事务</b>里写入，因此"消息待发送"这个事实
 * 是事务性的：业务提交成功 ⇒ outbox 记录一定在；业务回滚 ⇒ 一定不在。
 * 投递则由独立任务轮询完成，失败可以重试、可以人工排查。
 */
@Data
@TableName("t_outbox")
public class OutboxMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String messageId;

    private String bizType;

    private String payload;

    /** 0 待发送 / 1 已发送 / 2 重试耗尽 */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String lastError;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
