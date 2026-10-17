package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 核销：未使用 → 已使用。
     *
     * <p>带 {@code user_id} 与 {@code status = 0} 条件：
     * 前者防越权（用别人的券），后者防重复核销。
     * 影响行数为 0 就是"券不存在 / 不是你的 / 已经用过了"，
     * 三种情况用同一句话拒绝，不泄露券的归属信息。
     */
    @Update("""
            UPDATE t_coupon
               SET status = 1, order_no = #{orderNo}, use_time = #{useTime}
             WHERE id = #{id}
               AND user_id = #{userId}
               AND status = 0
               AND expire_time > #{now}
            """)
    int markUsed(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("orderNo") String orderNo,
                 @Param("useTime") LocalDateTime useTime,
                 @Param("now") LocalDateTime now);

    /**
     * 退款还券：已使用 → 未使用。
     *
     * <p>真实电商里"取消/退款后券是否退回"是运营策略，本项目按"退回"处理，
     * 因为券是用户资产，吞掉会引发投诉。
     */
    @Update("""
            UPDATE t_coupon
               SET status = 0, order_no = NULL, use_time = NULL
             WHERE order_no = #{orderNo}
               AND status = 1
            """)
    int restoreByOrder(@Param("orderNo") String orderNo);
}
