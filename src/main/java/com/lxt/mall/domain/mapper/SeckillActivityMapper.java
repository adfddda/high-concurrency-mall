package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}
