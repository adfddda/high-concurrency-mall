package com.lxt.mall.seckill;

/**
 * Lua 扣减结果码。
 *
 * <p>用枚举而不是裸数字：Redis 脚本的返回值是最容易出错的地方之一，
 * 一旦有人在脚本里插了一行改变返回值，裸数字会在代码里静默错位，
 * 而枚举至少会在 switch 的穷尽性检查里暴露出来。
 *
 * <p>这几个码要和服务端 {@code seckill_deduct.lua} 严格一致。
 */
public enum SeckillDeductResult {

    /** 0 —— 扣减成功，可以投递消息了 */
    SUCCESS,

    /** 1 —— 库存不足 */
    SOLD_OUT,

    /** 2 —— 该用户已经抢到过（一人一单） */
    REPEAT,

    /** 3 —— 活动未预热 / 预热数据已过期 */
    NOT_PREPARED,

    /**
     * 4 —— 抽签活动：无资格码，或资格码已被使用过
     *
     * <p>资格码的消费（SREM）放在 Lua 里，与库存扣减同一个原子操作，
     * 因此"同一个码用两次"在 Redis 层面就不可能发生。
     */
    NO_QUALIFICATION;

    public static SeckillDeductResult of(long code) {
        return switch ((int) code) {
            case 0 -> SUCCESS;
            case 1 -> SOLD_OUT;
            case 2 -> REPEAT;
            case 3 -> NOT_PREPARED;
            case 4 -> NO_QUALIFICATION;
            default -> throw new IllegalStateException("Lua 脚本返回了未知结果码: " + code
                    + "，请检查 seckill_deduct.lua 与 SeckillDeductResult 是否同步");
        };
    }
}
