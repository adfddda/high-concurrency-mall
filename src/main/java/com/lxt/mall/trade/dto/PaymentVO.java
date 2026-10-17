package com.lxt.mall.trade.dto;

import com.lxt.mall.domain.entity.Payment;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付单视图。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVO implements Serializable {

    private String paymentNo;

    private String orderNo;

    private BigDecimal amount;

    private Integer status;

    private String statusDesc;

    private String channelTradeNo;

    private LocalDateTime callbackTime;

    public static PaymentVO from(Payment p) {
        return new PaymentVO(p.getPaymentNo(), p.getOrderNo(), p.getAmount(),
                p.getStatus() == null ? null : p.getStatus().getCode(),
                p.getStatus() == null ? null : p.getStatus().getDesc(),
                p.getChannelTradeNo(), p.getCallbackTime());
    }
}
