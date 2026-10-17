package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.SeckillRiskHit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SeckillRiskHitMapper extends BaseMapper<SeckillRiskHit> {

    /** 按原因统计某个活动的拦截次数，供运维接口展示。 */
    @Select("""
            SELECT reason AS reason, COUNT(*) AS cnt
              FROM t_seckill_risk_hit
             WHERE activity_id = #{activityId}
             GROUP BY reason
            """)
    List<Map<String, Object>> countByReason(@Param("activityId") Long activityId);
}
