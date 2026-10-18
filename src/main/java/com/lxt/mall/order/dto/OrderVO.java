package com.lxt.mall.order.dto;

import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderNo;

    private Long activityId;

    private Long productId;

    private String productTitle;

    private BigDecimal seckillPrice;

    private Integer quantity;

    private BigDecimal payAmount;

    private Integer status;

    private String statusDesc;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime cancelTime;

    private LocalDateTime expireTime;

    public static OrderVO from(Order order) {
        OrderStatus status = order.getStatus();
        return new OrderVO(order.getOrderNo(), order.getActivityId(), order.getProductId(),
                order.getProductTitle(), order.getSeckillPrice(), order.getQuantity(),
                order.getPayAmount(),
                status == null ? null : status.getCode(),
                status == null ? null : status.getDesc(),
                order.getCreateTime(), order.getPayTime(), order.getCancelTime(), order.getExpireTime());
    }
}
