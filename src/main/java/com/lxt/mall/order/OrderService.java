package com.lxt.mall.order;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lxt.mall.common.api.PageResult;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.domain.entity.StockFlow;
import com.lxt.mall.domain.enums.StockChangeType;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import com.lxt.mall.domain.mapper.StockFlowMapper;
import com.lxt.mall.order.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单服务。
 *
 * <h3>状态流转一律用「条件更新」</h3>
 * <pre>
 *   UPDATE t_order SET status = 2 WHERE order_no = ? AND status = 0
 * </pre>
 * 靠影响行数判断竞争结果。绝不能写成"先 SELECT 判断状态、再 UPDATE"：
 * 两步之间有窗口，支付回调和超时关单可能同时通过判断，
 * 结果一个订单既被置为已支付又被置为已取消，库存还会被错误回补。
 *
 * <h3>库存回补挂在"取消成功"的分支上</h3>
 * 只有 {@code cancelOrder} 返回 1（即本次调用真正把状态从 0 改成 2）才回补库存。
 * 这样"超时关单"、"用户主动取消"、"兜底扫描"三条路径并发触发时，
 * 也只会回补一次——幂等性由数据库的状态机保证，而不是靠应用层加锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final StockFlowMapper stockFlowMapper;
    private final Clock clock;

    /**
     * 取消结果。
     *
     * @param canceled 本次调用是否真正完成了取消（false 表示订单不存在或状态已变更）
     */
    public record CancelResult(boolean canceled, String orderNo, Long activityId, Long userId,
                               Integer quantity, Integer totalStock) {

        public static CancelResult notCanceled(String orderNo) {
            return new CancelResult(false, orderNo, null, null, null, null);
        }
    }

    /**
     * 取消未支付订单并归还数据库库存（同一个事务）。
     *
     * <p>Redis 库存的归还由调用方在<b>事务提交后</b>执行。
     * 为什么不放在同一个方法里？因为 Redis 不参与数据库事务，
     * 放在事务内一旦后续回滚，Redis 的修改无法撤销。
     * 正确顺序是：先让数据库事务成功提交，再做 Redis 补偿；
     * 万一 Redis 那一步失败，对账任务会把库存修回来。
     * <b>数据库是权威，Redis 是可重建的投影</b>——顺序不能反。
     */
    @Transactional(rollbackFor = Exception.class)
    public CancelResult cancelIfUnpaid(String orderNo, String remark) {
        Order order = findByOrderNo(orderNo);
        if (order == null) {
            return CancelResult.notCanceled(orderNo);
        }

        int rows = orderMapper.cancelOrder(orderNo, LocalDateTime.now(clock));
        if (rows == 0) {
            // 订单已经支付或已经被取消，本次无事可做。
            // 这是并发场景下的正常分支，不是错误。
            log.debug("订单状态已变更，跳过取消 orderNo={} currentStatus={}", orderNo, order.getStatus());
            return CancelResult.notCanceled(orderNo);
        }

        int quantity = order.getQuantity() == null ? 1 : order.getQuantity();

        // 条件回补：available_stock + quantity <= total_stock
        int restored = activityMapper.rollbackStock(order.getActivityId(), quantity);
        if (restored == 0) {
            // 要么活动不存在，要么回补后会超总库存（说明之前已经回补过）。
            // 不抛异常：订单取消本身是成功的，库存异常交给对账。
            log.error("数据库库存回补失败（可能已回补或超总库存）orderNo={} activityId={} quantity={}",
                    orderNo, order.getActivityId(), quantity);
        }

        SeckillActivity activity = activityMapper.selectById(order.getActivityId());
        Integer totalStock = activity == null ? null : activity.getTotalStock();

        // 库存流水：uk_order_change 保证同一订单的回补只落一次。
        // 如果真的撞了唯一键，说明前面有并发路径已经回补过，
        // 这里必须显式捕获并继续——抛出会导致整个取消事务回滚，
        // 让一个已经完成的取消操作前功尽弃。
        try {
            StockFlow flow = new StockFlow();
            flow.setId(IdWorker.getId());
            flow.setActivityId(order.getActivityId());
            flow.setOrderNo(orderNo);
            flow.setUserId(order.getUserId());
            flow.setChangeType(StockChangeType.ROLLBACK);
            flow.setChangeNum(quantity);
            flow.setStockBefore(activity == null ? 0 : activity.getAvailableStock() - quantity);
            flow.setStockAfter(activity == null ? 0 : activity.getAvailableStock());
            flow.setRemark(remark);
            stockFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            log.warn("库存回补流水已存在，跳过写入 orderNo={}", orderNo);
        }

        log.info("订单已取消并归还数据库库存 orderNo={} activityId={} quantity={}",
                orderNo, order.getActivityId(), quantity);
        return new CancelResult(true, orderNo, order.getActivityId(), order.getUserId(), quantity, totalStock);
    }

    /**
     * 支付（模拟）。
     *
     * @return true 表示本次调用真正完成了状态流转
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean pay(String orderNo) {
        Order order = findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        int rows = orderMapper.markPaid(orderNo, LocalDateTime.now(clock));
        if (rows == 0) {
            // 重复回调，或者回调晚于超时关单
            throw new BusinessException(ResultCode.ORDER_STATUS_CONFLICT,
                    "订单当前状态为[" + order.getStatus().getDesc() + "]，无法支付");
        }
        return true;
    }

    public Order findByOrderNo(String orderNo) {
        return orderMapper.selectOne(Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, orderNo));
    }

    public OrderVO queryVO(String orderNo, Long userId) {
        Order order = findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        // 越权校验：只能查自己的订单。
        // 这是最容易被忽略的漏洞之一——用订单号做查询参数却不校验归属，
        // 攻击者遍历订单号就能拿到别人的订单信息。
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该订单");
        }
        return OrderVO.from(order);
    }

    public PageResult<OrderVO> listByUser(Long userId, long pageNum, long pageSize) {
        Page<Order> page = new Page<>(pageNum, pageSize);
        IPage<Order> result = orderMapper.selectPage(page, Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getId));
        List<OrderVO> records = result.getRecords().stream().map(OrderVO::from).toList();
        return PageResult.of(result.getTotal(), pageNum, pageSize, records);
    }

    /** 兜底扫描用：查出已过期但仍未支付的订单号。 */
    public List<Order> findExpiredUnpaid(int limit) {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, com.lxt.mall.domain.enums.OrderStatus.CREATED)
                .le(Order::getExpireTime, LocalDateTime.now(clock))
                .last("LIMIT " + limit));
    }
}
