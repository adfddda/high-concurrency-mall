package com.lxt.mall.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.domain.entity.MqMessage;
import com.lxt.mall.domain.enums.MessageDirection;
import com.lxt.mall.domain.enums.MessageStatus;
import com.lxt.mall.domain.mapper.MqMessageMapper;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 消息台账：消费幂等 + 失败记录。
 *
 * <h3>幂等为什么必须靠唯一键，而不是"先查再插"</h3>
 * <pre>
 *   消费者 A: SELECT ... WHERE message_id = 'M1'  → 不存在
 *   消费者 B: SELECT ... WHERE message_id = 'M1'  → 不存在
 *   消费者 A: INSERT → 成功
 *   消费者 B: INSERT → 成功          ← 两条订单，幂等失效
 * </pre>
 * 唯一键由 InnoDB 在<b>索引层面</b>保证互斥，任何并发都绕不过去。
 * 所以这里的姿势是"检查只是为了给出更清晰的异常语义，
 * 真正的正确性由 uk_message_id 兜住"。
 *
 * <h3>与业务事务的关系</h3>
 * 消费记录的 INSERT 必须与订单 INSERT 在<b>同一个事务</b>里。
 * 如果分开（比如先记消息再开事务），业务失败回滚后消息记录还在，
 * 重试时就会被误判为"已消费"而直接 ACK，消息被永久丢弃——
 * 这是一个非常隐蔽的丢消息 Bug。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqMessageService {

    private final MqMessageMapper mqMessageMapper;
    private final MallMetrics metrics;

    /**
     * 是否已经消费过。仅用于给出更明确的异常语义，正确性由唯一键保证。
     *
     * <p><b>必须在消费者所在的事务内调用。</b>
     */
    public boolean alreadyConsumed(String messageId) {
        return mqMessageMapper.exists(
                Wrappers.<MqMessage>lambdaQuery().eq(MqMessage::getMessageId, messageId));
    }

    /**
     * 写入消费记录（与业务同事务）。
     *
     * @throws DuplicateKeyException 并发重复消费时由唯一键拦下
     */
    public void insertConsumeRecord(SeckillOrderMessage msg) {
        MqMessage record = new MqMessage();
        record.setMessageId(msg.getMessageId());
        record.setBizType("SECKILL_ORDER");
        record.setBizKey(msg.bizKey());
        record.setDirection(MessageDirection.CONSUME);
        record.setStatus(MessageStatus.SUCCESS);
        record.setRetryCount(0);
        record.setPayload(JsonUtils.toJson(msg));
        mqMessageMapper.insert(record);
    }

    /** 标记消费失败。用于死信与人工排查，不影响主流程。 */
    public void markConsumeFailed(String messageId, String errorMsg) {
        try {
            MqMessage update = new MqMessage();
            update.setStatus(MessageStatus.FAILED);
            update.setErrorMsg(truncate(errorMsg));
            mqMessageMapper.update(update, Wrappers.<MqMessage>lambdaUpdate()
                    .eq(MqMessage::getMessageId, messageId));
            metrics.mqEvent("seckill", "consume_failed_recorded");
        } catch (Exception e) {
            log.error("记录消费失败状态时出错 messageId={}", messageId, e);
        }
    }

    /** 递增重试次数。 */
    public void incrementRetry(String messageId) {
        try {
            mqMessageMapper.update(null, Wrappers.<MqMessage>lambdaUpdate()
                    .eq(MqMessage::getMessageId, messageId)
                    .setSql("retry_count = retry_count + 1"));
        } catch (Exception e) {
            log.warn("递增重试次数失败 messageId={}", messageId, e);
        }
    }

    /**
     * 记录生产侧投递失败。
     *
     * <p>注意：<b>正常投递不写库</b>。秒杀接口是整条链路压力最大的地方，
     * 在那里插一条数据库记录等于自己给热路径加一个写入瓶颈。
     * 只有失败时才落库，日志量与故障量成正比而不是与流量成正比。
     */
    public void recordProduceFailure(String messageId, String bizKey, Object payload, String errorMsg) {
        try {
            MqMessage existing = mqMessageMapper.selectOne(
                    Wrappers.<MqMessage>lambdaQuery().eq(MqMessage::getMessageId, messageId));
            if (existing != null) {
                markConsumeFailed(messageId, errorMsg);
                return;
            }
            MqMessage record = new MqMessage();
            record.setMessageId(messageId);
            record.setBizType("SECKILL_ORDER");
            record.setBizKey(bizKey);
            record.setDirection(MessageDirection.PRODUCE);
            record.setStatus(MessageStatus.FAILED);
            record.setRetryCount(0);
            record.setPayload(JsonUtils.toJson(payload));
            record.setErrorMsg(truncate(errorMsg));
            mqMessageMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.debug("生产失败记录已存在 messageId={}", messageId);
        } catch (Exception e) {
            log.error("记录生产失败状态时出错 messageId={}", messageId, e);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        // error_msg 列是 VARCHAR(512)，超长会被 MySQL 截断或报错（严格模式下报错）
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
