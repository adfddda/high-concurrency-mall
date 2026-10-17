package com.lxt.mall.mq;

import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 已投递、等待 broker 确认的消息上下文。
 *
 * <p>存放在<b>进程内存</b>里，而不是落数据库。这是一个刻意的权衡，
 * 见 {@link MessageSendTracker} 的类注释。
 */
@Data
@AllArgsConstructor
public class PendingSend {

    private final String messageId;
    private final String orderNo;
    private final Long activityId;
    private final Long userId;
    private final Integer totalStock;
    private final long sendTimeMillis;
    private final SeckillOrderMessage payload;

    public static PendingSend of(SeckillOrderMessage msg) {
        return new PendingSend(msg.getMessageId(), msg.getOrderNo(), msg.getActivityId(),
                msg.getUserId(), msg.getTotalStock(), System.currentTimeMillis(), msg);
    }

    public boolean isOlderThan(long timeoutMillis) {
        return System.currentTimeMillis() - sendTimeMillis > timeoutMillis;
    }
}
