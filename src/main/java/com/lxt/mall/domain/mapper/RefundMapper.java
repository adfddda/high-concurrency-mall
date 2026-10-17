package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.Refund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RefundMapper extends BaseMapper<Refund> {

    /**
     * 退款成功：处理中 -> 成功。
     *
     * <p>带 {@code status = 0} 条件，保证同一笔退款只会被"确认"一次。
     */
    @Update("""
            UPDATE t_refund
               SET status = 1, refund_time = #{refundTime}
             WHERE refund_no = #{refundNo}
               AND status = 0
            """)
    int markSuccess(@Param("refundNo") String refundNo, @Param("refundTime") LocalDateTime refundTime);
}
