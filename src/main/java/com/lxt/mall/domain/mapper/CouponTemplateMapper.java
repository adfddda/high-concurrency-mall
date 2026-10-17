package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.Coupon;
import com.lxt.mall.domain.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /**
     * 领券：原子占用一个发行名额。
     *
     * <p>{@code issued_count < total_count} 与自增压在同一条 UPDATE 里，
     * 影响行数为 0 就代表"领完了"或"不在发放期"。
     * 这是库存扣减思路在营销域的直接复用——<b>凡是有限的资源都要这么发</b>。
     */
    @Update("""
            UPDATE t_coupon_template
               SET issued_count = issued_count + 1
             WHERE id = #{id}
               AND status = 1
               AND issued_count < total_count
               AND #{now} BETWEEN start_time AND end_time
            """)
    int claimOne(@Param("id") Long id, @Param("now") LocalDateTime now);
}
