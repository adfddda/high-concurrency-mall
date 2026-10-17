package com.lxt.mall.seckill;

import com.lxt.mall.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀风控测试。
 *
 * <p>核心验证两件事：
 * <ol>
 *   <li><b>同一个设备在同一个活动里只能中一次</b>——这是限流挡不住的那类黄牛；</li>
 *   <li><b>失败请求不占用设备名额</b>——否则等于"没抢到还把你拉黑"。</li>
 * </ol>
 */
class SeckillRiskGuardTest extends BaseIntegrationTest {

    private static final long ACTIVITY_ID = 950_001L;
    private static final long OTHER_ACTIVITY_ID = 950_002L;

    @Autowired
    private SeckillRiskGuard riskGuard;

    private int hitCount(long activityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_seckill_risk_hit WHERE activity_id = ?",
                Integer.class, activityId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("首次下单放行，且不会写入任何拦截记录")
    void firstRequestIsAllowed() {
        SeckillRiskGuard.Decision decision =
                riskGuard.preCheck(ACTIVITY_ID, 900_001L, "device-abc", "1.1.1.1");

        assertThat(decision.blocked()).isFalse();
        assertThat(hitCount(ACTIVITY_ID)).as("放行的请求不该产生拦截记录").isZero();
    }

    @Test
    @DisplayName("同一设备抢到后再抢（换个账号）会被拦截，并留下审计记录")
    void sameDeviceIsBlockedAfterSuccess() {
        String device = "device-abc";

        // 第一个账号抢到了 → 占用设备名额
        riskGuard.markDevice(ACTIVITY_ID, device);

        SeckillRiskGuard.Decision second =
                riskGuard.preCheck(ACTIVITY_ID, 900_002L, device, "1.1.1.2");

        assertThat(second.blocked()).isTrue();
        assertThat(second.reason()).isEqualTo("DEVICE_REPEAT");
        assertThat(hitCount(ACTIVITY_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("设备指纹大小写/空白差异不会绕过风控")
    void deviceFingerprintIsNormalized() {
        riskGuard.markDevice(ACTIVITY_ID, "Device-ABC");

        SeckillRiskGuard.Decision decision =
                riskGuard.preCheck(ACTIVITY_ID, 900_003L, "  device-abc  ", "1.1.1.3");

        assertThat(decision.blocked()).as("同一设备换大小写不能绕过去").isTrue();
    }

    @Test
    @DisplayName("不同设备互不影响")
    void differentDevicesAreIndependent() {
        riskGuard.markDevice(ACTIVITY_ID, "device-1");

        assertThat(riskGuard.preCheck(ACTIVITY_ID, 900_004L, "device-2", "1.1.1.4").blocked())
                .isFalse();
    }

    @Test
    @DisplayName("设备名额按活动隔离：A 活动用过不影响 B 活动")
    void deviceLimitIsScopedToActivity() {
        riskGuard.markDevice(ACTIVITY_ID, "device-shared");

        assertThat(riskGuard.preCheck(OTHER_ACTIVITY_ID, 900_005L, "device-shared", "1.1.1.5").blocked())
                .as("限购语义是'一个活动一次'，不是'一辈子一次'")
                .isFalse();
    }

    @Test
    @DisplayName("没有设备指纹时放行：宁可漏防，也不能误杀")
    void missingDeviceIsAllowed() {
        assertThat(riskGuard.preCheck(ACTIVITY_ID, 900_006L, null, "1.1.1.6").blocked()).isFalse();
        assertThat(riskGuard.preCheck(ACTIVITY_ID, 900_006L, "  ", "1.1.1.6").blocked()).isFalse();
    }
}
