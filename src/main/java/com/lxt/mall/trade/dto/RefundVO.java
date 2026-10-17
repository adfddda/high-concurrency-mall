package com.lxt.mall.trade.dto;

import com.lxt.mall.domain.entity.Refund;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款单视图。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundVO implements Serializable {

    private String refundNo;

    private String orderNo;

    private BigDecimal amount;

    private Integer status;

    private String reason;

    private LocalDateTime refundTime;

    public static RefundVO from(Refund r) {
        return new RefundVO(r.getRefundNo(), r.getOrderNo(), r.getAmount(),
                r.getStatus(), r.getReason(), r.getRefundTime());
    }
}
