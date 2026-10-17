package com.lxt.mall.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 秒杀活动模式。
 *
 * <h3>为什么要有"抽签"这种模式</h3>
 * 直接抢购的架构已经能扛住很高并发，但它有个本质缺陷：
 * <b>绝大多数请求注定失败</b>。10 万人抢 200 件，99.8% 的请求只是在
 * 消耗带宽、限流器和 Redis 的 CPU，最后拿到一句"已售罄"。
 *
 * <p>抽签把这件事拆成两段：
 * <pre>
 *   预约（活动开始前，低频） → 抽签（后台批处理，O(参与人数)）
 *        → 中签者凭资格码下单（O(库存量) 个请求）
 * </pre>
 * 真正进入扣减链路的请求从「人群量级」降到「库存量级」，
 * 而且用户体验从"拼手速"变成"看运气"，对黄牛也更不友好——
 * 脚本抢购的优势被抽签的随机性抹平了。
 */
public enum SeckillMode {

    /** 0 —— 到点直接抢（本项目最初的模式） */
    DIRECT(0, "直接抢购"),

    /** 1 —— 预约 + 抽签 + 资格码 */
    LOTTERY(1, "预约抽签");

    @EnumValue
    private final int code;
    private final String desc;

    SeckillMode(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 空值按"直接抢"处理：兼容加了 mode 字段之前写入的缓存快照。 */
    public static SeckillMode of(Integer code) {
        if (code == null) {
            return DIRECT;
        }
        for (SeckillMode m : values()) {
            if (m.code == code) {
                return m;
            }
        }
        return DIRECT;
    }
}
