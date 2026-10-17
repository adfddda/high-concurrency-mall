package com.lxt.mall.seckill;

import com.lxt.mall.common.exception.BusinessException;
import com.lxt.mall.domain.entity.SeckillReservation;
import com.lxt.mall.domain.enums.SeckillMode;
import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预约抽签测试。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>预约幂等（重复点击不会产生两条记录）；</li>
 *   <li>抽签的名额 = 库存，且中签者拿到唯一资格码；</li>
 *   <li>资格码在扣减 Lua 里被原子消费——同一个码用两次必然失败。</li>
 * </ol>
 */
class ReservationLotteryTest extends BaseIntegrationTest {

    private static final long LOTTERY_ACTIVITY = 930_001L;
    private static final long DIRECT_ACTIVITY = 930_002L;
    private static final int STOCK = 3;
    private static final int PARTICIPANTS = 5;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private SeckillStockService stockService;

    @Autowired
    private SeckillPreheatService preheatService;

    @Autowired
    private StringRedisTemplate redis;

    private List<Long> reserveAll(long activityId, int n) {
        List<Long> users = new ArrayList<>();
        for (long i = 1; i <= n; i++) {
            long userId = 500_000L + i;
            reservationService.reserve(activityId, userId);
            users.add(userId);
        }
        return users;
    }

    @Test
    @DisplayName("预约幂等：重复预约只保留一条记录")
    void reserveIsIdempotent() {
        prepareLotteryActivity(LOTTERY_ACTIVITY, STOCK);

        SeckillReservation first = reservationService.reserve(LOTTERY_ACTIVITY, 600_001L);
        SeckillReservation second = reservationService.reserve(LOTTERY_ACTIVITY, 600_001L);

        assertThat(second.getId()).isEqualTo(first.getId());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_seckill_reservation WHERE activity_id = ?",
                Integer.class, LOTTERY_ACTIVITY);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("直接抢购的活动不能预约")
    void cannotReserveDirectActivity() {
        prepareActivity(DIRECT_ACTIVITY, STOCK);

        assertThatThrownBy(() -> reservationService.reserve(DIRECT_ACTIVITY, 600_002L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持预约");
    }

    @Test
    @DisplayName("抽签：中签人数等于库存，其余标记未中签，资格码写入 Redis")
    void drawSelectsWinnerCountEqualToStock() {
        prepareLotteryActivity(LOTTERY_ACTIVITY, STOCK);
        reserveAll(LOTTERY_ACTIVITY, PARTICIPANTS);

        int winners = reservationService.draw(LOTTERY_ACTIVITY, 1.0);

        assertThat(winners).as("中签名额 = 可售库存").isEqualTo(STOCK);

        Integer won = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_seckill_reservation WHERE activity_id = ? AND status = 1",
                Integer.class, LOTTERY_ACTIVITY);
        Integer lost = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_seckill_reservation WHERE activity_id = ? AND status = 2",
                Integer.class, LOTTERY_ACTIVITY);
        assertThat(won).isEqualTo(STOCK);
        assertThat(lost).isEqualTo(PARTICIPANTS - STOCK);

        Long codeCount = redis.opsForSet().size("seckill:codes:{act:" + LOTTERY_ACTIVITY + "}");
        assertThat(codeCount).as("中签资格码必须同步到 Redis 供扣减脚本原子消费").isEqualTo(STOCK);
    }

    @Test
    @DisplayName("抽签幂等：重复抽签不会产生第二批中签者")
    void drawTwiceDoesNothing() {
        prepareLotteryActivity(LOTTERY_ACTIVITY, STOCK);
        reserveAll(LOTTERY_ACTIVITY, PARTICIPANTS);

        assertThat(reservationService.draw(LOTTERY_ACTIVITY, 1.0)).isEqualTo(STOCK);
        assertThat(reservationService.draw(LOTTERY_ACTIVITY, 1.0)).isZero();
    }

    @Test
    @DisplayName("资格码一次性：有效则扣减成功，重复使用或伪造一律失败")
    void qualifyCodeIsSingleUse() {
        prepareLotteryActivity(LOTTERY_ACTIVITY, STOCK);
        reserveAll(LOTTERY_ACTIVITY, PARTICIPANTS);
        int winners = reservationService.draw(LOTTERY_ACTIVITY, 1.0);
        preheatService.preheat(LOTTERY_ACTIVITY);

        // 找出一个中签者及其资格码
        Long winnerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM t_seckill_reservation WHERE activity_id = ? AND status = 1 LIMIT 1",
                Long.class, LOTTERY_ACTIVITY);
        String code = reservationService.myReservation(LOTTERY_ACTIVITY, winnerId).getQualifyCode();
        assertThat(winners).isEqualTo(STOCK);
        assertThat(code).isNotBlank();

        // 第一次：成功
        assertThat(stockService.deduct(LOTTERY_ACTIVITY, winnerId, SeckillMode.LOTTERY, code))
                .isEqualTo(SeckillDeductResult.SUCCESS);

        // 同一个码再用：失败（Lua 里的 SREM 已经把它消费掉了）
        assertThat(stockService.deduct(LOTTERY_ACTIVITY, 999_999L, SeckillMode.LOTTERY, code))
                .isEqualTo(SeckillDeductResult.NO_QUALIFICATION);

        // 伪造的码：失败
        assertThat(stockService.deduct(LOTTERY_ACTIVITY, 999_998L, SeckillMode.LOTTERY, "FAKE-CODE"))
                .isEqualTo(SeckillDeductResult.NO_QUALIFICATION);
    }

    @Test
    @DisplayName("库存不足时不消耗资格码（中签者不该因为手慢连资格都丢了）")
    void soldOutDoesNotConsumeCode() {
        prepareLotteryActivity(LOTTERY_ACTIVITY, 1);
        reserveAll(LOTTERY_ACTIVITY, 2);
        reservationService.draw(LOTTERY_ACTIVITY, 1.0);
        preheatService.preheat(LOTTERY_ACTIVITY);

        Long winnerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM t_seckill_reservation WHERE activity_id = ? AND status = 1 LIMIT 1",
                Long.class, LOTTERY_ACTIVITY);
        String code = reservationService.myReservation(LOTTERY_ACTIVITY, winnerId).getQualifyCode();

        // 先把库存抢空
        stockService.deduct(LOTTERY_ACTIVITY, 777_777L, SeckillMode.DIRECT, null);

        // 中签者来晚了：得到"售罄"而不是"无资格"，且资格码仍在集合里
        assertThat(stockService.deduct(LOTTERY_ACTIVITY, winnerId, SeckillMode.LOTTERY, code))
                .isEqualTo(SeckillDeductResult.SOLD_OUT);
        assertThat(redis.opsForSet().isMember("seckill:codes:{act:" + LOTTERY_ACTIVITY + "}", code))
                .isTrue();
    }
}
