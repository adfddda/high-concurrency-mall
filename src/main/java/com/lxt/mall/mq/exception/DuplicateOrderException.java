package com.lxt.mall.mq.exception;

/**
 * 订单唯一键冲突。
 *
 * <p>意味着这条消息对应的用户/订单已经存在。原因是 Redis 侧的一人一单判定
 * 被绕过了（缓存被清、主从切换丢数据、补偿逻辑重复执行等）。
 *
 * <p>处理方式：<b>回补 Redis 库存并 ACK。</b>
 * 不能交给重试——重试一万次结果都一样，只会把队列堵住。
 * 同时这是一条必须告警的信号：它说明 Redis 与 MySQL 之间出现了一致性缺口。
 */
public class DuplicateOrderException extends RuntimeException {

    public DuplicateOrderException(String message) {
        super(message, null, false, false);
    }
}
