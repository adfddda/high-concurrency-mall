package com.lxt.mall.job;

import com.lxt.mall.trade.GroupBuyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拼团超时关闭任务。
 *
 * <p>为什么必须有它：未成团的团如果没人处理，用户的钱会一直挂在平台、
 * 库存会一直被占用。这类"到期必须有人做点什么"的业务，
 * 一定要有<b>按状态扫描</b>的任务兜底，而不是只靠"用户打开页面时触发"。
 *
 * <p>与订单超时关单是同一套思路：消息/事件负责"快"，扫描任务负责"不漏"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.job.group-buy-expire.enabled",
        havingValue = "true", matchIfMissing = true)
public class GroupBuyExpireJob {

    private static final int BATCH_SIZE = 100;

    private final GroupBuyService groupBuyService;

    @Scheduled(fixedDelayString = "${mall.job.group-buy-expire.interval:1m}")
    public void closeExpired() {
        try {
            int closed = groupBuyService.closeExpired(BATCH_SIZE);
            if (closed > 0) {
                log.info("拼团超时任务处理 {} 个团", closed);
            }
        } catch (Exception e) {
            log.error("拼团超时任务异常", e);
        }
    }
}
