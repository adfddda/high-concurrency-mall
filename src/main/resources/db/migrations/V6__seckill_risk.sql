-- ============================================================================
--  V6 迁移：秒杀风控命中记录
--
--  ⚠️ 全新安装不需要执行：01-schema.sql 已是 V6 结构。
--     本文件用于从 V5 原地升级。
-- ============================================================================

-- t_seckill_risk_hit 是新增表（CREATE TABLE IF NOT EXISTS），
-- 直接执行 01-schema.sql 中对应段落即可，重复执行安全。
SELECT 'V6: run the t_seckill_risk_hit CREATE TABLE from 01-schema.sql' AS migration_note;
