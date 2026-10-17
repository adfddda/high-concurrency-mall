package com.lxt.mall.seckill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动快照（热路径使用的数据形态）。
 *
 * <p>它把"活动 + 商品"两张表里抢购真正需要的字段拼在一起，
 * 目的是让秒杀接口<b>完全不碰数据库</b>：
 * <pre>
 *   本地 Caffeine(5s)  →  Redis 快照  →  MySQL（仅冷启动/缓存失效时）
 * </pre>
 *
 * <p>这个对象会被预热进 Redis，也是"秒杀开始前把活动预热到 Redis"
 * 这条要求的落点。注意这里<b>只有元信息，没有库存</b>——
 * 库存单独用一个 String key 存放，因为库存需要被 Lua 原子地 DECR，
 * 塞进 JSON 里就没法用 Redis 的原生原子操作了。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillActivitySnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long activityId;

    private String activityName;

    private Long productId;

    private String productTitle;

    private BigDecimal seckillPrice;

    private BigDecimal originalPrice;

    private Integer totalStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    /** 0 直接抢 / 1 预约抽签。热路径据此决定"要不要校验资格码"。 */
    private Integer mode;

    /** 快照生成时间，用于判断本地缓存是否过旧。 */
    private LocalDateTime snapshotTime;
}
