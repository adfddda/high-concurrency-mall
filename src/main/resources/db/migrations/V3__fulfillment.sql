-- ============================================================================
--  V3 迁移：履约链路（发货 / 确认收货 / 自动确认）
--
--  ⚠️ 全新安装不需要执行：01-schema.sql 已是 V3 结构。
--     本文件用于从 V2 原地升级。
-- ============================================================================

ALTER TABLE t_order
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
        COMMENT '0待支付 1已支付 2已取消 3已完成 4已退款 5已发货',
    ADD COLUMN ship_time   DATETIME(3) NULL COMMENT '发货时间，自动确认收货的计时起点' AFTER cancel_time,
    ADD COLUMN finish_time DATETIME(3) NULL COMMENT '完成时间（手动/自动确认收货）' AFTER ship_time,
    ADD KEY idx_status_ship (status, ship_time);
