package com.lxt.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lxt.mall.domain.enums.MessageDirection;
import com.lxt.mall.domain.enums.MessageStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息投递 / 消费记录（幂等表 + 台账）。
 *
 * <p>消费端用它做幂等的正确姿势是【直接 INSERT，靠唯一键冲突去重】，
 * 而不是 SELECT 再判断：
 * <pre>
 *   try {
 *       mqMessageMapper.insert(record);   // uk_message_id 冲突 → DuplicateKeyException
 *       doBusiness();
 *   } catch (DuplicateKeyException e) {
 *       // 重复消费，直接 ack
 *   }
 * </pre>
 * "先查再插"在并发消费（多消费者 competing consumer）下必然漏判，
 * 而唯一键是由 InnoDB 保证的强约束，任何并发都绕不过去。
 */
@Data
@TableName("t_mq_message")
public class MqMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String messageId;

    /** SECKILL_ORDER / ORDER_TIMEOUT */
    private String bizType;

    private String bizKey;

    private MessageDirection direction;

    private MessageStatus status;

    private Integer retryCount;

    private String payload;

    private String errorMsg;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
