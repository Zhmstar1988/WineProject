-- ============================================================
-- 初始化数据一致性校验脚本
-- 用途：CI 阶段执行 data.sql 后，校验订单出酒量与瓶位容量扣减是否一致
-- 原理：每个瓶位的 SUM(order_main.volume_ml) 应等于 (initial_capacity - current_capacity)
-- 若存在差异，说明 data.sql 中的测试订单未同步扣减容量，会导致对账数据污染
-- 退出码：差异为 0 时通过，非 0 时 CI 失败
-- ============================================================

SELECT
    s.dispenser_id,
    s.slot_no,
    COALESCE(SUM(o.volume_ml), 0) AS order_ml,
    (s.initial_capacity - s.current_capacity) AS consumed_ml,
    (COALESCE(SUM(o.volume_ml), 0) - (s.initial_capacity - s.current_capacity)) AS diff_ml
FROM dispenser_slot s
LEFT JOIN order_main o
    ON o.dispenser_id = s.dispenser_id
    AND o.slot_no = s.slot_no
    AND o.deleted = 0
WHERE s.deleted = 0
GROUP BY s.dispenser_id, s.slot_no, s.initial_capacity, s.current_capacity
HAVING diff_ml <> 0;
