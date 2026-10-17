package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * SKU Mapper。
 *
 * <p>库存扣减用「条件更新」而不是「先查再改」：
 * <pre>
 *   UPDATE t_sku SET stock = stock - ? WHERE id = ? AND stock >= ?
 * </pre>
 * 判断与扣减压进同一条语句，由 InnoDB 行锁保证互斥。
 * 影响行数为 0 就是"库存不足"，不需要额外的 SELECT 也读不出脏数据。
 *
 * <p>这是<b>普通下单</b>与秒杀的对照点：普通下单不去 Redis，直接同步扣库，
 * 因为它的并发量级不需要那套削峰机制——<b>不要为了"高并发"而过度设计</b>。
 */
@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    @Update("""
            UPDATE t_sku
               SET stock = stock - #{num}
             WHERE id = #{skuId}
               AND stock >= #{num}
               AND status = 1
            """)
    int deductStock(@Param("skuId") Long skuId, @Param("num") int num);

    /**
     * 回补库存（取消 / 退款）。
     *
     * <p>没有加"上限"条件：SKU 的库存本来就不应该被"总数"约束，
     * 因为退款回补、取消回补在业务上都是合法的加法。
     * 重复回补由调用方保证幂等（订单状态机 + 退款单唯一键），
     * 这一点与秒杀活动库存不同——活动库存有 total_stock 这个硬上限。
     */
    @Update("""
            UPDATE t_sku
               SET stock = stock + #{num}
             WHERE id = #{skuId}
            """)
    int rollbackStock(@Param("skuId") Long skuId, @Param("num") int num);
}
