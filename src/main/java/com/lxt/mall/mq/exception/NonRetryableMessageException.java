package com.lxt.mall.mq.exception;

/**
 * 不可重试的消息异常。
 *
 * <p>用于"重试没有意义"的业务性失败，例如：
 * <ul>
 *   <li>数据库侧库存不足（Redis 允许了但 DB 拒绝）——重试还是不足</li>
 *   <li>活动已结束——重试也还是结束</li>
 *   <li>消息体本身非法——重试永远是非法</li>
 * </ul>
 *
 * <p>这类异常应当<b>立即回补 Redis 库存并 ACK</b>（或直接进死信），
 * 而不是走 3 次延迟重试。区分"可重试"与"不可重试"是可靠消息系统
 * 必须做的一个判断——把所有失败都当成可重试，会让一条毒消息
 * 反复占用消费者、拖慢整个队列，也就是所谓的"毒丸消息"。
 */
public class NonRetryableMessageException extends RuntimeException {

    public NonRetryableMessageException(String message) {
        super(message, null, false, false);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause, false, false);
    }
}
