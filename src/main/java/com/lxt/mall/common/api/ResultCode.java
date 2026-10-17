package com.lxt.mall.common.api;

/**
 * 统一错误码。
 *
 * <p>分段约定：让前端/告警系统只看数字就能判断性质，不用解析文案。
 * <pre>
 *   0        成功
 *   4xxxx   客户端问题（参数、鉴权、限流）
 *   5xxxx   服务端问题
 *   1xxxx   用户域业务错误
 *   2xxxx   秒杀域业务错误
 *   3xxxx   订单域业务错误
 * </pre>
 */
public enum ResultCode {

    SUCCESS(0, "成功"),

    // ---------------- 通用 ----------------
    PARAM_ERROR(40000, "参数校验失败"),
    UNAUTHORIZED(40100, "未登录或登录状态已失效"),
    FORBIDDEN(40300, "无权访问"),
    NOT_FOUND(40400, "资源不存在"),
    METHOD_NOT_ALLOWED(40500, "请求方法不支持"),
    RATE_LIMITED(42900, "请求过于频繁，请稍后再试"),
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(50300, "依赖服务不可用"),

    // ---------------- 用户域 1xxxx ----------------
    USERNAME_EXISTS(10001, "用户名已被注册"),
    USER_NOT_FOUND(10002, "用户不存在"),
    PASSWORD_ERROR(10003, "用户名或密码错误"),
    USER_DISABLED(10004, "账号已被禁用"),

    // ---------------- 秒杀域 2xxxx ----------------
    ACTIVITY_NOT_FOUND(20001, "秒杀活动不存在"),
    ACTIVITY_NOT_STARTED(20002, "秒杀尚未开始"),
    ACTIVITY_ENDED(20003, "秒杀已经结束"),
    SECKILL_SOLD_OUT(20004, "商品已售罄"),
    SECKILL_REPEAT(20005, "您已参与过本次秒杀，每人限购一件"),
    SECKILL_NOT_PREPARED(20006, "秒杀活动正在准备中，请稍后重试"),
    SECKILL_QUEUEING(20007, "排队中，请稍后查询结果"),
    SECKILL_SUBMIT_FAILED(20008, "抢购请求提交失败，请重试"),
    SECKILL_NO_QUALIFICATION(20009, "您没有中签资格，或资格码已被使用"),
    RESERVATION_CLOSED(20010, "预约已截止"),
    NOT_LOTTERY_ACTIVITY(20011, "该活动不支持预约抽签"),
    SECKILL_RISK_BLOCKED(20012, "检测到异常请求，请稍后再试"),

    // ---------------- 订单域 3xxxx ----------------
    ORDER_NOT_FOUND(30001, "订单不存在"),
    ORDER_STATUS_CONFLICT(30002, "订单状态已变更，操作失败"),
    ORDER_EXPIRED(30003, "订单已超时关闭"),
    ORDER_PAID(30004, "订单已支付"),

    // ---------------- 交易域 3xxxx（SKU / 支付 / 退款） ----------------
    SKU_NOT_FOUND(30010, "规格不存在或已下架"),
    SKU_STOCK_NOT_ENOUGH(30011, "库存不足"),
    PAYMENT_NOT_FOUND(30020, "支付单不存在"),
    PAYMENT_AMOUNT_MISMATCH(30021, "支付金额与订单金额不一致"),
    PAYMENT_STATUS_CONFLICT(30022, "支付单状态已变更，操作失败"),
    REFUND_NOT_ALLOWED(30030, "当前订单状态不支持退款"),
    REFUND_AMOUNT_MISMATCH(30031, "退款金额与支付金额不一致"),
    COUPON_NOT_FOUND(30040, "优惠券不存在"),
    COUPON_SOLD_OUT(30041, "优惠券已领完"),
    COUPON_ALREADY_CLAIMED(30042, "您已领取过该优惠券"),
    COUPON_THRESHOLD_NOT_MET(30043, "订单金额未达到优惠券使用门槛"),
    COUPON_UNAVAILABLE(30044, "优惠券不可用或已被使用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
