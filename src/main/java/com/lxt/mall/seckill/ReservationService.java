package com.lxt.mall.seckill;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lxt.mall.common.api.ResultCode;
import com.lxt.mall.common.constant.RedisKeys;
import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.domain.entity.SeckillReservation;
import com.lxt.mall.domain.enums.SeckillMode;
import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import com.lxt.mall.domain.mapper.SeckillReservationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 秒杀预约与抽签。
 *
 * <h3>它要解决的问题</h3>
 * 直接抢购模式下，10 万人抢 200 件意味着 99.8% 的请求是"注定失败"的，
 * 但它们依然要经过网关、限流、Redis Lua 才能得到一句"已售罄"。
 * 这些请求消耗的是真金白银的带宽和 CPU，却没有任何业务价值。
 *
 * <h3>抽签把"瞬时并发"换成"两段低频"</h3>
 * <pre>
 *   ① 预约：活动开始前随时可做，请求量 = 参与人数，但可以提前几小时/几天分散掉；
 *   ② 抽签：后台批处理，一次 SELECT + N 次 UPDATE，与用户请求完全解耦；
 *   ③ 下单：只有中签者（数量 ≈ 库存 × 比例）能进入扣减链路。
 * </pre>
 * 于是扣减链路的 QPS 上限从"人群量级"降到"库存量级"，
 * 而且不需要用户拼手速——体验和防黄牛都更好。
 *
 * <h3>为什么资格码要在 Redis 里再存一份</h3>
 * 数据库是权威，但下单是热路径：如果每次下单都要回查 `t_seckill_reservation`，
 * 就等于把数据库放回了秒杀链路上。把中签的码同步到 Redis 集合后，
 * 扣减脚本可以在<b>同一个原子操作</b>里完成"校验 + 消费 + 扣库存"，
 * 既没有回表，也不存在"同一个码被两个并发请求同时判定有效"的窗口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final SeckillReservationMapper reservationMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /**
     * 预约（幂等）。
     *
     * <p>重复预约不报错、直接返回已有记录：用户在活动页反复点"预约"是常态，
     * 这里报错只会制造无意义的失败提示。
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillReservation reserve(Long activityId, Long userId) {
        SeckillActivity activity = requireLotteryActivity(activityId);
        if (!LocalDateTime.now(clock).isBefore(activity.getStartTime())) {
            throw new BusinessException(ResultCode.RESERVATION_CLOSED);
        }

        SeckillReservation existing = find(activityId, userId);
        if (existing != null) {
            return existing;
        }

        SeckillReservation reservation = new SeckillReservation();
        reservation.setId(IdWorker.getId());
        reservation.setActivityId(activityId);
        reservation.setUserId(userId);
        reservation.setStatus(0);
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException e) {
            // 并发点击：另一个请求先插入了，以已存在的那条为准
            return find(activityId, userId);
        }
        return reservation;
    }

    public SeckillReservation myReservation(Long activityId, Long userId) {
        return find(activityId, userId);
    }

    /**
     * 抽签。
     *
     * <p>名额 = 可售库存 × ratio（ratio 允许略大于 1，用于"超发一点资格、
     * 让没来付款的人被自然淘汰"的真实玩法；本项目默认 1.0）。
     *
     * <p>实现上刻意保持简单：一次捞出全部预约、内存里洗牌、逐条条件更新。
     * 演示规模（几千到几万人）够用。真实场景必须改成
     * "分页拉取 + 批量 UPDATE + 消息驱动"，否则一次抽签会把数据库压住——
     * 这段代码的价值在于讲清楚<b>为什么</b>要抽签，而不是性能。
     */
    @Transactional(rollbackFor = Exception.class)
    public int draw(Long activityId, double ratio) {
        SeckillActivity activity = requireLotteryActivity(activityId);

        Long alreadyDrawn = reservationMapper.selectCount(
                Wrappers.<SeckillReservation>lambdaQuery()
                        .eq(SeckillReservation::getActivityId, activityId)
                        .gt(SeckillReservation::getStatus, 0));
        if (alreadyDrawn != null && alreadyDrawn > 0) {
            log.info("活动已抽签，跳过 activityId={} 已处理={}", activityId, alreadyDrawn);
            return 0;
        }

        List<SeckillReservation> all = reservationMapper.selectList(
                Wrappers.<SeckillReservation>lambdaQuery()
                        .eq(SeckillReservation::getActivityId, activityId)
                        .eq(SeckillReservation::getStatus, 0));
        if (all.isEmpty()) {
            log.info("活动暂无预约，跳过抽签 activityId={}", activityId);
            return 0;
        }

        int capacity = (int) Math.floor(activity.getAvailableStock() * Math.max(0.1, ratio));
        int winners = Math.min(capacity, all.size());

        // 洗牌打乱顺序，保证结果与预约先后无关——这才是"抽签"而不是"排队"
        Collections.shuffle(all, new Random());

        int batch = (int) (System.currentTimeMillis() / 1000);
        Map<Long, String> codeByUser = new LinkedHashMap<>(Math.max(16, winners));

        for (int i = 0; i < all.size(); i++) {
            SeckillReservation r = all.get(i);
            if (i < winners) {
                String code = newCode();
                if (reservationMapper.markWon(activityId, r.getUserId(), code, batch) == 1) {
                    codeByUser.put(r.getUserId(), code);
                }
            } else {
                reservationMapper.markLost(activityId, r.getUserId(), batch);
            }
        }

        codeByUser.forEach((userId, code) -> cacheCode(activity, userId, code));
        log.info("抽签完成 activityId={} 参与={} 中签={} 库存={} 批次={}",
                activityId, all.size(), codeByUser.size(), activity.getAvailableStock(), batch);
        return codeByUser.size();
    }

    /** 供定时任务使用：找出即将开始、且尚未抽签的抽签类活动。 */
    public List<SeckillActivity> findUpcomingLotteryActivities(Duration ahead) {
        LocalDateTime now = LocalDateTime.now(clock);
        return activityMapper.selectList(Wrappers.<SeckillActivity>lambdaQuery()
                .eq(SeckillActivity::getMode, SeckillMode.LOTTERY.getCode())
                .gt(SeckillActivity::getEndTime, now)
                .le(SeckillActivity::getStartTime, now.plus(ahead)));
    }

    /**
     * 把资格码写进<b>中签者自己所属的那个桶</b>。
     *
     * <p>为什么按用户分桶而不是全放一个集合：分桶后扣减 Lua 只能操作同槽的 key，
     * 把所有人的码放一个全局集合会导致 CROSSSLOT。放各自桶里还有个额外好处——
     * 每个码只存在一份，不可能出现"两个桶各有一份、被消费两次"。
     */
    private void cacheCode(SeckillActivity activity, Long userId, String code) {
        String key = RedisKeys.seckillQualifyCodes(activity.getId(), stockService.bucketOf(userId));
        redis.opsForSet().add(key, code);
        // 与库存 key 同生命周期：活动结束后再留 1 小时，允许边界上的在途请求
        Duration ttl = Duration.between(LocalDateTime.now(clock), activity.getEndTime()).plusHours(1);
        if (ttl.isNegative() || ttl.toMinutes() < 10) {
            ttl = Duration.ofMinutes(30);
        }
        redis.expire(key, ttl);
    }

    private String newCode() {
        return "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private SeckillReservation find(Long activityId, Long userId) {
        return reservationMapper.selectOne(Wrappers.<SeckillReservation>lambdaQuery()
                .eq(SeckillReservation::getActivityId, activityId)
                .eq(SeckillReservation::getUserId, userId));
    }

    private SeckillActivity requireLotteryActivity(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (SeckillMode.of(activity.getMode()) != SeckillMode.LOTTERY) {
            throw new BusinessException(ResultCode.NOT_LOTTERY_ACTIVITY);
        }
        return activity;
    }
}
