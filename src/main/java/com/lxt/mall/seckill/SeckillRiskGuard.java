package com.lxt.mall.seckill;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.config.MallProperties;
import com.lxt.mall.domain.entity.SeckillRiskHit;
import com.lxt.mall.domain.mapper.SeckillRiskHitMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 秒杀风控（设备维度）。
 *
 * <h3>它补的是限流挡不住的那一类</h3>
 * 限流按 IP/用户限速，能挡住"一个人狂点"和"一个 IP 打爆"。
 * 但真实黄牛是：<b>一台设备、100 个账号、轮流点</b>。
 * 每个账号都合法、每次请求都在限额内，限流完全拦不住，
 * 只能靠"设备指纹"这个维度收敛——同一台设备在一个活动里只能中一次。
 *
 * <h3>为什么"检查"和"标记"要分成两步</h3>
 * <pre>
 *   请求进来 → 检查设备是否已用过（只读，不写入）
 *            → 扣减成功后才标记设备
 * </pre>
 * 如果检查时就写入标记，那么一个"未中签/已售罄"的失败请求也会把设备消耗掉，
 * 用户明明没抢到，却再也没资格抢了——这是把防黄牛做成了误伤。
 * <b>只有真正抢到的请求才应该占用设备名额。</b>
 *
 * <h3>为什么设备是"可选"参数</h3>
 * App/H5 能拿到稳定的设备指纹，而第三方或老客户端可能没有。
 * 没有设备号时不拦截（放行），但会保留 IP 维度的限流兜底——
 * 宁可漏掉一部分，也不要因为拿不到指纹就把正常用户全部拒之门外。
 *
 * <h3>为什么拦截要落库</h3>
 * 拦截日志是风控唯一能自证的证据：能算出拦截率，能在误杀时回溯，
 * 也能在面试时回答"你这套风控到底挡住了多少"。
 * 只记录被拦截的请求，正常请求不写，体量可控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillRiskGuard {

    private static final String REASON_DEVICE_REPEAT = "DEVICE_REPEAT";
    private static final Duration RISK_KEY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final SeckillRiskHitMapper hitMapper;
    private final MallProperties properties;

    /** 风控判定结果。 */
    public record Decision(boolean blocked, String reason) {
        public static Decision allow() {
            return new Decision(false, null);
        }

        public static Decision block(String reason) {
            return new Decision(true, reason);
        }
    }

    /**
     * 下单前检查（只读，不做任何标记）。
     *
     * <p>放在 Lua 扣减之前是刻意的：被风控拦下的请求连 Redis 扣减都不需要走，
     * 这本身就是"用最便宜的手段挡掉最脏的流量"。
     */
    public Decision preCheck(Long activityId, Long userId, String deviceId, String clientIp) {
        if (!properties.getRisk().isEnabled() || deviceId == null || deviceId.isBlank()) {
            return Decision.allow();
        }
        String device = normalize(deviceId);
        Boolean used = redis.opsForSet()
                .isMember(RedisKeys.seckillRiskDevices(activityId), device);
        if (Boolean.TRUE.equals(used)) {
            record(activityId, userId, device, clientIp, REASON_DEVICE_REPEAT);
            return Decision.block(REASON_DEVICE_REPEAT);
        }
        return Decision.allow();
    }

    /**
     * 扣减成功之后占用设备名额。
     *
     * <p>调用点必须在"Lua 返回 SUCCESS"之后、且不影响主流程——
     * 标记失败最多是风控变弱，不能让一次已经成功的抢购失败。
     */
    public void markDevice(Long activityId, String deviceId) {
        if (!properties.getRisk().isEnabled() || deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            String key = RedisKeys.seckillRiskDevices(activityId);
            redis.opsForSet().add(key, normalize(deviceId));
            redis.expire(key, RISK_KEY_TTL);
        } catch (Exception e) {
            log.warn("设备标记失败 activityId={}（不影响订单）", activityId, e);
        }
    }

    /** 设备指纹规范化：去掉空白与大小写差异，避免同一个设备被当成多个。 */
    private String normalize(String deviceId) {
        String trimmed = deviceId.trim().toLowerCase();
        int max = Math.max(8, properties.getRisk().getDeviceIdMaxLength());
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private void record(Long activityId, Long userId, String deviceId, String clientIp, String reason) {
        try {
            SeckillRiskHit hit = new SeckillRiskHit();
            hit.setId(IdWorker.getId());
            hit.setActivityId(activityId);
            hit.setUserId(userId);
            hit.setDeviceId(deviceId);
            hit.setClientIp(clientIp);
            hit.setReason(reason);
            hitMapper.insert(hit);
            log.info("风控拦截 activityId={} userId={} reason={}", activityId, userId, reason);
        } catch (Exception e) {
            // 埋点失败不能影响主流程，也不能让风控变成故障点
            log.warn("风控记录写入失败 activityId={} userId={}", activityId, userId, e);
        }
    }
}
