-- ============================================================================
--  V4 迁移：预约抽签模式
--
--  ⚠️ 全新安装不需要执行：01-schema.sql 已是 V4 结构。
--     本文件用于从 V3 原地升级。
-- ============================================================================

ALTER TABLE t_seckill_activity
    ADD COLUMN mode TINYINT NOT NULL DEFAULT 0 COMMENT '0 直接抢 1 预约抽签' AFTER status;

-- t_seckill_reservation 是新增表（CREATE TABLE IF NOT EXISTS），
-- 直接执行 01-schema.sql 中对应段落即可，重复执行安全。
