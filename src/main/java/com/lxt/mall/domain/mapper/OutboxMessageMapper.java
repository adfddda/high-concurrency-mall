package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.OutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {

    /**
     * 取出到期待发送的消息。
     *
     * <p>加 {@code next_retry_time <= now} 是为了失败退避：投递失败的消息
     * 不会被下一轮立刻重试，避免 broker 持续不可用时把日志和 CPU 刷满。
     */
    @Select("""
            SELECT * FROM t_outbox
             WHERE status = 0
               AND next_retry_time <= #{now}
             ORDER BY id
             LIMIT #{limit}
            """)
    List<OutboxMessage> selectPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 投递成功。 */
    @Update("""
            UPDATE t_outbox
               SET status = 1, last_error = NULL
             WHERE id = #{id}
               AND status = 0
            """)
    int markSent(@Param("id") Long id);

    /**
     * 投递失败：重试次数 +1。
     *
     * <p>{@code retry_count >= maxRetry} 时置为 2（重试耗尽），不再自动投递——
     * 这类记录需要人工介入（看 {@code last_error}），
     * 而不是无限重试把消息系统变成噪声源。
     */
    @Update("""
            UPDATE t_outbox
               SET retry_count = retry_count + 1,
                   status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 2 ELSE 0 END,
                   next_retry_time = #{nextRetryTime},
                   last_error = #{error}
             WHERE id = #{id}
               AND status = 0
            """)
    int markFailed(@Param("id") Long id,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("maxRetry") int maxRetry,
                   @Param("error") String error);
}
