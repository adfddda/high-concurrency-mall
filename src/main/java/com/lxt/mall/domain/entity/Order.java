package com.lxt.mall.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lxt.mall.domain.enums.OrderStatus;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单。
 *
 * <p>注意三个唯一索引的分工（这是本项目幂等设计的地基）：
 * <ul>
 *   <li>{@code uk_order_no} —— 订单号幂等，重放同一订单号必然失败</li>
 *   <li>{@code uk_user_activity} —— 一人一单（带 {@code active_flag}：
 *       取消/退款后置 NULL，允许再次购买，历史订单仍保留）</li>
 *   <li>{@code uk_message_id} —— 同一 MQ 消息重复投递时，
 *       第二条 INSERT 直接撞唯一键，比"先查再插"省掉一次竞态</li>
 * </ul>
 *
 * <p>主键用雪花 ID 而非自增：高并发写入时 InnoDB 的自增主键存在
 * AUTO-INC 锁竞争，且自增在将来分库分表时无法保证全局唯一。
 */
@Data
@TableName("t_order")
public class Order implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Long userId;

    /** 秒杀活动 ID。 */
    private Long activityId;

    private Long productId;

    /** 商品标题快照：商品改价改名不影响历史订单展示。 */
    private String productTitle;

    private BigDecimal seckillPrice;

    private Integer quantity;

    private BigDecimal payAmount;

    private OrderStatus status;

    private String messageId;

    /**
     * 有效期标记：1 = 有效，null = 已失效（取消/退款）。
     *
     * <p>它是"一人一单"唯一索引的一部分：取消后置 null，用户就能重新抢，
     * 而历史订单仍然保留可查。用"删订单"来实现同样效果会丢失订单历史。
     */
    private Integer activeFlag;

    private LocalDateTime payTime;

    private LocalDateTime cancelTime;

    /** 超时关单时间点，由延迟队列消息触达时校验，双保险。 */
    private LocalDateTime expireTime;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
