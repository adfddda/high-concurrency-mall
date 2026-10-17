package com.lxt.mall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** 秒杀下单接口的响应：只返回"已受理"，不是最终结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单号，前端用它轮询 {@code /api/seckill/result/{orderNo}}。 */
    private String orderNo;

    /** QUEUEING / SUCCESS / FAILED。 */
    private SeckillResultStatus status;

    private String message;

    /** 建议的轮询间隔（毫秒）。 */
    private long retryAfterMillis;

    public static SeckillResponse accepted(String orderNo) {
        return new SeckillResponse(orderNo, SeckillResultStatus.QUEUEING,
                "已进入抢购队列，正在为您排队", 300);
    }
}
