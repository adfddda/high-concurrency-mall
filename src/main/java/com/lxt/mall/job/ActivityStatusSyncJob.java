package com.lxt.mall.job;

import com.lxt.mall.domain.mapper.SeckillActivityMapper;
import com.lxt.mall.seckill.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 活动状态同步任务。
 *
 * <h3>它修的是一个"沉默的"数据问题</h3>
 * {@code t_seckill_activity.status} 本来只是 {@code start_time / end_time}
 * 的派生值：开始前是 0，进行中是 1，结束后是 2。但原实现只在种子数据里
 * 写过一次，之后<b>没有任何代码再更新它</b>。结果是：
 * <ul>
 *   <li>活动结束三个月后，列表接口按 {@code status=1} 筛选仍然能筛出它；</li>
 *   <li>运营后台看到的是"进行中"，用户点进去却发现活动已结束；</li>
 *   <li>这类问题不会报错、不会告警，只会让运营对数据失去信任。</li>
 * </ul>
 *
 * <h3>为什么用"定期对齐"而不是"事件驱动"</h3>
 * 状态的唯一真相是时间，而时间没有事件。硬要事件驱动，就得给每个活动
 * 各排一个定时消息（活动数 × 2 条），复杂度远高于一条全表对齐语句。
 * 这里选择最简单、最不容易出错的方式：每 30 秒把"状态与时间不一致"的行纠正过来。
 *
 * <p>{@code WHERE status <> 新值} 让这条 UPDATE 在稳态下不产生任何写入，
 * 因此即便是全表更新也不会成为负担（活动表本身就是小表）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.activity-status.enabled",
        havingValue = "true", matchIfMissing = true)
public class ActivityStatusSyncJob {

    private final SeckillActivityMapper activityMapper;
    private final SeckillActivityService activityService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${mall.job.activity-status.interval:30s}")
    public void sync() {
        try {
            int changed = activityMapper.syncStatus(LocalDateTime.now(clock));
            if (changed > 0) {
                // 状态变了，本地快照里缓存的那份也要作废，
                // 否则最长 5 秒内仍会读到旧状态
                activityService.evictAllLocal();
                log.info("活动状态已同步：{} 个活动的状态发生变化", changed);
            }
        } catch (Exception e) {
            // 定时任务异常必须自己吞掉：抛出去会让调度线程停止后续执行
            log.error("活动状态同步任务异常", e);
        }
    }
}
