package com.lxt.mall.seckill.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀下单消息。
 *
 * <p>消息体里【带齐了下游落库需要的全部字段】，包括商品标题、秒杀价这些快照。
 * 理由：
 * <ol>
 *   <li>消费端不需要再回查活动/商品表，少一次数据库依赖，
 *       也避免了"消费时活动已被修改"导致的金额不一致；</li>
 *   <li>消息本身成为一份可审计的凭证——线上排查"这单为什么是 3999 元"时，
 *       直接看消息体就够了。</li>
 * </ol>
 *
 * <p>注意<b>不要</b>把整个实体塞进消息：消息体会随字段增加而膨胀，
 * 而且会泄露不该外传的字段。显式列出需要的字段更安全。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息唯一 ID，消费端幂等去重的依据。 */
    private String messageId;

    /** 订单号（生产端预生成，这样接口能立刻把订单号返回给前端轮询）。 */
    private String orderNo;

    private Long userId;

    private Long activityId;

    private Long productId;

    private String productTitle;

    private BigDecimal seckillPrice;

    private Integer quantity;

    /** 活动总库存，补偿脚本用它做回补封顶。 */
    private Integer totalStock;

    /** 创建时间，用于排查消息积压时长。 */
    private LocalDateTime createdAt;

    public String bizKey() {
        return activityId + ":" + userId;
    }
}
