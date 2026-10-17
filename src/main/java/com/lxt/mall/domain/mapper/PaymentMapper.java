package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.Payment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 支付单 Mapper。
 *
 * <p>两个状态流转都写成带前驱条件的 UPDATE：
 * 支付成功只允许从 PENDING 迁入，退款只允许从 SUCCESS 迁入。
 * 这样"渠道重复回调"与"退款和回调并发"这两个真实场景，
 * 都只有一方能改到行，另一方拿到 0 行——不需要分布式锁。
 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

    @Update("""
            UPDATE t_payment
               SET status = 1,
                   channel_trade_no = #{channelTradeNo},
                   callback_time = #{callbackTime}
             WHERE payment_no = #{paymentNo}
               AND status = 0
            """)
    int markSuccess(@Param("paymentNo") String paymentNo,
                    @Param("channelTradeNo") String channelTradeNo,
                    @Param("callbackTime") LocalDateTime callbackTime);

    @Update("""
            UPDATE t_payment
               SET status = 3
             WHERE order_no = #{orderNo}
               AND status = 1
            """)
    int markRefunded(@Param("orderNo") String orderNo);
}
