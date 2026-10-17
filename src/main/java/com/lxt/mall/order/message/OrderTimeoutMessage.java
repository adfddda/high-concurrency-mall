package com.lxt.mall.order.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 订单超时关单消息（走延迟队列，TTL 到期后由 DLX 投递到关单队列）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String messageId;

    private String orderNo;

    private Long orderId;

    private Long activityId;

    private Long userId;

    private Integer quantity;

    /** 活动总库存：Redis 回补时用作封顶值。 */
    private Integer totalStock;

    private LocalDateTime expireTime;

    public String bizKey() {
        return orderNo;
    }
}
