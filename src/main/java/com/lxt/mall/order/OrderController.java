package com.lxt.mall.order;

import com.lxt.mall.common.api.PageResult;
import com.lxt.mall.common.api.Result;
import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.order.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final com.lxt.mall.seckill.SeckillStockService stockService;

    /**
     * 订单详情。
     *
     * <p>强制用上下文中的 userId 做归属校验，而不是从前端参数里取 userId。
     * 用一个"越权校验"换来的是：即便有人猜到了别人的订单号，
     * 也拿不到任何数据。
     */
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.queryVO(orderNo, UserContext.requireUserId()));
    }

    @GetMapping("/list")
    public Result<PageResult<OrderVO>> list(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(orderService.listByUser(UserContext.requireUserId(), pageNum, pageSize));
    }

    /** 模拟支付。真实场景由支付渠道异步回调触发，这里提供一个手动入口便于演示。 */
    @PostMapping("/{orderNo}/pay")
    public Result<Void> pay(@PathVariable String orderNo) {
        // 归属校验：不能替别人付款
        orderService.queryVO(orderNo, UserContext.requireUserId());
        orderService.pay(orderNo);
        return Result.ok();
    }

    /**
     * 用户主动取消（未支付订单）。
     *
     * <p>取消要同时做三件事，缺一不可：
     * <ol>
     *   <li>数据库：订单置为已取消，`active_flag` 置 NULL（否则无法再次购买）；</li>
     *   <li>数据库：回补活动库存（在 `cancelIfUnpaid` 的事务里完成）；</li>
     *   <li>Redis：回补秒杀库存 <b>并释放购买资格</b>。</li>
     * </ol>
     * 第 3 步过去是缺失的——用户取消后 Redis 侧库存不回补、已购集合也不清理，
     * 结果是他既买不了第二次，库存也要等对账任务才能修回来。
     */
    @PostMapping("/{orderNo}/cancel")
    public Result<Boolean> cancel(@PathVariable String orderNo) {
        orderService.queryVO(orderNo, UserContext.requireUserId());
        OrderService.CancelResult result = orderService.cancelIfUnpaid(orderNo, "用户主动取消");
        if (result.canceled() && result.activityId() != null) {
            // releaseQualification=true：取消后用户应能重新抢
            stockService.rollback(result.activityId(), result.userId(),
                    result.totalStock() == null ? Integer.MAX_VALUE : result.totalStock(),
                    true, false);
        }
        return Result.ok(result.canceled());
    }
}
