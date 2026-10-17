-- ============================================================================
--  V5 迁移：购物车 + 优惠券
--
--  ⚠️ 全新安装不需要执行：01-schema.sql 已是 V5 结构。
--     本文件用于从 V4 原地升级。
-- ============================================================================

ALTER TABLE t_order
    ADD COLUMN coupon_id       BIGINT UNSIGNED NULL COMMENT '使用的优惠券' AFTER pay_amount,
    ADD COLUMN discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '优惠金额' AFTER coupon_id;

-- t_cart_item / t_coupon_template / t_coupon 是新增表（CREATE TABLE IF NOT EXISTS），
-- 直接执行 01-schema.sql 中对应段落即可，重复执行安全。
