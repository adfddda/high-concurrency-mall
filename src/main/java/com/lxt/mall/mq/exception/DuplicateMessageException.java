package com.lxt.mall.mq.exception;

/**
 * 重复消费。
 *
 * <p>抛出它意味着"这条消息之前已经成功处理过了"，
 * 属于<b>正常的幂等命中</b>，不是故障。消费端应当直接 ACK 而不是重试。
 */
public class DuplicateMessageException extends RuntimeException {

    public DuplicateMessageException(String messageId) {
        super("消息已消费过: " + messageId, null, false, false);
    }
}
