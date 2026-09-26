-- ============================================================
-- 压测数据准备脚本
-- 用途：清空交易数据，扩容瓶位并发度，重置容量
-- 注意：不删除 sys_user 中的管理员/酒吧/供酒商账号(9001/9002/9004)
--       10万C端用户由 Java 压测客户端批量插入
-- ============================================================

-- 1. 清空所有交易与日志表
TRUNCATE TABLE order_main;
TRUNCATE TABLE dispense_ticket;
TRUNCATE TABLE reconcile_log;
TRUNCATE TABLE loss_audit_log;
TRUNCATE TABLE sms_otp_log;
TRUNCATE TABLE inventory_log;
TRUNCATE TABLE replenish_order;
TRUNCATE TABLE bar_inventory;

-- 2. 删除压测 C 端用户（保留管理员/酒吧/供酒商账号）
DELETE FROM sys_user WHERE role = 1 AND id >= 10000;

-- 3. 扩容分酒机瓶位：每台分酒机 100 个瓶位，每个瓶位 500000ml
--    2 台分酒机 × 100 瓶位 = 200 并发瓶位，总容量 100,000,000 ml
--    足够支撑 200 万笔 50ml 订单
DELETE FROM dispenser_slot WHERE dispenser_id IN (3001, 3002);

-- 分酒机 3001 的 100 个瓶位（酒款 2001）
INSERT INTO dispenser_slot (id, dispenser_id, slot_no, wine_sku_id, initial_capacity, current_capacity, batch_no, residual_ml, need_calibration)
SELECT
    40000 + seq.n AS id,
    3001 AS dispenser_id,
    seq.n AS slot_no,
    2001 AS wine_sku_id,
    500000 AS initial_capacity,
    500000 AS current_capacity,
    'BATCH_STRESS_01' AS batch_no,
    0 AS residual_ml,
    FALSE AS need_calibration
FROM (
    SELECT a.N + b.N * 10 + 1 AS n
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
) seq;

-- 分酒机 3002 的 100 个瓶位（酒款 2001）
INSERT INTO dispenser_slot (id, dispenser_id, slot_no, wine_sku_id, initial_capacity, current_capacity, batch_no, residual_ml, need_calibration)
SELECT
    42000 + seq.n AS id,
    3002 AS dispenser_id,
    seq.n AS slot_no,
    2001 AS wine_sku_id,
    500000 AS initial_capacity,
    500000 AS current_capacity,
    'BATCH_STRESS_02' AS batch_no,
    0 AS residual_ml,
    FALSE AS need_calibration
FROM (
    SELECT a.N + b.N * 10 + 1 AS n
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
) seq;

-- 4. 重建酒单：每个瓶位 × 50ml 规格
DELETE FROM bar_wine_menu WHERE bar_id IN (1001, 1002);

-- 酒吧 1001 / 分酒机 3001 的 100 个瓶位酒单
INSERT INTO bar_wine_menu (id, bar_id, dispenser_id, slot_id, wine_sku_id, volume_ml, volume_name, price, status)
SELECT
    50000 + seq.n AS id,
    1001 AS bar_id,
    3001 AS dispenser_id,
    40000 + seq.n AS slot_id,
    2001 AS wine_sku_id,
    50 AS volume_ml,
    '品尝杯' AS volume_name,
    18.00 AS price,
    1 AS status
FROM (
    SELECT a.N + b.N * 10 + 1 AS n
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
) seq;

-- 酒吧 1002 / 分酒机 3002 的 100 个瓶位酒单
INSERT INTO bar_wine_menu (id, bar_id, dispenser_id, slot_id, wine_sku_id, volume_ml, volume_name, price, status)
SELECT
    52000 + seq.n AS id,
    1002 AS bar_id,
    3002 AS dispenser_id,
    42000 + seq.n AS slot_id,
    2001 AS wine_sku_id,
    50 AS volume_ml,
    '品尝杯' AS volume_name,
    18.00 AS price,
    1 AS status
FROM (
    SELECT a.N + b.N * 10 + 1 AS n
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
) seq;

-- 5. 清空 Redis 缓存（需在应用外执行 redis-cli FLUSHALL）
-- 此处仅做标记，实际由压测客户端通过 redis-cli 或 Java Jedis 执行

SELECT '压测数据准备完成' AS status;
SELECT COUNT(*) AS total_slots FROM dispenser_slot;
SELECT MIN(current_capacity) AS min_cap, MAX(current_capacity) AS max_cap FROM dispenser_slot;
SELECT COUNT(*) AS menu_count FROM bar_wine_menu;
