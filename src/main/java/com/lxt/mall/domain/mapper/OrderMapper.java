package com.lxt.mall.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lxt.mall.domain.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 订单 Mapper。
 *
 * <p>这里所有状态流转都写成「带前驱状态条件的 UPDATE」，
 * 靠影响行数判断竞争结果。原因在于：
 * <ul>
 *   <li>「先 SELECT 判断状态，再 UPDATE」两步之间存在窗口，
 *       支付回调与超时关单可能同时通过判断，一个订单既被置为已支付又被置为已取消；
 *   <li>带条件的一条 UPDATE 把判断压进 InnoDB 行锁里，
 *       两个并发事务只有一个能改到行，天然互斥。
 * </ul>
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 支付成功：CREATED -> PAID。
     *
     * @return 1 表示本次调用真正完成了状态流转；0 表示订单不存在，
     *         或已经是 PAID/CANCELED（重复回调、回调晚于超时关单）
     */
    @Update("""
            UPDATE t_order
               SET status = 1, pay_time = #{payTime}
             WHERE order_no = #{orderNo}
               AND status = 0
            """)
    int markPaid(@Param("orderNo") String orderNo, @Param("payTime") LocalDateTime payTime);

    /**
     * 取消订单：CREATED -> CANCELED。
     *
     * <p>超时关单、用户主动取消、死信回补三条路径都会调用它，
     * 但只有第一个到达的能拿到 1，其余全部返回 0，
     * 从而保证「库存只回补一次」这个语义（回补动作挂在返回 1 的分支上）。
     */
    @Update("""
            UPDATE t_order
               SET status = 2, cancel_time = #{cancelTime}, active_flag = NULL
             WHERE order_no = #{orderNo}
               AND status = 0
            """)
    int cancelOrder(@Param("orderNo") String orderNo, @Param("cancelTime") LocalDateTime cancelTime);
}
