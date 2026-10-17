package com.lxt.mall.job;

import com.lxt.mall.seckill.SeckillPreheatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 秒杀活动预热任务。
 *
 * <p>两个触发点：
 * <ol>
 *   <li><b>应用启动时</b>（{@link ApplicationRunner}）：把正在进行和即将开始的
 *       活动全部预热。必要性在于 <b>Redis 可能被清空/重启</b>——
 *       如果只依赖"活动创建时预热一次"，Redis 一重启所有活动就都处于
 *       "未预热"状态，秒杀接口会全线报 20006。</li>
 *   <li><b>每分钟轮询</b>：把未来 10 分钟内开始的活动提前预热。
 *       为什么是 10 分钟？要留出足够的余量——如果预热刚好卡在活动开始那一刻，
 *       几万个并发请求会在缓存建好之前先打到数据库。</li>
 * </ol>
 *
 * <p>预热是幂等的（重复写同样的值），所以启动预热和定时预热重叠也不会出问题。
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.preheat.enabled", havingValue = "true", matchIfMissing = true)
public class SeckillPreheatJob implements ApplicationRunner {

    /** 提前预热的窗口。 */
    private static final Duration PREHEAT_AHEAD = Duration.ofMinutes(10);

    private final SeckillPreheatService preheatService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = preheatService.preheatUpcoming(PREHEAT_AHEAD);
            log.info("启动预热完成，共预热 {} 个活动", count);
        } catch (Exception e) {
            // 预热失败不阻塞启动：活动开始前的定时任务还有机会补上，
            // 而且秒杀接口在未预热时会安全地拒绝请求（不会超卖）。
            log.error("启动预热失败，将由定时任务重试", e);
        }
    }

    @Scheduled(fixedDelayString = "${mall.job.preheat.interval:60000}", initialDelay = 60_000)
    public void preheatUpcoming() {
        try {
            preheatService.preheatUpcoming(PREHEAT_AHEAD);
        } catch (Exception e) {
            log.error("定时预热任务异常", e);
        }
    }
}
