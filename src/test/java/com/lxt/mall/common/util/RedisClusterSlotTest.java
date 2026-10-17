package com.lxt.mall.common.util;

import com.lxt.mall.common.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hash Tag 同槽验证（纯单元测试，不需要 Redis）。
 *
 * <p>这个测试的价值在于：它把"Redis Cluster 下 Lua 不会报 CROSSSLOT"
 * 这件事从<b>口头承诺</b>变成了 CI 里的一条硬断言。
 *
 * <p>为什么必须自动化？因为这类问题在单机 Redis 上<b>永远不会暴露</b>。
 * 开发环境全绿、测试全过，一上生产集群就全线报错
 * {@code CROSSSLOT Keys in request don't hash to the same slot}。
 * 靠人肉 review 记住"给 key 加 hash tag"是不可靠的，
 * 但只要有人重构时去掉了 {@link RedisKeys#activityTag}，
 * 这个测试就会立刻变红。
 */
class RedisClusterSlotTest {

    @Test
    @DisplayName("秒杀脚本涉及的三个 key 必须落在同一个哈希槽")
    void seckillKeysMustShareHashSlot() {
        for (long activityId : List.of(1L, 2001L, 999999L, Long.MAX_VALUE)) {
            List<String> keys = List.of(
                    RedisKeys.seckillStock(activityId),
                    RedisKeys.seckillBought(activityId),
                    RedisKeys.seckillInflight(activityId));

            assertThat(RedisClusterSlot.sameSlot(keys.toArray(new String[0])))
                    .as("activityId=%s 的三个 key 必须同槽，否则 Lua 在集群模式下会 CROSSSLOT；keys=%s",
                            activityId, keys)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("不同活动的 key 应当落在不同的槽，避免数据倾斜")
    void differentActivitiesShouldSpreadAcrossSlots() {
        int slot1 = RedisClusterSlot.slot(RedisKeys.seckillStock(2001L));
        int slot2 = RedisClusterSlot.slot(RedisKeys.seckillStock(2002L));
        int slot3 = RedisClusterSlot.slot(RedisKeys.seckillStock(2003L));

        // 不强求两两不同（16384 个槽，哈希碰撞是正常的），
        // 但三个全撞在一起的概率极低，撞上了说明 hash tag 没生效
        assertThat(slot1 == slot2 && slot2 == slot3)
                .as("三个不同活动的库存 key 落到了同一个槽 %s，说明 hash tag 可能没有生效", slot1)
                .isFalse();
    }

    @Test
    @DisplayName("Hash Tag 语义：只有大括号内的内容参与计算")
    void hashTagSemantics() {
        // Redis 原生行为：{tag} 之外的部分不参与哈希计算
        assertThat(RedisClusterSlot.slot("foo{bar}baz"))
                .isEqualTo(RedisClusterSlot.slot("foo{bar}qux"));
        assertThat(RedisClusterSlot.slot("{bar}")).isEqualTo(RedisClusterSlot.slot("{bar}"));

        // 空 tag "{}" 与未闭合的 "{" 都不构成 hash tag，按整个 key 计算
        assertThat(RedisClusterSlot.slot("foo{}bar"))
                .isNotEqualTo(RedisClusterSlot.slot("foo{}baz"));
        assertThat(RedisClusterSlot.slot("foo{bar"))
                .isNotEqualTo(RedisClusterSlot.slot("foo{bar}"));
    }

    @Test
    @DisplayName("商品缓存的两个 key 也必须同槽（否则多 key DEL 会静默失败）")
    void productCacheKeysMustShareHashSlot() {
        for (long productId : List.of(1L, 1001L, 9_999_999L)) {
            String cacheKey = RedisKeys.productCache(productId);
            String nullKey = RedisKeys.productNullCache(productId);

            assertThat(RedisClusterSlot.sameSlot(cacheKey, nullKey))
                    .as("productId=%s 的缓存 key 与空值占位 key 必须同槽。"
                            + "ProductCacheService.invalidate 用一条 DEL 同时删这两个 key，"
                            + "不同槽在 Redis Cluster 下会直接 CROSSSLOT，"
                            + "而【缓存删不掉】是静默故障——不报错，只是用户一直读到旧数据。"
                            + " keys=[%s, %s]", productId, cacheKey, nullKey)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("不同商品的缓存 key 应当分散到不同槽，避免数据倾斜")
    void differentProductsSpreadAcrossSlots() {
        int slot1 = RedisClusterSlot.slot(RedisKeys.productCache(1001L));
        int slot2 = RedisClusterSlot.slot(RedisKeys.productCache(1002L));
        int slot3 = RedisClusterSlot.slot(RedisKeys.productCache(1003L));

        assertThat(slot1 == slot2 && slot2 == slot3).isFalse();
    }

    @Test
    @DisplayName("CRC16 实现正确（对照 CRC16-XMODEM 标准校验值 0x31C3）")
    void crc16MatchesStandardCheckValue() {
        // "123456789" 是 CRC 算法领域的标准测试向量，
        // CRC16/XMODEM（多项式 0x1021、初值 0x0000、无反转）的结果固定为 0x31C3。
        // 这条断言保证我们的实现与 Redis 源码 crc16.c 用的是同一个变体——
        // 用错变体（比如 CCITT-FALSE 之外的其它参数）算出来的槽位会整体偏移，
        // 后果是 hash tag 看起来"生效了"（同槽判断依然一致），
        // 但落到的槽和集群实际分配的对不上，问题极难定位。
        assertThat(RedisClusterSlot.crc16("123456789")).isEqualTo(0x31C3);
    }

    @Test
    @DisplayName("已知 key 的槽位与 Redis 文档一致")
    void knownKeySlots() {
        // Redis Cluster 规范文档里的示例值
        assertThat(RedisClusterSlot.slot("foo")).isEqualTo(12182);
        assertThat(RedisClusterSlot.slot("bar")).isEqualTo(5061);
    }
}
