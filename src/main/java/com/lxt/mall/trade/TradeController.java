package com.lxt.mall.trade;

import com.lxt.mall.common.api.Result;
import com.lxt.mall.common.context.UserContext;
import com.lxt.mall.order.dto.OrderVO;
import com.lxt.mall.trade.dto.CreateOrderRequest;
import com.lxt.mall.trade.dto.CartItemVO;
import com.lxt.mall.trade.dto.CouponVO;
import com.lxt.mall.trade.dto.PaymentVO;
import com.lxt.mall.trade.dto.RefundVO;
import com.lxt.mall.trade.dto.SkuVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 普通交易接口（SKU 下单 / 支付 / 退款）。
 *
 * <p>与 {@code /api/seckill} 的区别：这里是<b>同步</b>的，下单立即返回订单号，
 * 支付通过"创建支付单 + 渠道回调"两步完成，退款幂等。
 * 秒杀接口返回的是"排队中"，需要轮询结果——同一个订单域，两种交互模型。
 */
@RestController
@RequestMapping("/api/trade")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final CartService cartService;
    private final CouponService couponService;

    /** 商品的可售 SKU 列表（公开接口）。 */
    @GetMapping("/skus")
    public Result<List<SkuVO>> skus(@RequestParam Long productId) {
        return Result.ok(tradeService.listSkus(productId));
    }

    /** 普通下单：同步扣库存、同步落库，立即返回订单（可带优惠券）。 */
    @PostMapping("/orders")
    public Result<OrderVO> createOrder(@RequestBody @Valid CreateOrderRequest request) {
        return Result.ok(tradeService.createOrder(
                UserContext.requireUserId(), request.getSkuId(), request.getQuantity(),
                request.getCouponId()));
    }

    // ========================================================================
    // 购物车
    // ========================================================================

    @GetMapping("/cart")
    public Result<List<CartItemVO>> cart() {
        return Result.ok(cartService.list(UserContext.requireUserId()));
    }

    @PostMapping("/cart")
    public Result<CartItemVO> addToCart(@RequestParam Long skuId,
                                        @RequestParam(defaultValue = "1") int quantity) {
        return Result.ok(cartService.add(UserContext.requireUserId(), skuId, quantity));
    }

    @PutMapping("/cart/{skuId}")
    public Result<CartItemVO> updateCart(@PathVariable Long skuId, @RequestParam int quantity) {
        return Result.ok(cartService.updateQuantity(UserContext.requireUserId(), skuId, quantity));
    }

    @DeleteMapping("/cart/{skuId}")
    public Result<Void> removeFromCart(@PathVariable Long skuId) {
        cartService.remove(UserContext.requireUserId(), skuId);
        return Result.ok();
    }

    @DeleteMapping("/cart")
    public Result<Void> clearCart() {
        cartService.clear(UserContext.requireUserId());
        return Result.ok();
    }

    // ========================================================================
    // 优惠券
    // ========================================================================

    @GetMapping("/coupons")
    public Result<List<CouponVO>> myCoupons(@RequestParam(required = false) Integer status) {
        return Result.ok(couponService.listMine(UserContext.requireUserId(), status));
    }

    /** 领券。幂等：重复领取返回已有券而不是报错。 */
    @PostMapping("/coupons/{templateId}/claim")
    public Result<CouponVO> claimCoupon(@PathVariable Long templateId) {
        return Result.ok(couponService.claim(templateId, UserContext.requireUserId()));
    }

    /**
     * 发起支付：创建支付单。
     *
     * <p>真实流程是拿着 paymentNo 去唤起渠道（收银台 / 二维码），
     * 渠道处理完回调 {@code /payment/callback}。这里把支付单直接返回，
     * 方便用 curl 模拟整条链路。
     */
    @PostMapping("/orders/{orderNo}/pay")
    public Result<PaymentVO> pay(@PathVariable String orderNo) {
        return Result.ok(paymentService.createPayment(orderNo, UserContext.requireUserId()));
    }

    /**
     * 渠道回调（模拟）。
     *
     * <p>真实实现必须做：验签 → 幂等 → 金额校验 → 推进订单状态。
     * 本项目保留了后三步，验签用注释说明——没有真实渠道时，
     * 验签只能是个假动作。
     *
     * <p>这个接口不走登录拦截器（渠道不会带用户 token），
     * 安全性完全依赖签名，这也是为什么真实场景里验签绝不能省。
     */
    @PostMapping("/payment/callback")
    public Result<Boolean> paymentCallback(@RequestParam String paymentNo,
                                           @RequestParam String channelTradeNo,
                                           @RequestParam BigDecimal amount) {
        return Result.ok(paymentService.handleCallback(paymentNo, channelTradeNo, amount));
    }

    /** 退款（全额，幂等）。 */
    @PostMapping("/orders/{orderNo}/refund")
    public Result<RefundVO> refund(@PathVariable String orderNo,
                                   @RequestParam(required = false) String reason) {
        return Result.ok(refundService.refund(
                orderNo, reason == null ? "用户申请退款" : reason, UserContext.requireUserId()));
    }
}
