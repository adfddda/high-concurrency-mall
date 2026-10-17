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
 *   <li>{@code uk_user_activity} —— 一人一单。Redis 的 Set 判定在
 *       「Redis 主从切换 / 缓存被清 / 投递失败回补」等场景下可能出现漏网，
 *       数据库这层是最后一道闸门</li>
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

    /** 订单来源：0 秒杀 / 1 普通。见 {@link com.lxt.mall.domain.enums.OrderBizType}。 */
    private Integer bizType;

    /** 秒杀活动 ID；普通订单为 null（数据库唯一索引允许多个 NULL）。 */
    private Long activityId;

    /** SKU ID；秒杀订单为 null（秒杀是单 SKU 特例，库存挂在活动上）。 */
    private Long skuId;

    private Long productId;

    /** 商品标题快照：商品改价改名不影响历史订单展示。 */
    private String productTitle;

    private BigDecimal seckillPrice;

    private Integer quantity;

    private BigDecimal payAmount;

    /** 使用的优惠券 ID（未使用为 null）。 */
    private Long couponId;

    /** 优惠金额。pay_amount = 单价 × 数量 − discount_amount。 */
    private BigDecimal discountAmount;

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

    /** 发货时间。自动确认收货的计时起点。 */
    private LocalDateTime shipTime;

    /** 完成时间（用户确认收货 / 系统自动确认）。 */
    private LocalDateTime finishTime;

    /** 超时关单时间点，由延迟队列消息触达时校验，双保险。 */
    private LocalDateTime expireTime;

    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
}
