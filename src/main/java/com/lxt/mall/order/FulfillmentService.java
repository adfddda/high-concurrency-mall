package com.lxt.mall.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.domain.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 履约：发货 → 确认收货。
 *
 * <h3>它补齐的是状态机里断掉的一环</h3>
 * 在加入履约之前，订单支付成功后停在 {@code PAID}，再没有任何代码推进它。
 * {@link OrderStatus#FINISHED} 是一个<b>永远不可达的死状态</b>——
 * 这类"枚举里定义了但没人会写"的值，是数据模型腐化最早的症状。
 *
 * <pre>
 *   CREATED ──支付──► PAID ──发货──► SHIPPED ──确认/自动──► FINISHED
 *      │                 │
 *      └──超时/取消──► CANCELED ◄┘（仅未支付可取消）
 *                        └──退款──► REFUNDED
 * </pre>
 *
 * <h3>三条业务规则（都在 SQL 条件里，不靠应用层判断）</h3>
 * <ol>
 *   <li>只有 PAID 能发货 —— 没付钱不能发货；</li>
 *   <li>只有 SHIPPED 能确认收货 —— 没发货不存在收货；</li>
 *   <li>已发货不能退款 —— 退款只允许 PAID，逆向流程（退货退款）是另一条链路。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private final OrderMapper orderMapper;
    private final MallProperties properties;
    private final Clock clock;

    /** 发货（管理端操作）。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean ship(String orderNo) {
        Order order = requireOrder(orderNo);
        int rows = orderMapper.markShipped(orderNo, LocalDateTime.now(clock));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_CONFLICT,
                    "只有已支付的订单可以发货，当前状态[" + order.getStatus().getDesc() + "]");
        }
        log.info("订单已发货 orderNo={}", orderNo);
        return true;
    }

    /** 确认收货（用户操作）。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean confirm(String orderNo, Long userId) {
        Order order = requireOrder(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该订单");
        }
        int rows = orderMapper.markFinished(orderNo, LocalDateTime.now(clock));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_STATUS_CONFLICT,
                    "只有已发货的订单可以确认收货，当前状态[" + order.getStatus().getDesc() + "]");
        }
        log.info("用户确认收货 orderNo={} userId={}", orderNo, userId);
        return true;
    }

    /**
     * 自动确认收货：发货超过 N 天仍未确认的，系统代为完成。
     *
     * <p>为什么需要它：确认收货直接影响<b>商家结算</b>与<b>售后时效</b>。
     * 如果完全依赖用户点击，大量订单会永远停在 SHIPPED，
     * 商家的钱永远结不出来。这是电商平台必须有的兜底任务。
     *
     * <p>与用户手动确认走同一条条件更新，
     * 所以"用户点和任务扫"同时发生时只有一个能成功，不会重复完成。
     *
     * @return 本次自动完成的订单数
     */
    @Transactional(rollbackFor = Exception.class)
    public int autoConfirmExpired(int batchSize) {
        LocalDateTime threshold = LocalDateTime.now(clock)
                .minusDays(properties.getTrade().getAutoConfirmDays());

        List<Order> shipped = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.SHIPPED)
                .le(Order::getShipTime, threshold)
                .last("LIMIT " + Math.max(1, batchSize)));

        int finished = 0;
        for (Order order : shipped) {
            if (orderMapper.markFinished(order.getOrderNo(), LocalDateTime.now(clock)) == 1) {
                finished++;
            }
        }
        if (finished > 0) {
            log.info("自动确认收货完成 {} 笔（阈值：发货超过 {} 天）",
                    finished, properties.getTrade().getAutoConfirmDays());
        }
        return finished;
    }

    private Order requireOrder(String orderNo) {
        Order order = orderMapper.selectOne(
                Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
