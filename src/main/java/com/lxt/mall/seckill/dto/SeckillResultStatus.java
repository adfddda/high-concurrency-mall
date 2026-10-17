package com.lxt.mall.seckill.dto;

/** 秒杀结果状态。 */
public enum SeckillResultStatus {

    /** 已进入队列，订单尚未落库。 */
    QUEUEING,

    /** 订单已创建成功。 */
    SUCCESS,

    /** 最终失败（投递失败、落库失败、库存不足等），已回补库存。 */
    FAILED
}
