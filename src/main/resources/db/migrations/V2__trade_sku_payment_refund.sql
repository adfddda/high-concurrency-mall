-- ============================================================================
--  V2 迁移：从"仅秒杀订单"升级到"秒杀 + 普通交易"
--
--  ⚠️ 全新安装不需要执行本文件：01-schema.sql 已经是 V2 结构。
--     本文件只用于「已经跑过 V1 的库」原地升级。
--
--  注意：本文件刻意放在 db/migrations/ 子目录，而不是 db/ 根目录。
--  docker-compose 把 db/ 挂载到 MySQL 的 docker-entrypoint-initdb.d，
--  该目录下的 *.sql 会在初始化时【按文件名顺序全部执行】。
--  如果迁移脚本放在根目录，全新库会因为"列已存在"而启动失败。
-- ============================================================================

ALTER TABLE t_order
    MODIFY COLUMN activity_id BIGINT UNSIGNED NULL COMMENT '秒杀活动ID（普通订单为 NULL）',
    MODIFY COLUMN message_id  VARCHAR(64) NULL COMMENT '创建该订单的 MQ 消息ID（普通订单为 NULL）',
    MODIFY COLUMN status      TINYINT NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消 3已完成 4已退款',
    ADD COLUMN biz_type TINYINT NOT NULL DEFAULT 0 COMMENT '0 秒杀 1 普通' AFTER user_id,
    ADD COLUMN sku_id   BIGINT UNSIGNED NULL COMMENT 'SKU ID（秒杀订单为 NULL）' AFTER activity_id,
    ADD KEY idx_sku (sku_id);

-- 新增的三张表（t_sku / t_payment / t_refund）是 CREATE TABLE IF NOT EXISTS，
-- 直接执行 01-schema.sql 即可，重复执行安全。这里不再复制一份，避免两处 DDL 漂移。
