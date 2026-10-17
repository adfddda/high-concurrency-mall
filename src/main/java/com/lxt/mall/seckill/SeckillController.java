package com.lxt.mall.seckill;

import com.lxt.mall.common.api.Result;
import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.common.enums.RateLimitDimension;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.ratelimit.RateLimit;
import com.lxt.mall.seckill.dto.SeckillActivityVO;
import com.lxt.mall.seckill.dto.SeckillResponse;
import com.lxt.mall.seckill.dto.SeckillResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillActivityService activityService;
    private final SeckillPreheatService preheatService;
    private final OrderService orderService;

    /** 活动列表（公开）。 */
    @GetMapping("/activities")
    public Result<List<SeckillActivityVO>> activities(@RequestParam(required = false) Integer status) {
        return Result.ok(activityService.listActivities(status));
    }

    @GetMapping("/activities/{activityId}")
    public Result<SeckillActivityVO> activity(@PathVariable Long activityId) {
        return Result.ok(activityService.getActivityVO(activityId));
    }

    /**
     * 秒杀下单。
     *
     * <p>限流维度同时开 USER 和 IP：
     * <ul>
     *   <li>开 USER 是因为这是最可靠的一维——伪造 IP 容易，伪造登录态难</li>
     *   <li>开 IP 是为了挡住"同一台机器控制大量账号"的脚本党</li>
     * </ul>
     * 只开其中任何一个都有明显短板。
     */
    @PostMapping("/activities/{activityId}/order")
    @RateLimit(rule = "seckill",
            dimensions = {RateLimitDimension.USER, RateLimitDimension.IP},
            message = "手速太快啦，请稍后再试")
    public Result<SeckillResponse> seckill(@PathVariable Long activityId) {
        Long userId = UserContext.requireUserId();
        return Result.ok(seckillService.seckill(activityId, userId));
    }

    /**
     * 查询抢购结果（前端轮询）。
     *
     * <p>结果查不到时会回落到数据库，所以 Redis 结果缓存丢失不会导致查不到订单。
     */
    @GetMapping("/result/{orderNo}")
    public Result<OrderVO> result(@PathVariable String orderNo) {
        Long userId = UserContext.requireUserId();
        try {
            return Result.ok(orderService.queryVO(orderNo, userId));
        } catch (com.lxt.mall.common.exception.BusinessException e) {
            // 订单还没落库是正常的（还在排队），不要把 404 抛给前端，
            // 而是返回 QUEUEING 语义，让前端继续轮询。
            SeckillResultVO vo = seckillService.queryResult(orderNo, userId);
            return Result.fail(com.lxt.mall.common.api.ResultCode.SECKILL_QUEUEING, vo.getMessage());
        }
    }

    /**
     * 手动预热（演示/运维用）。
     *
     * <p>生产环境这类接口必须放在鉴权之后并限制为运维角色，
     * 因为它会覆盖 Redis 里的库存快照，属于高危操作。
     */
    @PostMapping("/activities/{activityId}/preheat")
    public Result<Boolean> preheat(@PathVariable Long activityId) {
        return Result.ok(preheatService.preheat(activityId));
    }
}
