-- ============================================================
-- 初始化测试数据
-- ============================================================

-- 酒吧（2家示例）
INSERT INTO bar (id, bar_code, bar_name, address, contact_phone, cusid, merchant_status, status, short_code) VALUES
(1001, 'SG_BAR_01', 'Marina Bay Wine Bar', '1 Bayfront Ave, Singapore', '+65 6123 4567', 'CUSID_SG_001', 2, 1, 'A001'),
(1002, 'SG_BAR_02', 'Clarke Quay Red Wine', '3 River Valley Rd, Singapore', '+65 6234 5678', 'CUSID_SG_002', 2, 1, 'A002');

-- 酒款SKU
INSERT INTO wine_sku (id, wine_name, origin, vintage, grape_type, alcohol, supplier_id, cover_image, description, status) VALUES
(2001, 'Château Margaux 2015', 'France - Bordeaux', 2015, 'Cabernet Sauvignon Blend', '13.5%', 1, '', '法国波尔多一级庄，酒体饱满，单宁细腻', 1),
(2002, 'Penfolds Grange 2017', 'Australia - South Australia', 2017, 'Shiraz', '14.5%', 1, '', '澳洲酒王，浓郁黑莓与巧克力风味', 1),
(2003, 'Opus One 2018', 'USA - Napa Valley', 2018, 'Cabernet Sauvignon', '14.0%', 1, '', '纳帕谷顶级混酿，优雅与力量并存', 1);

-- 分酒机
INSERT INTO dispenser (id, bar_id, device_no, mac, device_name, slot_count, status, last_heartbeat, firmware_version) VALUES
(3001, 1001, 'DEV-SG-0001', 'AA:BB:CC:DD:EE:01', 'Marina Bay Dispenser 01', 4, 1, CURRENT_TIMESTAMP, 'v2.1.0'),
(3002, 1002, 'DEV-SG-0002', 'AA:BB:CC:DD:EE:02', 'Clarke Quay Dispenser 01', 4, 1, CURRENT_TIMESTAMP, 'v2.1.0');

-- 分酒机瓶位（含初始容量）
INSERT INTO dispenser_slot (id, dispenser_id, slot_no, wine_sku_id, initial_capacity, current_capacity, batch_no, residual_ml, need_calibration) VALUES
(4001, 3001, 1, 2001, 750, 750, 'BATCH20260901', 0, FALSE),
(4002, 3001, 2, 2002, 750, 600, 'BATCH20260901', 0, FALSE),
(4003, 3001, 3, 2003, 750, 750, 'BATCH20260902', 0, FALSE),
(4004, 3002, 1, 2001, 750, 500, 'BATCH20260901', 5, FALSE),
(4005, 3002, 2, 2002, 750, 750, 'BATCH20260903', 0, FALSE);

-- 酒吧酒单（杯量规格与售价）
INSERT INTO bar_wine_menu (id, bar_id, dispenser_id, slot_id, wine_sku_id, volume_ml, volume_name, price, status) VALUES
(5001, 1001, 3001, 4001, 2001, 50,  '品尝杯', 18.00, 1),
(5002, 1001, 3001, 4001, 2001, 150, '标准杯', 48.00, 1),
(5003, 1001, 3001, 4002, 2002, 50,  '品尝杯', 15.00, 1),
(5004, 1001, 3001, 4002, 2002, 150, '标准杯', 42.00, 1),
(5005, 1001, 3001, 4003, 2003, 50,  '品尝杯', 22.00, 1),
(5006, 1001, 3001, 4003, 2003, 150, '标准杯', 58.00, 1),
(5007, 1002, 3002, 4004, 2001, 50,  '品尝杯', 18.00, 1),
(5008, 1002, 3002, 4004, 2001, 150, '标准杯', 48.00, 1),
(5009, 1002, 3002, 4005, 2002, 50,  '品尝杯', 15.00, 1),
(5010, 1002, 3002, 4005, 2002, 150, '标准杯', 42.00, 1);

-- 平台超管账号（测试用）
INSERT INTO sys_user (id, phone, openid, login_type, role, nickname, age_verified, status) VALUES
(9001, '+6590000000', NULL, 1, 5, 'Platform Admin', TRUE, 1);

-- 酒吧管理员（测试用，关联酒吧1001）
INSERT INTO sys_user (id, phone, openid, login_type, role, nickname, bar_id, age_verified, status) VALUES
(9002, '+6590000001', NULL, 1, 2, 'Marina Bay Bar Manager', 1001, TRUE, 1);

-- 供酒商（测试用）
INSERT INTO sys_user (id, phone, openid, login_type, role, nickname, supplier_id, age_verified, status) VALUES
(9004, '+6590000003', NULL, 1, 6, 'Premium Wine Supplier', 1, TRUE, 1);

-- ============================================================
-- 三向核对跑批测试订单（覆盖一致/异常A漏单/异常B盗刷/异常C损耗/异常D授权不一致）
-- 注：通联授权状态查询当前为 mock，固定返回 1(成功)
-- ============================================================

-- 场景1：正常闭环 - 支付成功 + 出酒成功
INSERT INTO order_main (id, order_no, user_id, bar_id, cusid, dispenser_id, slot_no, wine_sku_id, volume_ml, original_amount, discount_amount, paid_amount, status, pay_status, transaction_id, pay_time, complete_time, create_time) VALUES
(10001, 'TEST20260924001', 9002, 1001, 'CUSID_SG_001', 3001, 1, 2001, 50, 18.00, 0, 18.00, 4, 1, 'TXN001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO dispense_ticket (id, order_no, order_id, user_id, dispenser_id, slot_no, target_ml, actual_ml, status, cup_present, dispense_start_time, dispense_end_time, create_time) VALUES
(20001, 'TEST20260924001', 10001, 9002, 3001, 1, 50, 50, 3, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 场景2：异常A-漏单 - 支付成功但未出酒
INSERT INTO order_main (id, order_no, user_id, bar_id, cusid, dispenser_id, slot_no, wine_sku_id, volume_ml, original_amount, discount_amount, paid_amount, status, pay_status, transaction_id, pay_time, create_time) VALUES
(10002, 'TEST20260924002', 9002, 1001, 'CUSID_SG_001', 3001, 2, 2002, 150, 48.00, 0, 48.00, 3, 1, 'TXN002', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 场景3：异常B-盗刷 - 未支付但出酒成功
INSERT INTO order_main (id, order_no, user_id, bar_id, cusid, dispenser_id, slot_no, wine_sku_id, volume_ml, original_amount, discount_amount, paid_amount, status, pay_status, create_time) VALUES
(10003, 'TEST20260924003', 9002, 1001, 'CUSID_SG_001', 3001, 3, 2003, 50, 22.00, 0, 22.00, 1, 0, CURRENT_TIMESTAMP);
INSERT INTO dispense_ticket (id, order_no, order_id, user_id, dispenser_id, slot_no, target_ml, actual_ml, status, cup_present, dispense_start_time, dispense_end_time, create_time) VALUES
(20003, 'TEST20260924003', 10003, 9002, 3001, 3, 50, 50, 3, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 场景4：异常C-损耗 - 正常闭环但实际出酒少8ml
INSERT INTO order_main (id, order_no, user_id, bar_id, cusid, dispenser_id, slot_no, wine_sku_id, volume_ml, original_amount, discount_amount, paid_amount, status, pay_status, transaction_id, pay_time, complete_time, create_time) VALUES
(10004, 'TEST20260924004', 9002, 1001, 'CUSID_SG_001', 3001, 1, 2001, 150, 48.00, 0, 48.00, 4, 1, 'TXN004', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO dispense_ticket (id, order_no, order_id, user_id, dispenser_id, slot_no, target_ml, actual_ml, status, cup_present, dispense_start_time, dispense_end_time, create_time) VALUES
(20004, 'TEST20260924004', 10004, 9002, 3001, 1, 150, 142, 3, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 场景5：异常D-授权不一致 - 本地支付状态为0，但通联返回1(成功)
INSERT INTO order_main (id, order_no, user_id, bar_id, cusid, dispenser_id, slot_no, wine_sku_id, volume_ml, original_amount, discount_amount, paid_amount, status, pay_status, transaction_id, pay_time, create_time) VALUES
(10005, 'TEST20260924005', 9002, 1001, 'CUSID_SG_001', 3001, 2, 2002, 50, 15.00, 0, 15.00, 1, 0, 'TXN005', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 测试订单对应容量扣减（保证库存一致性）
-- 3001:1: TEST001(50ml) + TEST004(150ml) = 200ml
UPDATE dispenser_slot SET current_capacity = current_capacity - 200 WHERE dispenser_id = 3001 AND slot_no = 1;
-- 3001:2: TEST002(150ml) + TEST005(50ml) = 200ml
UPDATE dispenser_slot SET current_capacity = current_capacity - 200 WHERE dispenser_id = 3001 AND slot_no = 2;
-- 3001:3: TEST003(50ml) = 50ml
UPDATE dispenser_slot SET current_capacity = current_capacity - 50 WHERE dispenser_id = 3001 AND slot_no = 3;
