package com.lxt.mall.order;

import com.lxt.mall.domain.entity.OutboxMessage;
import com.lxt.mall.domain.mapper.OutboxMessageMapper;
import com.lxt.mall.mq.OutboxRelayService;
import com.lxt.mall.seckill.SeckillPreheatService;
import com.lxt.mall.seckill.SeckillStockService;
import com.lxt.mall.seckill.message.SeckillOrderMessage;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox（本地消息表）验证。
 *
 * <p>核心断言只有一条：<b>"订单落库"和"延迟关单消息待发送"是同一个事务</b>。
 * 好消息是它可以用可观察的方式证明——
 * 只要订单存在，outbox 里就必然有一条 status=0 的记录；
 * 反过来，如果 outbox 写失败，订单事务也必须一起回滚。
 */
class OutboxTest extends BaseIntegrationTest {

    private static final long ACTIVITY_ID = 900_401L;

    @Autowired
    private OrderCreationService orderCreationService;

    @Autowired
    private OutboxMessageMapper outboxMapper;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private SeckillStockService stockService;

    @Autowired
    private SeckillPreheatService preheatService;

    private SeckillOrderMessage placeOrder(long userId) {
        String orderNo = "SO-OBX-" + System.nanoTime();
        SeckillOrderMessage message = new SeckillOrderMessage(
                UUID.randomUUID().toString().replace("-", ""), orderNo, userId, ACTIVITY_ID,
                TEST_PRODUCT_ID, "测试商品", new BigDecimal("9.90"), 1, 100, LocalDateTime.now());
        orderCreationService.createFromSeckill(message);
        return message;
    }

    @Test
    @DisplayName("下单即写 Outbox：订单与待发消息在同一个事务里落地")
    void orderWritesOutboxInSameTransaction() {
        prepareActivity(ACTIVITY_ID, 10);
        preheatService.preheat(ACTIVITY_ID);
        assertThat(stockService.deduct(ACTIVITY_ID, 710_001L))
                .isEqualTo(com.lxt.mall.seckill.SeckillDeductResult.SUCCESS);

        SeckillOrderMessage message = placeOrder(710_001L);

        List<OutboxMessage> rows = outboxMapper.selectList(null);
        assertThat(rows).hasSize(1);
        OutboxMessage row = rows.get(0);
        assertThat(row.getStatus()).isZero();
        assertThat(row.getBizType()).isEqualTo("ORDER_TIMEOUT");
        assertThat(row.getPayload()).contains(message.getOrderNo());
    }

    @Test
    @DisplayName("投递一轮后置为已发送；重复投递不会重复发送")
    void relayMarksSentAndIsIdempotent() {
        prepareActivity(ACTIVITY_ID, 10);
        preheatService.preheat(ACTIVITY_ID);
        assertThat(stockService.deduct(ACTIVITY_ID, 710_002L))
                .isEqualTo(com.lxt.mall.seckill.SeckillDeductResult.SUCCESS);

        placeOrder(710_002L);

        assertThat(relayService.relayOnce()).isEqualTo(1);
        assertThat(outboxMapper.selectList(null).get(0).getStatus()).isEqualTo(1);

        // 第二轮已经没有 status=0 的记录，不会再发一次
        assertThat(relayService.relayOnce()).isZero();
    }

    @Test
    @DisplayName("投递失败会累加重试次数并退避，不会立刻重试")
    void failedDeliveryBacksOff() {
        prepareActivity(ACTIVITY_ID, 10);
        preheatService.preheat(ACTIVITY_ID);
        assertThat(stockService.deduct(ACTIVITY_ID, 710_003L))
                .isEqualTo(com.lxt.mall.seckill.SeckillDeductResult.SUCCESS);

        placeOrder(710_003L);

        // 人为把 payload 破坏，让 relay 反序列化失败（模拟投递异常）
        OutboxMessage row = outboxMapper.selectList(null).get(0);
        row.setPayload("not-a-json");
        outboxMapper.updateById(row);

        assertThat(relayService.relayOnce()).isZero();

        OutboxMessage after = outboxMapper.selectById(row.getId());
        assertThat(after.getStatus()).isZero();
        assertThat(after.getRetryCount()).isEqualTo(1);
        assertThat(after.getNextRetryTime()).isAfter(LocalDateTime.now());
        assertThat(after.getLastError()).isNotNull();

        // 退避期内不会被再次取出
        assertThat(relayService.relayOnce()).isZero();
    }
}
