package com.lxt.mall.seckill;

import com.lxt.mall.common.api.Result;
import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.common.enums.RateLimitDimension;
import com.lxt.mall.order.OrderService;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.ratelimit.RateLimit;
import com.lxt.mall.seckill.dto.SeckillActivityVO;
import com.lxt.mall.seckill.dto.SeckillReservationVO;
import com.lxt.mall.seckill.dto.SeckillResponse;
import com.lxt.mall.seckill.dto.SeckillResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ReservationService reservationService;

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
    public Result<SeckillResponse> seckill(@PathVariable Long activityId,
                                           @RequestParam(required = false) String qualifyCode,
                                           @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                           jakarta.servlet.http.HttpServletRequest request) {
        Long userId = UserContext.requireUserId();
        return Result.ok(seckillService.seckill(activityId, userId, qualifyCode, deviceId, clientIp(request)));
    }

    /**
     * 取客户端 IP（仅用于风控记录）。
     *
     * <p>注意 {@code X-Forwarded-For} 可被伪造，所以它只能用于"记录/统计"，
     * 不能作为安全决策依据。真正的拦截依据是设备指纹 + 用户维度限购。
     */
    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 预约抽签活动的预约入口。
     *
     * <p>幂等：重复预约返回已有记录而不是报错——用户在活动页反复点"预约"是常态。
     * 只有活动开始之前可以预约，开始之后返回 20010。
     */
    @PostMapping("/activities/{activityId}/reservation")
    public Result<SeckillReservationVO> reserve(@PathVariable Long activityId) {
        return Result.ok(SeckillReservationVO.from(
                reservationService.reserve(activityId, UserContext.requireUserId())));
    }

    /** 查询我的预约/中签结果（含资格码）。 */
    @GetMapping("/activities/{activityId}/reservation")
    public Result<SeckillReservationVO> myReservation(@PathVariable Long activityId) {
        return Result.ok(SeckillReservationVO.from(
                reservationService.myReservation(activityId, UserContext.requireUserId())));
    }

    /**
     * 查询抢购结果（前端轮询）。
     *
     * <p>读取顺序：<b>Redis 订单快照 → MySQL</b>。
     * 快照命中时完全不碰数据库——秒杀结束后的轮询流量是典型的
     * "少量数据、极高频率"，让它打到数据库是最不划算的做法。
     *
     * <p>限流维度只取 USER：轮询本身是正常行为，按 IP 限流会误伤
     * NAT 出口后的整栋楼；按用户限流才对应"这个人在刷结果"。
     */
    @GetMapping("/result/{orderNo}")
    @RateLimit(rule = "seckill-result",
            dimensions = {RateLimitDimension.USER},
            message = "轮询太频繁了，请稍后再试")
    public Result<OrderVO> result(@PathVariable String orderNo) {
        Long userId = UserContext.requireUserId();

        // 1. 缓存直读（订单已落库的常见路径）
        OrderVO cached = seckillService.queryCachedOrder(orderNo, userId);
        if (cached != null) {
            return Result.ok(cached);
        }

        // 2. 回落数据库（快照过期 / 缓存被清 / 订单还没落库）
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
