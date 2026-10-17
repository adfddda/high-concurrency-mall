package com.lxt.mall.common.util;

import java.nio.charset.StandardCharsets;

/**
 * Redis Cluster 槽位计算。
 *
 * <p>存在的意义：<b>让 Hash Tag 这件事可被测试</b>。
 *
 * <p>Redis Cluster 用 {@code CRC16(key) % 16384} 决定 key 落在哪个槽。
 * 如果 key 里包含 {@code {...}}，且大括号内非空，
 * 则【只对大括号内的部分】计算哈希——这就是 Hash Tag。
 *
 * <p>本项目秒杀脚本一次要操作 3 个 key（库存 / 已购集合 / 在途计数）。
 * 在集群模式下，Lua 脚本涉及的所有 key 必须落在同一个槽，
 * 否则 EVAL 会直接报错：
 * <pre>
 *   CROSSSLOT Keys in request don't hash to the same slot
 * </pre>
 * 单机 Redis 下这个错误不会出现，所以很多人在开发环境永远发现不了，
 * 一上集群就炸。这里的 {@link #sameSlot(String...)} 被单元测试直接断言，
 * 把"集群可用"从口头承诺变成 CI 里的一条红线。
 *
 * <p>CRC16 用的是 XMODEM 变体（多项式 0x1021，初值 0x0000），
 * 与 Redis 源码 crc16.c 的实现一致。
 */
public final class RedisClusterSlot {

    public static final int SLOT_COUNT = 16384;

    private RedisClusterSlot() {
    }

    /** 计算 key 所属槽位，遵循 Hash Tag 规则。 */
    public static int slot(String key) {
        int start = key.indexOf('{');
        if (start >= 0) {
            int end = key.indexOf('}', start + 1);
            // 只有大括号内非空时才算 Hash Tag，否则按整个 key 计算
            // （"{}" 与 "a{}b" 都不构成 tag，这是 Redis 的原生行为）
            if (end > start + 1) {
                return crc16(key.substring(start + 1, end)) & (SLOT_COUNT - 1);
            }
        }
        return crc16(key) & (SLOT_COUNT - 1);
    }

    /** 判断一组 key 是否同槽——Lua 脚本能安全执行的前提。 */
    public static boolean sameSlot(String... keys) {
        if (keys == null || keys.length == 0) {
            return true;
        }
        int first = slot(keys[0]);
        for (int i = 1; i < keys.length; i++) {
            if (slot(keys[i]) != first) {
                return false;
            }
        }
        return true;
    }

    public static int crc16(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int crc = 0x0000;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
