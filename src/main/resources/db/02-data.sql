-- ============================================================================
--  演示数据
--  说明：这里刻意不用 INSERT IGNORE 之外的写法，保证可重复执行。
--        演示账号 lxt / 123456 的 BCrypt 密文由 DataSeeder 在启动时校正
--        （见 com.lxt.mall.boot.DataSeeder），避免把哈希硬编码进 SQL 后
--        因 cost 参数不同而无法登录。
-- ============================================================================

INSERT INTO t_product (id, title, sub_title, cover_img, price, stock, status, detail)
VALUES (1001, 'Xiaomi 15 Ultra 16GB+512GB', '徕卡光学四摄 · 骁龙8至尊版', '/img/p1001.png', 6499.00, 1000, 1,
        '{"brand":"Xiaomi","cpu":"Snapdragon 8 Elite","screen":"6.73inch 2K","battery":"6000mAh"}'),
       (1002, 'iPhone 17 Pro 256GB', 'A19 Pro · 钛金属边框', '/img/p1002.png', 8999.00, 800, 1,
        '{"brand":"Apple","cpu":"A19 Pro","screen":"6.3inch","battery":"4200mAh"}'),
       (1003, 'MacBook Pro 14 M5 Pro', 'M5 Pro 芯片 · 24GB 统一内存', '/img/p1003.png', 15999.00, 300, 1,
        '{"brand":"Apple","cpu":"M5 Pro","memory":"24GB","storage":"512GB SSD"}'),
       (1004, 'Sony WH-1000XM6 降噪耳机', '行业标杆级主动降噪', '/img/p1004.png', 2899.00, 2000, 1,
        '{"brand":"Sony","type":"over-ear","anc":"yes"}'),
       (1005, 'Nintendo Switch 2 主机', '4K 底座输出 · 向下兼容', '/img/p1005.png', 2599.00, 500, 1,
        '{"brand":"Nintendo","storage":"256GB"}')
ON DUPLICATE KEY UPDATE title = VALUES(title);

-- SKU：每个商品给 1~2 个规格，价格与库存挂在 SKU 上。
-- 注意 ON DUPLICATE KEY 只更新 sku_code，**不重置 stock**：
-- 否则每次应用启动都会把已经卖掉的库存"复活"，这在演示环境看起来
-- 像是"库存对不上"，实际是初始化脚本覆盖了业务数据。
INSERT INTO t_sku (id, product_id, sku_code, spec_json, price, stock, status)
VALUES (3001, 1001, 'MI15U-16-512-BK', '{"color":"黑","storage":"512G"}', 6499.00, 1000, 1),
       (3002, 1001, 'MI15U-16-256-WH', '{"color":"白","storage":"256G"}', 5999.00, 800, 1),
       (3003, 1002, 'IP17P-256-TI', '{"color":"钛","storage":"256G"}', 8999.00, 800, 1),
       (3004, 1003, 'MBP14-M5-24-512', '{"color":"深空黑","storage":"512G"}', 15999.00, 300, 1),
       (3005, 1004, 'SONY-XM6-BK', '{"color":"黑"}', 2899.00, 2000, 1),
       (3006, 1005, 'NSW2-256', '{"storage":"256G"}', 2599.00, 500, 1)
ON DUPLICATE KEY UPDATE sku_code = VALUES(sku_code);

-- 秒杀活动：时间窗口做成「长期有效」，方便随时压测；
-- 生产环境应按运营配置写入具体时段。
INSERT INTO t_seckill_activity (id, activity_name, product_id, seckill_price, total_stock, available_stock,
                                start_time, end_time, status)
VALUES (2001, 'Xiaomi 15 Ultra 秒杀专场', 1001, 3999.00, 200, 200,
        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 365 DAY), 1),
       (2002, 'iPhone 17 Pro 限时抢购', 1002, 6999.00, 100, 100,
        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 365 DAY), 1),
       (2003, 'MacBook Pro 14 秒杀', 1003, 12999.00, 50, 50,
        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 365 DAY), 1),
       (2004, 'Sony XM6 耳机秒杀', 1004, 1999.00, 500, 500,
        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 365 DAY), 1),
       (2005, 'Switch 2 首发秒杀', 1005, 1999.00, 300, 300,
        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 365 DAY), 1)
ON DUPLICATE KEY UPDATE activity_name = VALUES(activity_name),
                        total_stock   = VALUES(total_stock),
                        available_stock = VALUES(available_stock),
                        start_time    = VALUES(start_time),
                        end_time      = VALUES(end_time),
                        status        = VALUES(status);
