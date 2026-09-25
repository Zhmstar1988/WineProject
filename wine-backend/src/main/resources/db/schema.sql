-- ============================================================
-- 新加坡红酒项目（智能分酒模式）数据库 DDL
-- 双单解耦架构：交易主订单 + 硬件履约单
-- 适用于 MySQL 8.0+
-- ============================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT       NOT NULL COMMENT '主键',
    phone           VARCHAR(20)  COMMENT '手机号',
    openid          VARCHAR(128) COMMENT '三方登录唯一标识',
    login_type      TINYINT      COMMENT '登录方式:1-SMS 2-Apple 3-Google',
    role            TINYINT      COMMENT '角色:1-C端 2-酒吧管理员 5-平台超管 6-酒商',
    nickname        VARCHAR(64)  COMMENT '昵称',
    avatar          VARCHAR(256) COMMENT '头像',
    bar_id          BIGINT       COMMENT '关联酒吧ID',
    supplier_id     BIGINT       COMMENT '关联酒商ID',
    age_verified    BOOLEAN      DEFAULT FALSE COMMENT '是否已满18岁校验',
    birth_date      DATE         COMMENT '出生日期',
    status          TINYINT      DEFAULT 1 COMMENT '状态:1-正常 0-禁用',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_sys_user_phone (phone),
    INDEX idx_sys_user_bar_id (bar_id)
);

-- 2. 酒吧表
CREATE TABLE IF NOT EXISTS bar (
    id              BIGINT       NOT NULL,
    bar_code        VARCHAR(32)  COMMENT '酒吧编码',
    bar_name        VARCHAR(128) COMMENT '酒吧名称',
    address         VARCHAR(256) COMMENT '地址',
    contact_phone   VARCHAR(20)  COMMENT '联系电话',
    cusid           VARCHAR(64)  COMMENT '通联子商户号',
    merchant_status TINYINT      DEFAULT 0 COMMENT '进件状态',
    status          TINYINT      DEFAULT 1 COMMENT '营业状态',
    short_code      VARCHAR(8)   COMMENT '4位酒吧码',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_bar_bar_code (bar_code),
    UNIQUE INDEX uk_bar_short_code (short_code)
);

-- 3. 分酒机设备表
CREATE TABLE IF NOT EXISTS dispenser (
    id              BIGINT       NOT NULL,
    bar_id          BIGINT       NOT NULL COMMENT '所属酒吧',
    device_no       VARCHAR(64)  COMMENT '设备编号UUID',
    mac             VARCHAR(32)  COMMENT 'MAC地址',
    device_name     VARCHAR(128) COMMENT '设备名称',
    slot_count      INT          DEFAULT 8 COMMENT '瓶位数量',
    status          TINYINT      DEFAULT 1 COMMENT '0-离线 1-在线 2-故障',
    last_heartbeat  DATETIME     COMMENT '最后心跳',
    firmware_version VARCHAR(32) COMMENT '固件版本',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_dispenser_bar_id (bar_id)
);

-- 4. 分酒机瓶位表
CREATE TABLE IF NOT EXISTS dispenser_slot (
    id              BIGINT       NOT NULL,
    dispenser_id    BIGINT       NOT NULL,
    slot_no         INT          NOT NULL COMMENT '瓶位号1~8',
    wine_sku_id     BIGINT       COMMENT '酒款SKU',
    initial_capacity INT         COMMENT '初始容量ml',
    current_capacity INT         COMMENT '当前余量ml',
    batch_no        VARCHAR(64)  COMMENT '批次号',
    residual_ml     INT          DEFAULT 0 COMMENT '上瓶残留ml',
    need_calibration BOOLEAN     DEFAULT FALSE COMMENT '需校准',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_dispenser_slot (dispenser_id, slot_no)
);

-- 5. 酒款SKU表
CREATE TABLE IF NOT EXISTS wine_sku (
    id              BIGINT       NOT NULL,
    wine_name       VARCHAR(128) COMMENT '酒款名称',
    origin          VARCHAR(64)  COMMENT '产区',
    vintage         INT          COMMENT '年份',
    grape_type      VARCHAR(64)  COMMENT '葡萄品种',
    alcohol         VARCHAR(16)  COMMENT '酒精度',
    supplier_id     BIGINT       COMMENT '供应酒商',
    cover_image     VARCHAR(256) COMMENT '封面',
    description     TEXT         COMMENT '简介',
    status          TINYINT      DEFAULT 1,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id)
);

-- 6. 酒吧酒单（酒吧×酒款×杯量）
CREATE TABLE IF NOT EXISTS bar_wine_menu (
    id              BIGINT       NOT NULL,
    bar_id          BIGINT       NOT NULL,
    dispenser_id    BIGINT,
    slot_id         BIGINT,
    wine_sku_id     BIGINT       NOT NULL,
    volume_ml       INT          NOT NULL COMMENT '杯量ml',
    volume_name     VARCHAR(32)  COMMENT '杯量名称',
    price           DECIMAL(10,2) NOT NULL COMMENT '售价SGD',
    status          TINYINT      DEFAULT 1,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_bar_wine_menu_bar_id (bar_id)
);

-- 7. 交易主订单表
CREATE TABLE IF NOT EXISTS order_main (
    id              BIGINT       NOT NULL,
    order_no        VARCHAR(32)  NOT NULL COMMENT '订单号兼出酒幂等键',
    user_id         BIGINT       NOT NULL,
    bar_id          BIGINT       NOT NULL,
    cusid           VARCHAR(64)  COMMENT '通联子商户号',
    dispenser_id    BIGINT       NOT NULL,
    slot_no         INT          NOT NULL,
    wine_sku_id     BIGINT       NOT NULL,
    volume_ml       INT          NOT NULL,
    original_amount DECIMAL(10,2) NOT NULL,
    discount_amount DECIMAL(10,2) DEFAULT 0,
    paid_amount     DECIMAL(10,2) NOT NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1待支付2支付中3已付款4已完成5已退款',
    pay_status      TINYINT      DEFAULT 0 COMMENT '0未支付1成功2失败3已退款',
    transaction_id  VARCHAR(64)  COMMENT '通联交易号',
    pay_expire_time DATETIME     COMMENT '支付超时',
    pay_time        DATETIME     COMMENT '支付时间',
    complete_time   DATETIME     COMMENT '完成时间',
    refund_time     DATETIME     COMMENT '退款时间',
    refund_no       VARCHAR(64)  COMMENT '退款单号',
    idempotent_key  VARCHAR(64)  COMMENT '幂等键（防重复下单）',
    remark          VARCHAR(256),
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_order_main_order_no (order_no),
    INDEX idx_order_main_user_id (user_id),
    INDEX idx_order_main_bar_id (bar_id),
    INDEX idx_order_main_status (status),
    UNIQUE INDEX uk_order_main_idempotent (idempotent_key)
);

-- 8. 硬件出酒履约单表
CREATE TABLE IF NOT EXISTS dispense_ticket (
    id                  BIGINT       NOT NULL,
    order_no            VARCHAR(32)  NOT NULL COMMENT '关联主订单号',
    order_id            BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    dispenser_id        BIGINT       NOT NULL,
    slot_no             INT          NOT NULL,
    target_ml           INT          NOT NULL,
    actual_ml           INT          COMMENT '实际出酒量',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '1就绪2出酒中3成功4失败',
    cup_present         BOOLEAN      COMMENT '杯位感应',
    dispense_start_time DATETIME     COMMENT '下发时间',
    dispense_end_time   DATETIME     COMMENT '完成时间',
    fail_reason         VARCHAR(256) COMMENT '失败原因',
    sla_met             BOOLEAN     COMMENT 'SLA达标',
    create_time         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by           BIGINT,
    update_by           BIGINT,
    deleted             TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_dispense_ticket_order_no (order_no),
    INDEX idx_dispense_ticket_dispenser_id (dispenser_id)
);

-- 9. 履约三单核对日志
CREATE TABLE IF NOT EXISTS reconcile_log (
    id              BIGINT       NOT NULL,
    reconcile_date  DATE         NOT NULL,
    order_no        VARCHAR(32)  NOT NULL,
    order_status    TINYINT,
    ticket_status   TINYINT,
    pay_status      TINYINT      COMMENT '通联授权状态',
    reconcile_result TINYINT     COMMENT '1一致2异常A漏单3异常B盗刷4异常C损耗5异常D授权不一致',
    anomaly_desc    VARCHAR(256),
    handled         BOOLEAN      DEFAULT FALSE,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_reconcile_log_date (reconcile_date)
);

-- 10. 损耗审计日志
CREATE TABLE IF NOT EXISTS loss_audit_log (
    id              BIGINT       NOT NULL,
    order_no        VARCHAR(32),
    dispenser_id    BIGINT,
    slot_no         INT,
    loss_type       TINYINT      COMMENT '1出酒不足 2换瓶残留',
    target_ml       INT,
    actual_ml       INT,
    loss_ml         INT,
    remark          VARCHAR(256),
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id)
);

-- 11. 短信验证码日志
CREATE TABLE IF NOT EXISTS sms_otp_log (
    id              BIGINT       NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    code            VARCHAR(8)   NOT NULL,
    expire_time     DATETIME     NOT NULL,
    used            BOOLEAN      DEFAULT FALSE,
    send_result     VARCHAR(256),
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_sms_otp_log_phone (phone)
);

-- 12. 酒吧后备库存表（实物整瓶库存）
CREATE TABLE IF NOT EXISTS bar_inventory (
    id              BIGINT       NOT NULL,
    bar_id          BIGINT       NOT NULL COMMENT '酒吧ID',
    wine_sku_id     BIGINT       NOT NULL COMMENT '酒款SKU',
    quantity        INT          DEFAULT 0 COMMENT '库存数量（整瓶）',
    alert_threshold INT          DEFAULT 0 COMMENT '预警阈值',
    location        VARCHAR(128) COMMENT '存放位置',
    status          TINYINT      DEFAULT 1 COMMENT '1-正常 0-停用',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_bar_inventory_bar_id (bar_id),
    INDEX idx_bar_inventory_sku_id (wine_sku_id)
);

-- 13. 补货单表（酒商补货 + 到货验收）
CREATE TABLE IF NOT EXISTS replenish_order (
    id              BIGINT       NOT NULL,
    order_no        VARCHAR(64)  NOT NULL COMMENT '补货单号',
    bar_id          BIGINT       NOT NULL COMMENT '酒吧ID',
    supplier_id     BIGINT       COMMENT '供应酒商ID',
    wine_sku_id     BIGINT       NOT NULL COMMENT '酒款SKU',
    quantity        INT          NOT NULL COMMENT '补货数量（整瓶）',
    unit_price      DECIMAL(10,2) COMMENT '单价SGD',
    total_amount    DECIMAL(10,2) COMMENT '总金额',
    status          TINYINT      DEFAULT 0 COMMENT '0-待发货 1-已发货 2-已验收 3-已拒收',
    shipped_time    DATETIME     COMMENT '发货时间',
    received_time   DATETIME     COMMENT '验收时间',
    received_qty    INT          COMMENT '实收数量',
    reject_reason   VARCHAR(256) COMMENT '拒收原因',
    remark          VARCHAR(256) COMMENT '备注',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_replenish_order_no (order_no),
    INDEX idx_replenish_bar_id (bar_id),
    INDEX idx_replenish_supplier_id (supplier_id),
    INDEX idx_replenish_status (status)
);

-- 14. 库存变动流水表（入库/出库/调整，用于追溯）
CREATE TABLE IF NOT EXISTS inventory_log (
    id              BIGINT       NOT NULL,
    bar_id          BIGINT       NOT NULL COMMENT '酒吧ID',
    wine_sku_id     BIGINT       NOT NULL COMMENT '酒款SKU',
    change_type     TINYINT      NOT NULL COMMENT '1-入库(验收) 2-出库(换瓶) 3-手动调整 4-拒收冲正',
    change_qty      INT          NOT NULL COMMENT '变动数量（正入库/负出库）',
    before_qty      INT          COMMENT '变动前库存',
    after_qty       INT          COMMENT '变动后库存',
    ref_type        VARCHAR(32)  COMMENT '关联类型: replenish/change_bottle/manual',
    ref_no          VARCHAR(64)  COMMENT '关联单号',
    remark          VARCHAR(256) COMMENT '备注',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_by       BIGINT,
    deleted         TINYINT      DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_inventory_log_bar_sku (bar_id, wine_sku_id),
    INDEX idx_inventory_log_ref (ref_type, ref_no)
);
