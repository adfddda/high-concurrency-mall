package com.lxt.mall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 秒杀结果（前端轮询用）。
 *
 * <p><b>为什么秒杀要做成"异步 + 轮询"？</b>
 * 因为下单链路要经过 MQ 异步落库，同步返回订单详情意味着接口必须
 * 等待消费完成——那 MQ 的削峰作用就完全没有了。
 * 正确做法是接口只返回一个"已受理"的凭证（订单号），
 * 前端拿订单号轮询结果。这样秒杀接口的 RT 与下游数据库完全解耦，
 * 数据库再慢也不会把秒杀接口拖垮。
 *
 * <p>用轮询而不是长连接/WebSocket：轮询实现简单、无状态、
 * 天然支持水平扩容，代价是有一点空轮询开销。
 * 如果对实时性要求高，可以升级成"轮询 + 服务端 hold 一小段时间"
 * 的长轮询，或者用 SSE。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderNo;

    private SeckillResultStatus status;

    /** QUEUEING 时的提示 / FAILED 时的原因。 */
    private String message;

    /** 建议前端下次轮询的间隔（毫秒），服务端可以按负载动态调整。 */
    private long retryAfterMillis;

    public static SeckillResultVO queueing(String orderNo) {
        return new SeckillResultVO(orderNo, SeckillResultStatus.QUEUEING, "排队中，请稍候", 300);
    }

    public static SeckillResultVO success(String orderNo) {
        return new SeckillResultVO(orderNo, SeckillResultStatus.SUCCESS, "抢购成功", 0);
    }

    public static SeckillResultVO failed(String orderNo, String reason) {
        return new SeckillResultVO(orderNo, SeckillResultStatus.FAILED, reason, 0);
    }
}
