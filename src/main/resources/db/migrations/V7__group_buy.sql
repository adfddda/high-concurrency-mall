-- ============================================================================
--  V7 迁移：拼团
--
--  ⚠️ 全新安装不需要执行：01-schema.sql 已是 V7 结构。
--     本文件用于从 V6 原地升级。
-- ============================================================================

-- t_group_buy / t_group_buy_member 是新增表（CREATE TABLE IF NOT EXISTS），
-- 直接执行 01-schema.sql 中对应段落即可，重复执行安全。
--
-- 另外 t_order.biz_type 新增了取值 2（拼团），该列是 TINYINT，无需改结构。
SELECT 'V7: run the t_group_buy / t_group_buy_member CREATE TABLE from 01-schema.sql' AS migration_note;
