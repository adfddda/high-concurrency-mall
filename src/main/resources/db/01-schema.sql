-- ============================================================================
--  高并发商城交易系统 —— 表结构
--
--  设计要点（每条都对应一个面试考点）：
--   1. 金额一律 DECIMAL，绝不用 FLOAT/DOUBLE。
--   2. 时间统一 DATETIME(3)（毫秒精度），超时关单 / 状态竞争判断依赖它。
--   3. 秒杀订单主键、消息表主键使用【雪花 ID】而非自增：
--      高并发插入下 InnoDB 自增主键存在 AUTO-INC 锁竞争，且自增在分库分表下
--      无法全局唯一；雪花 ID 趋势递增，兼顾 B+ 树写入局部性。
--   4. 订单表上用【三组唯一索引】做最终兜底幂等：
--        uk_order_no         —— 订单号幂等
--        uk_user_activity    —— 一人一单（并发下 Redis Set 判定失效时的最后防线）
--        uk_message_id       —— MQ 消息幂等
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
    stock       INT             NOT NULL DEFAULT 0 COMMENT '普通库存',
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
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '0未开始 1进行中 2已结束',
    mode            TINYINT         NOT NULL DEFAULT 0 COMMENT '0 直接抢 1 预约抽签',
    version         INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_product_id (product_id),
    KEY idx_start_end (start_time, end_time),
    KEY idx_status (status),
    -- 库存不能为负：即使应用层校验被绕过，数据库这层也要拦下来。
    -- （MySQL 8.0.16+ 的 CHECK 才真正生效；写成列内约束保证 DDL 幂等）
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
--  购物车
--
--  为什么购物车不做成"一个用户一行 JSON"：
--   1. 加购/改数量需要并发安全，JSON 得整行读改写，两个人同时改会互相覆盖；
--   2. 下单时要按 sku_id 定位某几行，JSON 里做不到行级操作；
--   3. 购物车条目会单独维护"是否勾选"，这是行级状态。
--  所以用 (user_id, sku_id) 唯一的一行一条目模型。
--  另外这里刻意【不缓存价格】：价格是 SKU 的属性，购物车只存"买什么、买几件"，
--  展示时实时读 SKU——否则商品调价后购物车会一直显示旧价，下单又按新价算，
--  用户会觉得"被偷改价格"。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_cart_item
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    user_id     BIGINT UNSIGNED NOT NULL,
    sku_id      BIGINT UNSIGNED NOT NULL,
    quantity    INT             NOT NULL DEFAULT 1 COMMENT '数量',
    checked     TINYINT         NOT NULL DEFAULT 1 COMMENT '1 勾选 0 未勾选',
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 同一用户同一 SKU 只有一行：重复加购是"数量 +1"而不是"多一行"
    UNIQUE KEY uk_user_sku (user_id, sku_id),
    KEY idx_user (user_id, update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='购物车条目';


-- ============================================================================
--  优惠券模板
--
--  issued_count 是"已发放数量"，领取时用条件更新原子自增：
--      UPDATE ... SET issued_count = issued_count + 1
--       WHERE id = ? AND issued_count < total_count
--  影响行数为 0 就是"领完了"。这与库存扣减是同一套思路：
--  把"判断"和"变更"压进一条语句，避免"查的时候还有、发的时候没了"的超发。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_coupon_template
(
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name             VARCHAR(64)     NOT NULL COMMENT '券名',
    threshold_amount DECIMAL(10, 2)  NOT NULL COMMENT '满多少可用',
    discount_amount  DECIMAL(10, 2)  NOT NULL COMMENT '减多少',
    total_count      INT             NOT NULL COMMENT '发行总量',
    issued_count     INT             NOT NULL DEFAULT 0 COMMENT '已发放',
    per_user_limit   INT             NOT NULL DEFAULT 1 COMMENT '每人限领',
    start_time       DATETIME(3)     NOT NULL,
    end_time         DATETIME(3)     NOT NULL,
    status           TINYINT         NOT NULL DEFAULT 1 COMMENT '1 生效 0 停用',
    create_time      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT ck_coupon_issued CHECK (issued_count <= total_count)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='优惠券模板';


-- ============================================================================
--  用户优惠券
--  uk_template_user 在 per_user_limit = 1 时直接等价于"每人限领一张"，
--  不需要在应用层再查一次"领过没有"。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_coupon
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    coupon_no   VARCHAR(32)     NOT NULL COMMENT '券码（对外）',
    template_id BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '0未使用 1已使用 2已过期',
    order_no    VARCHAR(32)              DEFAULT NULL COMMENT '核销订单号',
    use_time    DATETIME(3)              DEFAULT NULL,
    expire_time DATETIME(3)     NOT NULL,
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_no (coupon_no),
    UNIQUE KEY uk_template_user (template_id, user_id),
    KEY idx_user_status (user_id, status),
    KEY idx_order (order_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户优惠券';


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


-- ============================================================================
--  拼团
--
--  业务本质是"用人数换价格"：一个人开团，凑够 N 人才能成团。
--  技术上要解决两件事：
--   1. <b>成团判定</b>：第 N 个成员支付成功的瞬间要把团置为"已成团"，
--      而且只能有一次判定成功——用带前驱状态的条件更新保证；
--   2. <b>未成团退款</b>：到期仍未凑满，已付款的要退钱、未付款的要关单、
--      库存要还回去。这是一条必须由定时任务兜底的链路。
--
--  paid_count 是冗余字段，但它让"是否成团"变成一次原子自增，
--  不需要每次支付都去 COUNT 成员表（那是 O(N) 的读放大）。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_group_buy
(
    id               BIGINT UNSIGNED NOT NULL COMMENT '团ID（雪花）',
    leader_user_id   BIGINT UNSIGNED NOT NULL COMMENT '团长',
    sku_id           BIGINT UNSIGNED NOT NULL,
    required_members INT             NOT NULL COMMENT '成团所需人数',
    paid_count       INT             NOT NULL DEFAULT 0 COMMENT '已支付人数',
    status           TINYINT         NOT NULL DEFAULT 0 COMMENT '0 进行中 1 已成团 2 已失败',
    expire_time      DATETIME(3)     NOT NULL COMMENT '凑不够人则到期失败',
    complete_time    DATETIME(3)              DEFAULT NULL,
    create_time      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status_expire (status, expire_time),
    KEY idx_sku (sku_id),
    CONSTRAINT ck_group_paid_non_negative CHECK (paid_count >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='拼团';


CREATE TABLE IF NOT EXISTS t_group_buy_member
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    group_id    BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    order_no    VARCHAR(32)     NOT NULL COMMENT '该成员对应的订单',
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '0 待支付 1 已支付 2 已退款',
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 一个人在一个团里只能占一个位置
    UNIQUE KEY uk_group_user (group_id, user_id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_group (group_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='拼团成员';


CREATE TABLE IF NOT EXISTS t_order
(
    id            BIGINT UNSIGNED NOT NULL COMMENT '订单ID（雪花）',
    order_no      VARCHAR(32)     NOT NULL COMMENT '订单号（业务唯一，对外暴露）',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    -- 订单来源：秒杀订单与普通订单共用一张表，靠 biz_type 区分。
    -- 为什么不分两张表：订单是交易域的核心实体，支付、退款、履约、售后
    -- 都要按订单操作。分两张表意味着这些逻辑全部要写两遍，
    -- 而两者的差异其实只有"库存从哪来"和"是否受活动约束"。
    biz_type      TINYINT         NOT NULL DEFAULT 0 COMMENT '0 秒杀 1 普通',
    activity_id   BIGINT UNSIGNED          DEFAULT NULL COMMENT '秒杀活动ID（普通订单为 NULL）',
    sku_id        BIGINT UNSIGNED          DEFAULT NULL COMMENT 'SKU ID（秒杀订单为 NULL）',
    product_id    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    product_title VARCHAR(128)    NOT NULL COMMENT '商品标题快照',
    seckill_price DECIMAL(10, 2)  NOT NULL COMMENT '成交单价快照',
    quantity      INT             NOT NULL DEFAULT 1 COMMENT '数量',
    pay_amount    DECIMAL(10, 2)  NOT NULL COMMENT '应付金额',
    coupon_id     BIGINT UNSIGNED          DEFAULT NULL COMMENT '使用的优惠券',
    discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '优惠金额',
    status        TINYINT         NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消 3已完成 4已退款 5已发货',
    message_id    VARCHAR(64)              DEFAULT NULL COMMENT '创建该订单的 MQ 消息ID（普通订单为 NULL）',
    pay_time      DATETIME(3)              DEFAULT NULL,
    cancel_time   DATETIME(3)              DEFAULT NULL,
    ship_time     DATETIME(3)              DEFAULT NULL COMMENT '发货时间，自动确认收货的计时起点',
    finish_time   DATETIME(3)              DEFAULT NULL COMMENT '完成时间（手动/自动确认收货）',
    expire_time   DATETIME(3)     NOT NULL COMMENT '超时关单时间点',
    create_time   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- 一人一单：Redis 的 Set 判定只是「准入闸门」，真正的强约束在数据库。
    -- 注意 MySQL 的唯一索引允许多个 NULL：普通订单 activity_id 为 NULL，
    -- 因此不受这条约束影响，同一个用户可以对同一个 SKU 下多单。
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    -- 消息幂等：同一消息重复投递时，第二条 INSERT 直接撞唯一键，天然去重
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_status_expire (status, expire_time),
    -- 自动确认收货任务按 (status, ship_time) 扫描
    KEY idx_status_ship (status, ship_time),
    KEY idx_user_create (user_id, create_time),
    KEY idx_activity (activity_id),
    KEY idx_sku (sku_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='订单';


-- ============================================================================
--  SKU：商品的可售单元
--
--  原项目的 t_product 其实是一个"单 SKU 商品"的特例：价格、库存都挂在
--  商品上。真实电商里一个商品有多个 SKU（颜色 × 容量），价格和库存
--  都挂在 SKU 上，商品（SPU）只负责描述。
--
--  这里刻意保留 t_product 不动，新增 t_sku 作为普通下单的库存维度：
--  改造成本低，也能和秒杀（单 SKU 特例）形成对照。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_sku
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'SKU ID',
    product_id  BIGINT UNSIGNED NOT NULL COMMENT '所属商品',
    sku_code    VARCHAR(64)     NOT NULL COMMENT 'SKU 编码（对外展示/对接上游）',
    spec_json   VARCHAR(512)             DEFAULT NULL COMMENT '规格快照，如 {"颜色":"黑","容量":"512G"}',
    price       DECIMAL(10, 2)  NOT NULL COMMENT '售价',
    stock       INT             NOT NULL DEFAULT 0 COMMENT '可售库存',
    status      TINYINT         NOT NULL DEFAULT 1 COMMENT '1 在售 0 下架',
    version     INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code),
    KEY idx_product (product_id),
    -- 库存不能为负的最后一道防线（与活动表的 CHECK 同理）
    CONSTRAINT ck_sku_stock_non_negative CHECK (stock >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='SKU';


-- ============================================================================
--  支付单
--
--  为什么要单独的支付表，而不是直接在订单上加个 pay_status：
--   1. 一个订单可能有多笔支付尝试（超时关闭后重新支付、换渠道重试）；
--   2. 支付渠道对账必须以「支付单」为单位，渠道流水号、回调时间、
--      手续费这些都是支付单的属性，不属于订单；
--   3. 回调幂等需要一个"渠道维度的唯一键"（payment_no / channel_trade_no），
--      挂在订单上就失去了这个维度。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_payment
(
    id               BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    payment_no       VARCHAR(32)     NOT NULL COMMENT '支付单号（对外）',
    order_no         VARCHAR(32)     NOT NULL COMMENT '关联订单号',
    channel          TINYINT         NOT NULL DEFAULT 1 COMMENT '1 模拟渠道 2 支付宝 3 微信',
    amount           DECIMAL(10, 2)  NOT NULL COMMENT '支付金额',
    status           TINYINT         NOT NULL DEFAULT 0 COMMENT '0待支付 1成功 2已关闭 3已退款',
    channel_trade_no VARCHAR(64)              DEFAULT NULL COMMENT '渠道流水号（对账用）',
    callback_time    DATETIME(3)              DEFAULT NULL COMMENT '渠道回调时间',
    create_time      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    -- 一个订单同时只允许一笔有效支付单：重复发起支付时直接复用，避免重复扣款
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_channel_trade (channel_trade_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='支付单';


-- ============================================================================
--  退款单
--  uk_order_no 保证"一个订单最多退一次"，这是退款幂等的关键：
--  退款接口被重复调用、或渠道回调重复抵达时，第二次直接撞唯一键。
-- ============================================================================
CREATE TABLE IF NOT EXISTS t_refund
(
    id          BIGINT UNSIGNED NOT NULL COMMENT '主键（雪花）',
    refund_no   VARCHAR(32)     NOT NULL COMMENT '退款单号',
    order_no    VARCHAR(32)     NOT NULL COMMENT '关联订单号',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '申请用户',
    amount      DECIMAL(10, 2)  NOT NULL COMMENT '退款金额',
    reason      VARCHAR(256)             DEFAULT NULL,
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '0处理中 1成功 2拒绝',
    refund_time DATETIME(3)              DEFAULT NULL,
    create_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='退款单';


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
