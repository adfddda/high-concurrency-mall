package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 条件扣减库存。
     *
     * <p>把「判断」和「扣减」压进同一条 UPDATE 里，由 InnoDB 的行锁保证原子性：
     * <pre>
     *   UPDATE ... SET available_stock = available_stock - 1
     *    WHERE id = ? AND available_stock >= 1
     * </pre>
     * 影响行数 = 1 表示扣减成功，= 0 表示库存已被抢空。
     *
     * <p>为什么不写成「SELECT ... FOR UPDATE 再 UPDATE」？
     * 那样是两次网络往返 + 更长的持锁时间；单条 UPDATE 持有行锁的时间更短，
     * 在高并发下 TPS 明显更高。
     *
     * <p>注意 JDBC 参数 useAffectedRows=true：
     * MySQL Connector/J 默认会带上 CLIENT_FOUND_ROWS 标志，
     * 使 UPDATE 返回的是「匹配行数」而不是「实际变更行数」，
     * 进而让这种「靠影响行数判断是否抢到」的写法在边界情况下失真。
     */
    @Update("""
            UPDATE t_seckill_activity
               SET available_stock = available_stock - #{num}
             WHERE id = #{activityId}
               AND available_stock >= #{num}
            """)
    int deductStock(@Param("activityId") Long activityId, @Param("num") int num);

    /**
     * 条件回补库存。
     *
     * <p>{@code available_stock + #{num} <= total_stock} 这个条件非常重要：
     * 它保证无论补偿逻辑被触发多少次，库存都不可能被顶到总库存以上。
     * 应用层有 {@code t_stock_flow} 唯一键做第一层防重，这里是第二层。
     */
    @Update("""
            UPDATE t_seckill_activity
               SET available_stock = available_stock + #{num}
             WHERE id = #{activityId}
               AND available_stock + #{num} <= total_stock
            """)
    int rollbackStock(@Param("activityId") Long activityId, @Param("num") int num);

    /**
     * 按时间推进活动状态：0 未开始 / 1 进行中 / 2 已结束。
     *
     * <p>为什么状态要由任务推进、而不是在查询时实时算：
     * <ul>
     *   <li>列表接口要支持"只看进行中"，实时算会让 SQL 无法走索引；</li>
     *   <li>运营后台、对账、预热都依赖 status 字段做筛选。</li>
     * </ul>
     * 但状态是<b>时间的派生值</b>，一旦没有任务维护，它就会永远停在创建时的值——
     * 这正是原实现的漏洞：种子数据写了 status=1，之后再也没有任何代码改过它，
     * 于是"已结束的活动"在列表里永远是"进行中"。
     *
     * <p>用一条 UPDATE 做全表状态对齐：`WHERE status <> 新值` 保证只有状态
     * 真正变化的行才会被写，正常运行时这条语句几乎不产生写入。
     */
    @Update("""
            UPDATE t_seckill_activity
               SET status = CASE
                              WHEN start_time > #{now} THEN 0
                              WHEN end_time <= #{now} THEN 2
                              ELSE 1
                            END
             WHERE status <> CASE
                              WHEN start_time > #{now} THEN 0
                              WHEN end_time <= #{now} THEN 2
                              ELSE 1
                            END
            """)
    int syncStatus(@Param("now") LocalDateTime now);

    /**
     * 立即开抢（运营工具）。
     *
     * <p>把开始时间提前到"现在"，常用于补开活动或演示。
     * 带 {@code start_time > now} 条件：只允许"提前"，不能把已经开始的活动推后——
     * 否则一次误操作就能让正在进行的秒杀提前结束。
     */
    @Update("""
            UPDATE t_seckill_activity
               SET start_time = #{now}, status = 1
             WHERE id = #{id}
               AND start_time > #{now}
               AND end_time > #{now}
            """)
    int openNow(@Param("id") Long id, @Param("now") LocalDateTime now);
}
