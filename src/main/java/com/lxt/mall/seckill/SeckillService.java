package com.lxt.mall.seckill;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.enums.SeckillMode;
import com.lxt.mall.metrics.MallMetrics;
import com.lxt.mall.mq.ReliableMessageSender;
import com.lxt.mall.order.dto.OrderVO;import com.lxt.mall.seckill.dto.CachedOrder;
import com.lxt.mall.seckill.dto.SeckillResponse;
import com.lxt.mall.seckill.dto.SeckillResultVO;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 秒杀下单主流程（全链路最热的一段代码）。
 *
 * <pre>
 *   ┌─ 1. 活动校验（本地缓存，不碰 Redis/DB）
 *   ├─ 2. 本地售罄标记（命中直接拒绝，不碰 Redis）
 *   ├─ 3. 生成订单号 + 消息ID
 *   ├─ 4. Redis Lua 原子扣减（库存校验 + 扣减 + 一人一单 + 在途计数）
 *   ├─ 5. 投递 MQ（Confirm/Return 异步兜底，不同步等待）
 *   └─ 6. 立即返回"已受理"
 * </pre>
 *
 * <h3>这个流程为什么能扛住高并发</h3>
 * <ol>
 *   <li><b>没有任何同步数据库操作</b>。整条链路只有一次 Redis 往返
 *       （Lua）和一次 MQ 发送，落库完全异步。</li>
 *   <li><b>判断尽量前移</b>。售罄、未开始这类必然失败的请求，
 *       在第一个 if 里就被挡掉，成本是几纳秒。</li>
 *   <li><b>扣减是原子的</b>。不需要分布式锁，也就没有锁等待、锁超时、
 *       锁续期这些问题。</li>
 *   <li><b>返回与结果解耦</b>。接口不等订单落库，RT 与数据库负载无关。</li>
 * </ol>
 *
 * <h3>代价（面试要主动说）</h3>
 * <ul>
 *   <li>用户拿到的是"排队中"，需要轮询才能知道结果，体验上不如同步返回。</li>
 *   <li>引入了 Redis 与 MySQL 的一致性问题，必须靠对账任务收敛。</li>
 *   <li>MQ 投递失败时要补偿 Redis，补偿逻辑本身又要幂等——复杂度实实在在地增加了。</li>
 * </ul>
 * 所以这个架构<b>只适用于秒杀这种"瞬时并发极高、可接受最终一致"的场景</b>。
 * 普通的下单流程应该老老实实同步落库，不要为了"高并发"而过度设计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final SeckillActivityService activityService;
    private final SeckillStockService stockService;
    private final SeckillResultService resultService;
    private final LocalSoldOutCache localSoldOutCache;
    private final SeckillRiskGuard riskGuard;
    private final com.lxt.mall.mq.MessagePublisher messageSender;
    private final MallProperties properties;
    private final MallMetrics metrics;
    private final Clock clock;

    public SeckillResponse seckill(Long activityId, Long userId) {
        return seckill(activityId, userId, null, null, null);
    }

    public SeckillResponse seckill(Long activityId, Long userId, String qualifyCode) {
        return seckill(activityId, userId, qualifyCode, null, null);
    }

    /**
     * 秒杀下单。
     *
     * @param qualifyCode 抽签活动的资格码；直接抢购活动传 null 即可
     * @param deviceId    客户端设备指纹（可选，用于风控）
     * @param clientIp    客户端 IP（仅用于风控记录）
     */
    public SeckillResponse seckill(Long activityId, Long userId, String qualifyCode,
                                   String deviceId, String clientIp) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        // ---------- 1. 活动校验（全部走本地缓存） ----------
        SeckillActivitySnapshot snapshot = activityService.requireSnapshot(activityId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(snapshot.getStartTime())) {
            metrics.seckillResult("not_started");
            throw new BusinessException(ResultCode.ACTIVITY_NOT_STARTED);
        }
        if (now.isAfter(snapshot.getEndTime())) {
            metrics.seckillResult("ended");
            throw new BusinessException(ResultCode.ACTIVITY_ENDED);
        }

        // ---------- 2. 本地售罄标记 ----------
        // 商品卖光后绝大多数请求注定失败，用进程内标记把它们挡在 Redis 之前。
        // 这只是"快速否定"优化，多实例之间不同步，最终仍以 Redis Lua 为准。
        if (localSoldOutCache.isSoldOut(activityId)) {
            metrics.seckillResult("sold_out_local");
            throw new BusinessException(ResultCode.SECKILL_SOLD_OUT);
        }

        // ---------- 3. 风控（设备维度） ----------
        // 放在 Lua 之前：被风控命中的请求连一次 Redis 扣减都不值得走
        SeckillRiskGuard.Decision risk = riskGuard.preCheck(activityId, userId, deviceId, clientIp);
        if (risk.blocked()) {
            metrics.seckillResult("risk_blocked");
            throw new BusinessException(ResultCode.SECKILL_RISK_BLOCKED);
        }

        // ---------- 4. 生成订单号与消息ID ----------
        // 订单号在这里就生成，是为了让接口能立刻把"凭证"返回给前端轮询。
        // 若等消费端生成，接口就只能返回"稍后查询"，前端无从查起。
        String orderNo = generateOrderNo();
        String messageId = generateMessageId();

        // ---------- 5. Redis Lua 原子扣减 ----------
        // 抽签活动必须在本地就先把"没带码"的请求挡掉：
        // 这类请求注定失败，没必要占用一次 Redis 往返。
        SeckillMode mode = SeckillMode.of(snapshot.getMode());
        if (mode == SeckillMode.LOTTERY && (qualifyCode == null || qualifyCode.isBlank())) {
            metrics.seckillResult("no_qualification");
            throw new BusinessException(ResultCode.SECKILL_NO_QUALIFICATION);
        }

        SeckillDeductResult deductResult = stockService.deduct(activityId, userId, mode, qualifyCode);
        switch (deductResult) {
            case SOLD_OUT -> {
                metrics.seckillResult("sold_out");
                throw new BusinessException(ResultCode.SECKILL_SOLD_OUT);
            }
            case REPEAT -> {
                metrics.seckillResult("repeat");
                throw new BusinessException(ResultCode.SECKILL_REPEAT);
            }
            case NO_QUALIFICATION -> {
                metrics.seckillResult("no_qualification");
                throw new BusinessException(ResultCode.SECKILL_NO_QUALIFICATION);
            }
            case NOT_PREPARED -> {
                // 活动未预热：可能是活动刚创建、Redis 被清空、或 TTL 意外到期。
                // 直接拒绝而不是"降级查数据库"——因为此时无法保证不超卖。
                metrics.seckillResult("not_prepared");
                log.error("秒杀活动未预热，请检查预热任务 activityId={}", activityId);
                throw new BusinessException(ResultCode.SECKILL_NOT_PREPARED);
            }
            case SUCCESS -> {
                metrics.seckillResult("deduct_success");
                // 只有真正抢到的请求才占用设备名额：
                // 失败请求不占用，否则等于"没抢到还把你拉黑"
                riskGuard.markDevice(activityId, deviceId);
            }
        }

        // ---------- 6. 投递消息 ----------
        SeckillOrderMessage message = new SeckillOrderMessage(
                messageId,
                orderNo,
                userId,
                activityId,
                snapshot.getProductId(),
                snapshot.getProductTitle(),
                snapshot.getSeckillPrice(),
                1,
                snapshot.getTotalStock(),
                LocalDateTime.now(clock));

        // 先落"排队中"的结果，再发消息。
        // 顺序不能反：如果先发消息、消费端极快地创建了订单并写入"成功"，
        // 这里的"排队中"就会把"成功"覆盖掉，前端会一直看到排队中。
        resultService.markQueueing(orderNo);

        try {
            messageSender.sendSeckillOrder(message);
        } catch (Exception e) {
            // sendSeckillOrder 内部已经完成了库存补偿，这里只需要告诉用户失败。
            metrics.seckillResult("mq_failed");
            log.error("秒杀消息投递失败，已回补库存 orderNo={} activityId={} userId={}",
                    orderNo, activityId, userId, e);
            throw new BusinessException(ResultCode.SECKILL_SUBMIT_FAILED);
        }

        metrics.seckillResult("accepted");
        return SeckillResponse.accepted(orderNo);
    }

    /**
     * 轮询结果时的缓存直读路径。
     *
     * <p>订单落库后会把订单快照写进 Redis，轮询接口优先读它，
     * 这样"一个用户每分钟轮询几十次"的流量完全不落到数据库上。
     *
     * @return null 表示缓存里没有（还没落库，或快照已过期），调用方应回落到数据库
     */
    public OrderVO queryCachedOrder(String orderNo, Long userId) {
        CachedOrder cached = resultService.getCachedOrder(orderNo);
        if (cached == null) {
            return null;
        }
        // 越权校验必须在缓存层就做：否则给别人的订单号也能读到快照
        if (userId != null && !userId.equals(cached.userId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该订单");
        }
        return cached.order();
    }

    /**
     * 查询抢购结果。
     *
     * <p>优先读 Redis 结果缓存（不落库），缓存里没有时回落到数据库。
     * 回落路径保证"缓存丢失不会导致查不到结果"，只是慢一点。
     */
    public SeckillResultVO queryResult(String orderNo, Long userId) {
        SeckillResultVO cached = resultService.get(orderNo);
        if (cached != null && cached.getStatus() != com.lxt.mall.seckill.dto.SeckillResultStatus.QUEUEING) {
            return cached;
        }
        // 排队中或缓存缺失：查一次数据库确认订单是否已经落库
        // （消费端写结果缓存失败时，这里就是唯一能修正的入口）
        return cached != null ? cached : SeckillResultVO.queueing(orderNo);
    }

    /**
     * 订单号生成。
     *
     * <p>用雪花 ID 而不是"时间戳 + 随机数"：
     * 随机数在极高并发下存在碰撞概率，而 {@code uk_order_no} 一旦命中冲突，
     * 会在消费端表现为"订单唯一键冲突"，被误判成重复抢购，非常难排查。
     * 雪花 ID 由 (时间戳 + 机器号 + 序列号) 组成，单机内严格递增且全局唯一。
     */
    private String generateOrderNo() {
        return "SO" + IdWorker.getIdStr();
    }

    private String generateMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
