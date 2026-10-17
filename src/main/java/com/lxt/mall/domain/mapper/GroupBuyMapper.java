package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.GroupBuy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface GroupBuyMapper extends BaseMapper<GroupBuy> {

    /**
     * 成员支付成功：已支付人数 +1，够了就成团。
     *
     * <p><b>SET 子句的顺序很关键</b>：MySQL 会对已赋值的列在后续表达式中使用新值，
     * 所以必须<b>先</b>用旧的 {@code paid_count} 判断是否成团，<b>再</b>自增。
     * 写反了就会永远差一人才能成团——这类 bug 不会报错，只会让团永远成不了。
     *
     * <p>前驱条件 {@code status = 0} 保证已成团/已失败的团不会被再次推进。
     */
    @Update("""
            UPDATE t_group_buy
               SET status = CASE WHEN paid_count + 1 >= required_members THEN 1 ELSE status END,
                   complete_time = CASE WHEN paid_count + 1 >= required_members THEN #{now} ELSE complete_time END,
                   paid_count = paid_count + 1
             WHERE id = #{id}
               AND status = 0
            """)
    int increasePaid(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 超时失败：进行中 → 已失败。
     *
     * <p>条件更新保证"恰好成团"与"到期失败"只有一个能赢：
     * 团在第 24 小时整凑满了人，而定时任务同时判定超时，两者并发时
     * 只有一条 UPDATE 能改到行，不会出现"已成团又被判失败"。
     */
    @Update("""
            UPDATE t_group_buy
               SET status = 2
             WHERE id = #{id}
               AND status = 0
            """)
    int markFailed(@Param("id") Long id);
}
