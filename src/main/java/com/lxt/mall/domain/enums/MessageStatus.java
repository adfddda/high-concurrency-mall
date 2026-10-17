package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 消息记录状态。
 *
 * <p>t_mq_message 一张表承担三个角色，靠 direction + status 区分：
 * <ul>
 *   <li>生产侧台账：direction=PRODUCE，PENDING 表示已发出但未收到 Confirm，
 *       由 {@code PendingMessageRetryJob} 回溯补偿</li>
 *   <li>消费侧幂等：direction=CONSUME，uk_message_id 唯一键即去重闸门</li>
 *   <li>失败台账：FAILED 是死信与人工介入的入口</li>
 * </ul>
 */
public enum MessageStatus {

    PENDING(0, "待处理"),
    SUCCESS(1, "成功"),
    FAILED(2, "失败");

    @EnumValue
    private final int code;
    private final String desc;

    MessageStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static MessageStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (MessageStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知消息状态: " + code);
    }
}
