package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.SeckillReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillReservationMapper extends BaseMapper<SeckillReservation> {

    /** 中签：仅当仍在"已预约"状态时生效，防止重复抽签覆盖结果。 */
    @Update("""
            UPDATE t_seckill_reservation
               SET status = 1, qualify_code = #{code}, draw_batch = #{batch}
             WHERE activity_id = #{activityId}
               AND user_id = #{userId}
               AND status = 0
            """)
    int markWon(@Param("activityId") Long activityId,
                @Param("userId") Long userId,
                @Param("code") String code,
                @Param("batch") int batch);

    /** 未中签：同样带 {@code status = 0} 前驱条件。 */
    @Update("""
            UPDATE t_seckill_reservation
               SET status = 2, draw_batch = #{batch}
             WHERE activity_id = #{activityId}
               AND user_id = #{userId}
               AND status = 0
            """)
    int markLost(@Param("activityId") Long activityId,
                 @Param("userId") Long userId,
                 @Param("batch") int batch);

    /** 资格已使用：中签 → 已使用，一次性。 */
    @Update("""
            UPDATE t_seckill_reservation
               SET status = 3
             WHERE activity_id = #{activityId}
               AND user_id = #{userId}
               AND status = 1
            """)
    int markUsed(@Param("activityId") Long activityId, @Param("userId") Long userId);
}
