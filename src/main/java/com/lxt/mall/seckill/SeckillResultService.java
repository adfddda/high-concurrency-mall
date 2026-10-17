package com.lxt.mall.seckill;

import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.common.util.JsonUtils;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.seckill.dto.CachedOrder;
import com.lxt.mall.seckill.dto.SeckillResultStatus;
import com.lxt.mall.seckill.dto.SeckillResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 秒杀结果缓存。
 *
 * <p>存在的意义是<b>把"结果查询"从数据库上卸下来</b>。
 * 秒杀结束后会有大量用户高频轮询结果（每分钟几十次），
 * 如果每次都查库，等于用读流量把刚刚扛过写洪峰的数据库再打一遍。
 * 把结果写进 Redis，轮询基本不落库。
 *
 * <p>TTL 设为 30 分钟：足够覆盖用户等待 + 重试的窗口，
 * 又不会让结果数据长期占用内存。TTL 到期后查询会回落到数据库，
 * 所以<b>缓存丢失不会导致结果查不到</b>，只是慢一点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillResultService {

    private static final Duration RESULT_TTL = Duration.ofMinutes(30);

    /**
     * 订单快照单独一个更短的 TTL。
     *
     * <p>结果状态需要覆盖"用户等待 + 重试"的 30 分钟；但订单快照只服务于
     * "抢到之后马上看一眼"这个动作，5 分钟足够。更短的 TTL 意味着
     * 用户之后查看订单详情时走的是数据库，拿到的是最新状态（可能已支付），
     * 不会被轮询缓存里的旧状态误导。
     */
    private static final Duration ORDER_SNAPSHOT_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public void markQueueing(String orderNo) {
        write(orderNo, SeckillResultVO.queueing(orderNo));
    }

    public void markSuccess(String orderNo) {
        markSuccess(orderNo, null, null);
    }

    /**
     * 订单落库成功：同时写"结果状态"和"订单快照"。
     *
     * <p>订单快照让轮询接口完全不碰数据库——这是把 `/result` 从
     * "每次轮询一次 SELECT"变成"每次轮询一次 Redis GET"的关键。
     */
    public void markSuccess(String orderNo, Long userId, OrderVO order) {
        write(orderNo, SeckillResultVO.success(orderNo));
        if (userId != null && order != null) {
            try {
                redis.opsForValue().set(RedisKeys.seckillOrder(orderNo),
                        JsonUtils.toJson(new CachedOrder(userId, order)), ORDER_SNAPSHOT_TTL);
            } catch (Exception e) {
                // 快照写失败只是让轮询回落到数据库，不影响正确性
                log.warn("写入订单快照失败 orderNo={}（轮询会回落到数据库）", orderNo, e);
            }
        }
        log.debug("秒杀订单已落库 orderNo={}", orderNo);
    }

    /**
     * 读取订单快照。
     *
     * @return null 表示缓存里没有，调用方应回落到数据库
     */
    public CachedOrder getCachedOrder(String orderNo) {
        String json = redis.opsForValue().get(RedisKeys.seckillOrder(orderNo));
        if (json == null) {
            return null;
        }
        try {
            return JsonUtils.fromJson(json, CachedOrder.class);
        } catch (Exception e) {
            log.warn("订单快照反序列化失败 orderNo={}（将回落到数据库）", orderNo, e);
            return null;
        }
    }

    public void markFailed(String orderNo, String reason) {
        write(orderNo, SeckillResultVO.failed(orderNo, reason));
        log.warn("秒杀最终失败 orderNo={} reason={}", orderNo, reason);
    }

    /** @return null 表示 Redis 中没有结果（可能已过期），调用方应回落到数据库 */
    public SeckillResultVO get(String orderNo) {
        String json = redis.opsForValue().get(RedisKeys.seckillResult(orderNo));
        if (json == null) {
            return null;
        }
        try {
            SeckillResultVO vo = JsonUtils.fromJson(json, SeckillResultVO.class);
            if (vo != null && vo.getStatus() == SeckillResultStatus.QUEUEING) {
                // 每次读到"排队中"都续一下 TTL：
                // 用户还在等，结果不能被清理掉，否则轮询会突然拿到"查不到"。
                redis.expire(RedisKeys.seckillResult(orderNo), RESULT_TTL);
            }
            return vo;
        } catch (Exception e) {
            log.warn("秒杀结果反序列化失败 orderNo={}", orderNo, e);
            return null;
        }
    }

    private void write(String orderNo, SeckillResultVO vo) {
        try {
            redis.opsForValue().set(RedisKeys.seckillResult(orderNo), JsonUtils.toJson(vo), RESULT_TTL);
        } catch (Exception e) {
            // 结果缓存写失败不能影响订单创建这个主流程
            log.error("写入秒杀结果缓存失败 orderNo={}", orderNo, e);
        }
    }
}
