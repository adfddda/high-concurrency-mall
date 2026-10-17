-- ============================================================================
--  V8 迁移：取消/退款后允许再次购买
--
--  ⚠️ 全新安装不需要执行：01-schema.sql 已是 V8 结构。
--     本文件用于从 V7 原地升级。
-- ============================================================================

ALTER TABLE t_order
    ADD COLUMN active_flag TINYINT NULL DEFAULT 1
        COMMENT '1 有效 NULL 已失效（取消/退款）' AFTER message_id;

-- 老订单都视为有效
UPDATE t_order SET active_flag = 1 WHERE active_flag IS NULL;
UPDATE t_order SET active_flag = NULL WHERE status IN (2, 4);

-- 重建唯一索引：从 (user_id, activity_id) 改为 (user_id, activity_id, active_flag)
ALTER TABLE t_order DROP INDEX uk_user_activity;
ALTER TABLE t_order ADD UNIQUE KEY uk_user_activity (user_id, activity_id, active_flag);
