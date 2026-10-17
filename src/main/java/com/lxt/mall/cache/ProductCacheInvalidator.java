package com.lxt.mall.cache;

import com.lxt.mall.config.MallProperties;
import com.lxt.mall.product.event.ProductChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * 商品缓存失效。
 *
 * <p><b>为什么必须是 {@code AFTER_COMMIT}？</b>
 * 这是本项目里最容易写错、也最值得讲清楚的一处。
 *
 * <p>如果在事务提交<b>之前</b>删缓存，会有一个致命的时序问题：
 * <pre>
 *   T1: 开启事务，UPDATE t_product ...
 *   T1: 删除缓存
 *   T2: 读请求 → 缓存 miss → 查 DB（因为 T1 未提交，读到的是【旧值】）
 *   T2: 把旧值写回缓存            ← 关键：缓存里重新出现了旧值
 *   T1: 事务提交（DB 变成新值）
 *   结果：数据库是新值，缓存是旧值，且因为缓存里有值，
 *         在 TTL 到期之前【没有任何机制会去修正它】
 * </pre>
 * 把删除动作推迟到 AFTER_COMMIT，读取方就不可能读到未提交的旧值再回填，
 * 上述时序被彻底消除。
 *
 * <p><b>为什么还要"延迟双删"？</b>
 * 即使 AFTER_COMMIT 之后才删，仍存在一个更窄的竞态窗口：
 * <pre>
 *   T2: 读请求 → 缓存 miss → 查 DB 拿到【旧值】（此时 T1 还没提交）
 *   T1: 提交事务
 *   T1: AFTER_COMMIT 删缓存
 *   T2: 把刚才读到的旧值写回缓存   ← 删除发生在回填【之前】，删了个寂寞
 * </pre>
 * 应对办法是在删除之后再过一小段时间（本项目 500ms）<b>再删一次</b>，
 * 把上述窗口内被回填的脏数据清掉。本质上是用"最终一致"换取实现简单，
 * 代价是这段时间内仍可能读到旧值。
 *
 * <p><b>更强一致性的做法（本项目未实现，但要知道）</b>：
 * 订阅 MySQL binlog（Canal / Debezium），把缓存删除动作与业务事务
 * 彻底解耦，按 binlog 的顺序消费。这样连"应用层忘了删缓存"的漏网
 * 都能覆盖（比如有人直接连库改数据）。代价是引入一个额外的中间件
 * 和一条需要长期维护的链路。
 */
@Slf4j
@Component
public class ProductCacheInvalidator {

    private final ProductCacheService cacheService;
    private final MallProperties properties;
    private final ThreadPoolTaskScheduler scheduler;

    public ProductCacheInvalidator(ProductCacheService cacheService,
                                   MallProperties properties,
                                   @Qualifier("cacheDoubleDeleteScheduler") ThreadPoolTaskScheduler scheduler) {
        this.cacheService = cacheService;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 事务提交后失效缓存。
     *
     * <p>{@code fallbackExecution = true}：没有事务上下文时（比如单元测试里
     * 直接调用 Service，或者将来某次调用不在事务中）也要执行。
     * 默认值是 false——那样事件会被<b>静默丢弃</b>，
     * 表现为"改了数据但缓存一直不更新"，且没有任何报错，极难排查。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductChanged(ProductChangedEvent event) {
        Long productId = event.getProductId();
        cacheService.invalidate(productId);

        long delayMillis = properties.getCache().getDoubleDeleteDelay().toMillis();
        if (delayMillis <= 0) {
            return;
        }
        try {
            scheduler.schedule(() -> {
                try {
                    cacheService.invalidate(productId);
                    log.debug("延迟双删完成 productId={}", productId);
                } catch (Exception e) {
                    log.warn("延迟双删失败 productId={}（不影响主流程，TTL 到期后会自愈）", productId, e);
                }
            }, Instant.now().plusMillis(delayMillis));
        } catch (Exception e) {
            // 调度失败不影响主流程：第一次删除已经生效，
            // 即使窗口内被回填了脏数据，也会在缓存 TTL 到期后自然修正。
            log.warn("延迟双删调度失败 productId={}", productId, e);
        }
    }
}
