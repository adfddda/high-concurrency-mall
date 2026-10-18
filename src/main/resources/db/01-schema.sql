-- ============================================================================
--  高并发秒杀系统 —— 表结构（9 张表）
--
--  设计要点（每条都对应一个面试考点）：
--   1. 金额一律 DECIMAL，绝不用 FLOAT/DOUBLE。
--   2. 时间统一 DATETIME(3)（毫秒精度），超时关单 / 状态竞争判断依赖它。
--   3. 订单、明细、消息、流水、预约、风控记录的主键使用【雪花 ID】而非自增：
--      高并发插入下 InnoDB 自增主键存在 AUTO-INC 锁竞争，且自增在分库分表下
--      无法全局唯一；雪花 ID 趋势递增，兼顾 B+ 树写入局部性。
--   4. 订单表上用【三组唯一索引】做最终兜底幂等：
--        uk_order_no       —— 订单号幂等
--        uk_user_activity  —— 一人一单（带 active_flag：取消/退款后可重买）
--        uk_message_id     —— MQ 消息幂等
--   5. t_stock_flow.uk_order_change(order_no, change_type) —— 保证同一订单
--      的「扣减」「回补」各只能落一次，杜绝超时关单与死信回补重复回补库存。
--   6. 全部 DDL 幂等（IF NOT EXISTS），可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS t_user
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(64)     NOT NULL COMMENT '登录名',
    password    VARCHAR(100)    NOT NULL COMMENT 'BCrypt 密文',
    nickname    VARCHAR(64)              DEFAULT NULL COMMENT '昵称',
    phone       VARCHAR(20)              DEFAULT NULL COMMENT '手机号',
    status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_phone (phone)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户';


CREATE TABLE IF NOT EXISTS t_product
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    title       VARCHAR(128)    NOT NULL COMMENT '标题',
    sub_title   VARCHAR(256)             DEFAULT NULL COMMENT '副标题',
    cover_img   VARCHAR(255)             DEFAULT NULL COMMENT '封面图',
    price       DECIMAL(10, 2)  NOT NULL COMMENT '原价',
    stock       INT             NOT NULL DEFAULT 0 COMMENT '普通库存（展示用，秒杀扣的是活动库存）',
    status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    detail      TEXT COMMENT '详情（大字段，缓存时按需裁剪）',
    version     INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='商品';


CREATE TABLE IF NOT EXISTS t_seckill_activity
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    activity_name   VARCHAR(128)    NOT NULL COMMENT '活动名',
    product_id      BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    seckill_price   DECIMAL(10, 2)  NOT NULL COMMENT '秒杀价',
    total_stock     INT             NOT NULL COMMENT '总库存（活动快照，不随扣减慢）',
    available_stock INT             NOT NULL COMMENT '剩余可售库存（随下单/回补增减）',
    start_time      DATETIME(3)     NOT NULL COMMENT '开始时间',
    end_time        DATETIME(3)     NOT NULL COMMENT '结束时间',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0未开始 1进行中 2已结束（由 ActivityStatusSyncJob 维护）',
    mode            TINYINT         NOT NULL DEFAULT 0 COMMENT '0 直接抢 1 预约抽签',
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_product_id (product_id),
    KEY idx_start_end (start_time, end_time),
    KEY idx_status (status),
    -- 库存不能为负：即使应用层校验被绕过，数据库这层也要拦下来。
    -- （MySQL 8.0.16+ 的 CHECK 才真正生效）
    CONSTRAINT ck_activity_stock_non_negative CHECK (available_stock >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀活动';


-- ============================================================================
--  秒杀预约 / 抽签
--
--  为什么要有这张表：
--   "所有人到点一起抢" 的流量是库存的几万倍，绝大多数请求注定失败，
--   它们消耗的是入口带宽、限流器和 Redis 的 CPU。
--   抽签把「抢购」变成两段：
--        预约（低频、可提前）→ 抽签（后台批处理）→ 中签者凭码下单
--   于是真正进入扣减链路的请求量 = 库存量级，而不是人群量级。
--
--  uk_activity_user：一个人对一个活动只能预约一次（也是抽签去重依据）
--  uk_qualify_code：资格码全局唯一，且只发一次
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_seckill_reservation
(
    id            BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    activity_id   BIGINT UNSIGNED NOT NULL,
    user_id       BIGINT UNSIGNED NOT NULL,
    status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0已预约 1已中签 2未中签 3资格已使用',
    qualify_code  VARCHAR(32)              DEFAULT NULL COMMENT '资格码（中签时生成）',
    draw_batch    INT                      DEFAULT NULL COMMENT '抽签批次号',
    create_time   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_user (activity_id, user_id),
    UNIQUE KEY uk_qualify_code (qualify_code),
    KEY idx_activity_status (activity_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀预约与抽签记录';


-- ============================================================================
--  秒杀风控命中记录
--
--  为什么要把"被拦截的请求"落库：
--   拦截率是唯一能回答"风控到底有没有用"的指标。只挡不记，
--   既无法向运营证明价值，也无法在误杀时回溯。
--  这张表只记录【被拦截】的请求（正常请求不写），因此体量可控。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_seckill_risk_hit
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    activity_id BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    device_id   VARCHAR(64)              DEFAULT NULL COMMENT '客户端设备指纹',
    client_ip   VARCHAR(64)              DEFAULT NULL,
    reason      VARCHAR(32)     NOT NULL COMMENT 'DEVICE_REPEAT / IP_LIMIT ...',
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_activity_time (activity_id, create_time),
    KEY idx_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀风控命中记录';


CREATE TABLE IF NOT EXISTS t_order
(
    id            BIGINT UNSIGNED NOT NULL COMMENT '订单ID（雪花）',
    order_no      VARCHAR(32)     NOT NULL COMMENT '订单号（业务唯一，对外暴露）',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    activity_id   BIGINT UNSIGNED NOT NULL COMMENT '秒杀活动ID',
    product_id    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    product_title VARCHAR(128)    NOT NULL COMMENT '商品标题快照',
    seckill_price DECIMAL(10, 2)  NOT NULL COMMENT '成交单价快照',
    quantity      INT             NOT NULL DEFAULT 1 COMMENT '数量',
    pay_amount    DECIMAL(10, 2)  NOT NULL COMMENT '应付金额',
    -- 只有三个状态：秒杀订单没有履约环节，多一个"已完成"就会变成
    -- 永远不可达的死状态（枚举里定义了但没有任何代码会写它）
    status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
    message_id    VARCHAR(64)     NOT NULL COMMENT '创建该订单的 MQ 消息ID（幂等）',
    -- 有效期标记：1 = 有效订单，NULL = 已失效（取消）。
    -- 唯一索引 uk_user_activity 带上这一列，"一人一单"就变成
    -- "同一活动同时只能有一笔有效订单"——取消后可以重新抢，但历史订单不会被删除。
    active_flag   TINYINT                  DEFAULT 1 COMMENT '1 有效 NULL 已失效（取消）',
    pay_time      DATETIME(3)              DEFAULT NULL,
    cancel_time   DATETIME(3)              DEFAULT NULL,
    expire_time   DATETIME(3)     NOT NULL COMMENT '超时关单时间点',
    create_time   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- 一人一单：Redis 的 Set 判定只是「准入闸门」，真正的强约束在数据库。
    -- MySQL 的唯一索引允许多个 NULL，所以 active_flag 为 NULL 的已取消订单
    -- 不参与唯一性判断——这就是"取消后能再买"的实现方式。
    UNIQUE KEY uk_user_activity (user_id, activity_id, active_flag),
    -- 消息幂等：同一消息重复投递时，第二条 INSERT 直接撞唯一键，天然去重
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_status_expire (status, expire_time),
    KEY idx_user_create (user_id, create_time),
    KEY idx_activity (activity_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='秒杀订单';


CREATE TABLE IF NOT EXISTS t_order_item
(
    id            BIGINT UNSIGNED NOT NULL COMMENT '明细ID（雪花）',
    order_id      BIGINT UNSIGNED NOT NULL COMMENT '订单ID',
    order_no      VARCHAR(32)     NOT NULL COMMENT '订单号',
    product_id    BIGINT UNSIGNED NOT NULL,
    product_title VARCHAR(128)    NOT NULL,
    seckill_price DECIMAL(10, 2)  NOT NULL,
    quantity      INT             NOT NULL DEFAULT 1,
    create_time   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='订单明细';


-- ============================================================================
--  消息表：一张表同时承担三个职责
--    a) 生产者投递记录（direction=1）：Confirm 超时未确认的消息由此重投
--    b) 消费者幂等记录（direction=2）：message_id 唯一键 = 消费去重
--    c) 失败台账（status=2）：死信 / 人工排查 / 对账的依据
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_mq_message
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    message_id  VARCHAR(64)     NOT NULL COMMENT '业务消息唯一ID',
    biz_type    VARCHAR(32)     NOT NULL COMMENT 'SECKILL_ORDER / ORDER_TIMEOUT',
    biz_key     VARCHAR(128)    NOT NULL COMMENT '业务键，如 orderNo 或 actId:userId',
    direction   TINYINT         NOT NULL DEFAULT 2 COMMENT '1生产 2消费',
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '0待处理 1成功 2失败',
    retry_count INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    payload     TEXT COMMENT '消息体快照',
    error_msg   VARCHAR(512)             DEFAULT NULL COMMENT '失败原因',
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_status_retry (status, retry_count, update_time),
    KEY idx_biz (biz_type, biz_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='消息投递/消费记录（幂等 + 台账）';


-- ============================================================================
--  库存流水：可追溯 + 防重复回补
--  uk_order_change(order_no, change_type) 是「同一订单同一类变更只能落一次」
--  的硬约束。超时关单回补与死信回补同时发生时，只有一个能成功。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_stock_flow
(
    id           BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    activity_id  BIGINT UNSIGNED NOT NULL,
    order_no     VARCHAR(32)     NOT NULL,
    user_id      BIGINT UNSIGNED NOT NULL,
    change_type  TINYINT         NOT NULL COMMENT '1扣减 2回补',
    change_num   INT             NOT NULL COMMENT '变更数量（正数）',
    stock_before INT             NOT NULL COMMENT '变更前剩余库存',
    stock_after  INT             NOT NULL COMMENT '变更后剩余库存',
    remark       VARCHAR(128)             DEFAULT NULL,
    create_time  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_change (order_no, change_type),
    KEY idx_activity_create (activity_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='库存流水（对账 + 防重复回补）';
