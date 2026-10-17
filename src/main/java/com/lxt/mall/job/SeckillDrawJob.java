package com.lxt.mall.job;

import com.lxt.mall.domain.entity.SeckillActivity;
import com.lxt.mall.seckill.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 抽签活动自动开奖任务。
 *
 * <p>在活动开始前 1 分钟把抽签跑掉，保证活动一开始中签者就能立刻下单。
 * 为什么不用"到点触发"：抽签是批处理，耗时随参与人数增长；
 * 提前一分钟执行可以吸收这点延迟，而用户感知不到。
 *
 * <p>任务本身是幂等的：{@link ReservationService#draw} 会先检查
 * 是否已有中签记录，重复触发直接跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.seckill-draw.enabled",
        havingValue = "true", matchIfMissing = true)
public class SeckillDrawJob {

    /** 提前多久开奖。 */
    private static final Duration AHEAD = Duration.ofMinutes(1);

    private final ReservationService reservationService;

    @Scheduled(fixedDelayString = "${mall.job.seckill-draw.interval:30s}")
    public void draw() {
        try {
            List<SeckillActivity> upcoming = reservationService.findUpcomingLotteryActivities(AHEAD);
            for (SeckillActivity activity : upcoming) {
                try {
                    int winners = reservationService.draw(activity.getId(), 1.0);
                    if (winners > 0) {
                        log.info("活动 {} 自动开奖完成，中签 {} 人", activity.getId(), winners);
                    }
                } catch (Exception e) {
                    // 单个活动失败不能影响其它活动
                    log.error("自动开奖失败 activityId={}", activity.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("抽签任务异常", e);
        }
    }
}
