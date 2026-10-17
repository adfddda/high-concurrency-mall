package com.lxt.mall.order;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.Order;
import com.lxt.mall.domain.entity.OrderItem;
import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.domain.entity.StockFlow;
import com.lxt.mall.domain.enums.OrderStatus;
import com.lxt.mall.domain.enums.StockChangeType;
import com.lxt.mall.domain.mapper.OrderItemMapper;
import com.lxt.mall.domain.mapper.OrderMapper;
import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import com.lxt.mall.domain.mapper.StockFlowMapper;
import com.lxt.mall.mq.MqMessageService;
import com.lxt.mall.mq.exception.DuplicateMessageException;
import com.lxt.mall.mq.exception.DuplicateOrderException;
import com.lxt.mall.mq.exception.NonRetryableMessageException;
import com.lxt.mall.order.event.OrderCreatedEvent;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * 订单落库（消费端唯一的写入口）。
 *
 * <p><b>整个方法必须是一个事务</b>，因为下面五件事要么全成、要么全不成：
 * <pre>
 *   1. 消费记录（幂等闸门）
 *   2. 数据库库存扣减（条件更新）
 *   3. 订单
 *   4. 订单项
 *   5. 库存流水
 * </pre>
 * 尤其第 1 条和第 2/3 条不能分开：如果先写消费记录、再单独开事务写订单，
 * 订单事务回滚后消费记录还在，消息重投时会被误判为"已消费"而直接 ACK，
 * <b>消息被永久丢弃且没有任何报错</b>——这是可靠消息系统里最隐蔽的一类 Bug。
 *
 * <p>每一步失败都抛出<b>语义明确</b>的异常，让消费端能做出正确决策
 * （ACK / 重试 / 进死信 / 补偿），而不是笼统地 catch(Exception)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SeckillActivityMapper activityMapper;
    private final StockFlowMapper stockFlowMapper;
    private final MqMessageService mqMessageService;
    private final ApplicationEventPublisher eventPublisher;
    private final MallProperties properties;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public Order createFromSeckill(SeckillOrderMessage msg) {
        int quantity = msg.getQuantity() == null ? 1 : msg.getQuantity();

        // ---------- 1. 幂等闸门 ----------
        // 先查一次是为了给出明确的异常语义；真正的并发正确性由
        // t_mq_message.uk_message_id 保证（两个消费者同时查到"不存在"是必然的）。
        if (mqMessageService.alreadyConsumed(msg.getMessageId())) {
            throw new DuplicateMessageException(msg.getMessageId());
        }
        try {
            mqMessageService.insertConsumeRecord(msg);
        } catch (DuplicateKeyException e) {
            // 并发场景：两个消费者同时处理同一条消息，都通过了上面的查询，
            // 然后一起走到 INSERT，其中一个撞上 uk_message_id。
            //
            // 必须在这里把它翻译成 DuplicateMessageException。
            // 如果放任 DuplicateKeyException 冒到消费端，它会落进通用的
            // "可重试异常"分支 → 投递延迟重试队列 → 几秒后重新消费 →
            // 这次 alreadyConsumed 返回 true 才被识别为重复。
            // 结果是白白多花两次重试和几秒延迟，还会在监控里制造
            // "消费失败率上升"的假告警，掩盖真正的问题。
            throw new DuplicateMessageException(msg.getMessageId());
        }

        // ---------- 2. 数据库库存扣减 ----------
        // Redis 库存只是"准入闸门"，真正的超卖防线在这里。
        // 条件更新 available_stock >= quantity，由 InnoDB 行锁保证原子性。
        int rows = activityMapper.deductStock(msg.getActivityId(), quantity);
        if (rows == 0) {
            SeckillActivity activity = activityMapper.selectById(msg.getActivityId());
            if (activity == null) {
                throw new NonRetryableMessageException("秒杀活动不存在: " + msg.getActivityId());
            }
            // 走到这里说明 Redis 放行了但数据库库存已经没了——
            // 两者出现漂移，是必须告警的信号。重试没有意义，所以是不可重试异常。
            throw new NonRetryableMessageException(
                    "数据库库存不足，Redis 与 DB 库存出现漂移: activityId=" + msg.getActivityId()
                            + " availableStock=" + activity.getAvailableStock());
        }

        // 同一事务内可以读到自己的更新，用它记录准确的变更前后值
        SeckillActivity afterDeduct = activityMapper.selectById(msg.getActivityId());
        int stockAfter = afterDeduct.getAvailableStock();
        int stockBefore = stockAfter + quantity;

        // ---------- 3. 订单 ----------
        Order order = buildOrder(msg, quantity);
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 走到这里说明消费记录没冲突、但订单唯一键冲突了，
            // 即 uk_user_activity / uk_order_no 命中。
            // 意味着 Redis 侧的一人一单判定被绕过，属于必须告警的一致性缺口。
            throw new DuplicateOrderException("订单唯一键冲突，疑似重复抢购: orderNo=" + msg.getOrderNo()
                    + " userId=" + msg.getUserId() + " activityId=" + msg.getActivityId()
                    + " cause=" + e.getMostSpecificCause().getMessage());
        }

        // ---------- 4. 订单项 ----------
        OrderItem item = new OrderItem();
        item.setId(IdWorker.getId());
        item.setOrderId(order.getId());
        item.setOrderNo(order.getOrderNo());
        item.setProductId(msg.getProductId());
        item.setProductTitle(msg.getProductTitle());
        item.setSeckillPrice(msg.getSeckillPrice());
        item.setQuantity(quantity);
        orderItemMapper.insert(item);

        // ---------- 5. 库存流水 ----------
        // uk_order_change(order_no, change_type) 保证同一订单的"扣减"只落一次，
        // 也把"这一单是否已经扣过库存"变成可查询的事实，供对账使用。
        StockFlow flow = new StockFlow();
        flow.setId(IdWorker.getId());
        flow.setActivityId(msg.getActivityId());
        flow.setOrderNo(msg.getOrderNo());
        flow.setUserId(msg.getUserId());
        flow.setChangeType(StockChangeType.DEDUCT);
        flow.setChangeNum(quantity);
        flow.setStockBefore(stockBefore);
        flow.setStockAfter(stockAfter);
        flow.setRemark("秒杀下单扣减");
        stockFlowMapper.insert(flow);

        // ---------- 6. 提交后的副作用 ----------
        // 由 OrderCreatedListener 在 AFTER_COMMIT 阶段处理
        eventPublisher.publishEvent(new OrderCreatedEvent(this, order, msg));

        log.info("秒杀订单创建成功 orderNo={} userId={} activityId={} amount={}",
                order.getOrderNo(), order.getUserId(), order.getActivityId(), order.getPayAmount());
        return order;
    }

    private Order buildOrder(SeckillOrderMessage msg, int quantity) {
        BigDecimal price = msg.getSeckillPrice();
        Order order = new Order();
        // 用雪花 ID 而不是自增：高并发插入下自增主键有 AUTO-INC 锁竞争，
        // 且自增在分库分表时无法全局唯一。
        order.setId(IdWorker.getId());
        order.setOrderNo(msg.getOrderNo());
        order.setUserId(msg.getUserId());
        order.setActivityId(msg.getActivityId());
        order.setProductId(msg.getProductId());
        order.setProductTitle(msg.getProductTitle());
        order.setSeckillPrice(price);
        order.setQuantity(quantity);
        order.setPayAmount(price.multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(OrderStatus.CREATED);
        order.setMessageId(msg.getMessageId());
        order.setExpireTime(java.time.LocalDateTime.now(clock).plus(properties.getSeckill().getOrderExpire()));
        return order;
    }
}
