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
               SET status = 2, cancel_time = #{cancelTime}
             WHERE order_no = #{orderNo}
               AND status = 0
            """)
    int cancelOrder(@Param("orderNo") String orderNo, @Param("cancelTime") LocalDateTime cancelTime);

    /**
     * 完成订单：PAID -> FINISHED。
     */
    @Update("""
            UPDATE t_order
               SET status = 3
             WHERE order_no = #{orderNo}
               AND status = 1
            """)
    int finishOrder(@Param("orderNo") String orderNo);

    /**
     * 退款成功：PAID -> REFUNDED。
     *
     * <p>必须是 PAID 才能退——未支付的订单应该走"取消"（Canceled），
     * 而不是"退款"。这两个动作在财务上完全不同：一个是作废，一个是资金流出。
     */
    @Update("""
            UPDATE t_order
               SET status = 4
             WHERE order_no = #{orderNo}
               AND status = 1
            """)
    int markRefunded(@Param("orderNo") String orderNo);

    /**
     * 发货：PAID -> SHIPPED。
     *
     * <p>带 {@code status = 1} 条件，重复调用只会有一次生效。
     * 发货是<b>不可逆的外部动作</b>（货已经交给物流了），
     * 所以它必须比支付更严格地防重复。
     */
    @Update("""
            UPDATE t_order
               SET status = 5, ship_time = #{shipTime}
             WHERE order_no = #{orderNo}
               AND status = 1
            """)
    int markShipped(@Param("orderNo") String orderNo, @Param("shipTime") LocalDateTime shipTime);

    /**
     * 确认收货：SHIPPED -> FINISHED。
     *
     * <p>前驱状态是 SHIPPED 而不是 PAID：没发货就不存在"确认收货"。
     * 同时它是自动确认收货任务的唯一入口——用户手动确认和任务自动确认
     * 走同一条状态迁移，只有一个能成功。
     */
    @Update("""
            UPDATE t_order
               SET status = 3, finish_time = #{finishTime}
             WHERE order_no = #{orderNo}
               AND status = 5
            """)
    int markFinished(@Param("orderNo") String orderNo, @Param("finishTime") LocalDateTime finishTime);
}
