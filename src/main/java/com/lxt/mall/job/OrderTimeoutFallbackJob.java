package com.lxt.mall.job;

import com.lxt.mall.config.MallProperties;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.seckill.SeckillStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单超时关单兜底扫描。
 *
 * <h3>为什么有了延迟队列还要这个任务</h3>
 * 延迟队列（TTL + DLX）是一条很优雅的路径，但它有几种失效方式：
 * <ul>
 *   <li>投递延迟消息时 broker 不可用（已经记录日志但不影响下单）</li>
 *   <li>消息在队列里因为队列被误删/重建而丢失</li>
 *   <li>消费者处理失败被拒绝</li>
 *   <li>RabbitMQ 数据未持久化，broker 异常宕机</li>
 * </ul>
 * 这些情况下如果没有兜底，订单会永久停留在"待支付"，
 * 对应的库存被永久占用——用户看到的是"这件商品明明卖完了，
 * 但从来没被支付过"。
 *
 * <p><b>设计原则：任何"靠消息触发的关键动作"，都要有一个按状态扫描的兜底任务。</b>
 * 消息负责"快"，扫描负责"对"，两者缺一不可。
 * 扫描条件是 {@code status = 0 AND expire_time <= now}，
 * 而 {@code idx_status_expire} 索引就是为这个查询建的。
 *
 * <p>与延迟队列天然幂等：两边都调 {@code cancelIfUnpaid}，
 * 靠数据库的条件更新保证只有一个能成功取消。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.order-timeout-fallback.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTimeoutFallbackJob {

    /** 单次扫描上限：一次捞太多会把数据库连接池占满，分批处理更稳。 */
    private static final int BATCH_SIZE = 200;

    private final OrderService orderService;
    private final SeckillStockService stockService;
    private final MallProperties properties;
    private final MallMetrics metrics;

    @Scheduled(fixedDelayString = "${mall.job.order-timeout-fallback.interval:60000}", initialDelay = 30_000)
    public void closeExpiredOrders() {
        try {
            List<com.lxt.mall.domain.entity.Order> expired = orderService.findExpiredUnpaid(BATCH_SIZE);
            if (expired.isEmpty()) {
                return;
            }
            int closed = 0;
            for (com.lxt.mall.domain.entity.Order order : expired) {
                try {
                    OrderService.CancelResult result = orderService.cancelIfUnpaid(
                            order.getOrderNo(), "兜底扫描：超时未支付");
                    if (result.canceled()) {
                        stockService.rollback(result.activityId(), result.userId(),
                                result.totalStock() == null ? Integer.MAX_VALUE : result.totalStock(),
                                false, false);
                        closed++;
                    }
                } catch (Exception e) {
                    log.error("兜底关单失败 orderNo={}", order.getOrderNo(), e);
                }
            }
            if (closed > 0) {
                log.info("兜底扫描关闭了 {} 个超时订单（扫描到 {} 个候选）", closed, expired.size());
                for (int i = 0; i < closed; i++) {
                    metrics.mqEvent("order-timeout", "closed_by_fallback");
                }
            }
            if (expired.size() >= BATCH_SIZE) {
                // 说明积压超过了一批的处理能力，需要调大频率或排查消费端
                log.warn("超时订单扫描达到批次上限 {}，可能存在积压，请检查延迟队列消费情况", BATCH_SIZE);
            }
        } catch (Exception e) {
            log.error("订单超时兜底扫描异常", e);
        }
    }
}
