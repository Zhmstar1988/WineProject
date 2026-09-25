package com.wine.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.Result;
import com.wine.common.UserContextHolder;
import com.wine.domain.*;
import com.wine.dto.BatchChangeBottleReq;
import com.wine.dto.ChangeBottleReq;
import com.wine.dto.ReplenishCreateReq;
import com.wine.dto.ReplenishReceiveReq;
import com.wine.mapper.*;
import com.wine.service.BarOperationService;
import com.wine.service.ReconcileService;
import com.wine.service.ReplenishService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台接口（平台运营端 + 酒吧营业端）
 * 多租户：按 bar_id 强制数据隔离
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Resource
    private BarMapper barMapper;

    @Resource
    private DispenserMapper dispenserMapper;

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    @Resource
    private OrderMainMapper orderMainMapper;

    @Resource
    private ReconcileLogMapper reconcileLogMapper;

    @Resource
    private BarOperationService barOperationService;

    @Resource
    private ReconcileService reconcileService;

    @Resource
    private BarInventoryMapper barInventoryMapper;

    @Resource
    private WineSkuMapper wineSkuMapper;

    @Resource
    private ReplenishService replenishService;

    @Resource
    private ReplenishOrderMapper replenishOrderMapper;

    @Resource
    private InventoryLogMapper inventoryLogMapper;

    /** 酒吧列表（平台端） */
    @GetMapping("/bars")
    public Result<List<Bar>> listBars() {
        return Result.success(barMapper.selectList(
                new LambdaQueryWrapper<Bar>().orderByDesc(Bar::getCreateTime)));
    }

    /** 新增酒吧（平台端） */
    @PostMapping("/bars")
    public Result<Bar> createBar(@RequestBody Bar bar) {
        if (bar.getStatus() == null) bar.setStatus(1);
        if (bar.getMerchantStatus() == null) bar.setMerchantStatus(0);
        barMapper.insert(bar);
        return Result.success(bar);
    }

    /** 更新酒吧（平台端） */
    @PutMapping("/bars/{id}")
    public Result<Void> updateBar(@PathVariable Long id, @RequestBody Bar bar) {
        bar.setId(id);
        barMapper.updateById(bar);
        return Result.success();
    }

    /** 删除酒吧（平台端） */
    @DeleteMapping("/bars/{id}")
    public Result<Void> deleteBar(@PathVariable Long id) {
        barMapper.deleteById(id);
        return Result.success();
    }

    /** 本店分酒机列表（酒吧端） */
    @GetMapping("/dispensers")
    public Result<List<Dispenser>> listDispensers() {
        Integer role = UserContextHolder.getRole();
        LambdaQueryWrapper<Dispenser> qw = new LambdaQueryWrapper<>();
        // 酒吧端只看本店，平台端看全部
        if (role != null && role == 2) {
            Long barId = UserContextHolder.get().getBarId();
            qw.eq(Dispenser::getBarId, barId);
        }
        qw.orderByDesc(Dispenser::getCreateTime);
        return Result.success(dispenserMapper.selectList(qw));
    }

    /** 瓶位实时余量监控 */
    @GetMapping("/slots/{dispenserId}")
    public Result<List<DispenserSlot>> listSlots(@PathVariable Long dispenserId) {
        return Result.success(dispenserSlotMapper.selectList(
                new LambdaQueryWrapper<DispenserSlot>().eq(DispenserSlot::getDispenserId, dispenserId)));
    }

    /** 新增设备（平台端） */
    @PostMapping("/dispensers")
    public Result<Dispenser> createDispenser(@RequestBody Dispenser d) {
        if (d.getStatus() == null) d.setStatus(1);
        if (d.getSlotCount() == null) d.setSlotCount(8);
        dispenserMapper.insert(d);
        return Result.success(d);
    }

    /** 更新设备 */
    @PutMapping("/dispensers/{id}")
    public Result<Void> updateDispenser(@PathVariable Long id, @RequestBody Dispenser d) {
        d.setId(id);
        dispenserMapper.updateById(d);
        return Result.success();
    }

    /** 删除设备 */
    @DeleteMapping("/dispensers/{id}")
    public Result<Void> deleteDispenser(@PathVariable Long id) {
        dispenserMapper.deleteById(id);
        return Result.success();
    }

    /** 订单流水：平台端看全部，酒吧端看本店 */
    @GetMapping("/orders")
    public Result<List<OrderMain>> listOrders() {
        Integer role = UserContextHolder.getRole();
        LambdaQueryWrapper<OrderMain> qw = new LambdaQueryWrapper<>();
        if (role != null && role == 2) {
            Long barId = UserContextHolder.get().getBarId();
            qw.eq(OrderMain::getBarId, barId);
        }
        qw.orderByDesc(OrderMain::getCreateTime);
        return Result.success(orderMainMapper.selectList(qw));
    }

    /** 标准换瓶 SOP（单瓶位） */
    @PostMapping("/change-bottle")
    public Result<Void> changeBottle(@Valid @RequestBody ChangeBottleReq req) {
        barOperationService.changeBottle(req);
        return Result.success();
    }

    /** 批量换瓶（一次给多个瓶位同时换瓶，按酒款聚合扣减后备库存） */
    @PostMapping("/change-bottle/batch")
    public Result<Void> batchChangeBottle(@Valid @RequestBody BatchChangeBottleReq req) {
        barOperationService.batchChangeBottle(req);
        return Result.success();
    }

    /** 履约核对日志 */
    @GetMapping("/reconcile")
    public Result<List<ReconcileLog>> listReconcile() {
        return Result.success(reconcileLogMapper.selectList(
                new LambdaQueryWrapper<ReconcileLog>().orderByDesc(ReconcileLog::getCreateTime)));
    }

    /** 手动触发三向核对跑批（指定日期，默认昨天） */
    @PostMapping("/reconcile/run")
    public Result<Void> runReconcile(@RequestParam(name = "date", required = false) String date) {
        java.time.LocalDate target = (date != null && !date.isBlank())
                ? java.time.LocalDate.parse(date)
                : java.time.LocalDate.now().minusDays(1);
        reconcileService.reconcile(target);
        return Result.success();
    }

    // ==================== 月度对账单导出（差距2） ====================

    /**
     * 导出月度对账单（CSV）
     * 酒吧端导出本店订单流水，平台端可指定 barId 或导出全部
     *
     * @param month 月份，格式 yyyy-MM，默认上个月
     */
    @GetMapping("/orders/export")
    public void exportMonthlyOrders(
            @RequestParam(name = "month", required = false) String month,
            @RequestParam(name = "barId", required = false) Long barId,
            HttpServletResponse response) throws IOException {
        // 解析月份
        LocalDate monthStart;
        if (month == null || month.isBlank()) {
            monthStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        } else {
            monthStart = LocalDate.parse(month + "-01");
        }
        LocalDate monthEnd = monthStart.plusMonths(1);

        // 查询订单（按角色隔离）
        LambdaQueryWrapper<OrderMain> qw = new LambdaQueryWrapper<>();
        qw.ge(OrderMain::getCreateTime, monthStart.atStartOfDay())
                .lt(OrderMain::getCreateTime, monthEnd.atStartOfDay())
                .orderByAsc(OrderMain::getCreateTime);

        Integer role = UserContextHolder.getRole();
        if (role != null && role == 2) {
            // 酒吧端：只看本店
            Long userBarId = UserContextHolder.get().getBarId();
            qw.eq(OrderMain::getBarId, userBarId);
        } else if (barId != null) {
            // 平台端指定酒吧
            qw.eq(OrderMain::getBarId, barId);
        }
        List<OrderMain> orders = orderMainMapper.selectList(qw);

        // 构建 CSV
        String fileName = "monthly-orders-" + monthStart + ".csv";
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        // BOM for Excel
        sb.append('\uFEFF');
        sb.append("订单号,酒吧ID,用户ID,分酒机ID,瓶位,酒款SKU,杯量(ml),原价(SGD),优惠(SGD),实付(SGD),订单状态,支付状态,通联交易号,创建时间,支付时间,完成时间\n");
        for (OrderMain o : orders) {
            sb.append(String.join(",",
                    nvl(o.getOrderNo()),
                    nvl(o.getBarId()),
                    nvl(o.getUserId()),
                    nvl(o.getDispenserId()),
                    nvl(o.getSlotNo()),
                    nvl(o.getWineSkuId()),
                    nvl(o.getVolumeMl()),
                    nvl(o.getOriginalAmount()),
                    nvl(o.getDiscountAmount()),
                    nvl(o.getPaidAmount()),
                    nvl(o.getStatus()),
                    nvl(o.getPayStatus()),
                    nvl(o.getTransactionId()),
                    nvl(o.getCreateTime()),
                    nvl(o.getPayTime()),
                    nvl(o.getCompleteTime())
            )).append("\n");
        }
        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    private static String nvl(Object o) {
        return o == null ? "" : String.valueOf(o).replace(",", "，");
    }

    // ==================== 实物整瓶库存管理（差距4） ====================

    /** 后备库存列表（酒吧端看本店，平台端可指定 barId） */
    @GetMapping("/inventory")
    public Result<List<BarInventory>> listInventory(
            @RequestParam(name = "barId", required = false) Long barId) {
        Integer role = UserContextHolder.getRole();
        LambdaQueryWrapper<BarInventory> qw = new LambdaQueryWrapper<>();
        if (role != null && role == 2) {
            Long userBarId = UserContextHolder.get().getBarId();
            qw.eq(BarInventory::getBarId, userBarId);
        } else if (barId != null) {
            qw.eq(BarInventory::getBarId, barId);
        }
        qw.orderByDesc(BarInventory::getCreateTime);
        return Result.success(barInventoryMapper.selectList(qw));
    }

    /** 新增库存记录（入库） */
    @PostMapping("/inventory")
    public Result<BarInventory> createInventory(@RequestBody BarInventory inv) {
        if (inv.getStatus() == null) inv.setStatus(1);
        if (inv.getQuantity() == null) inv.setQuantity(0);
        if (inv.getAlertThreshold() == null) inv.setAlertThreshold(0);
        // 酒吧端强制为本店
        Integer role = UserContextHolder.getRole();
        if (role != null && role == 2) {
            inv.setBarId(UserContextHolder.get().getBarId());
        }
        barInventoryMapper.insert(inv);
        return Result.success(inv);
    }

    /** 更新库存（调整数量/阈值/位置） */
    @PutMapping("/inventory/{id}")
    public Result<Void> updateInventory(@PathVariable Long id, @RequestBody BarInventory inv) {
        inv.setId(id);
        barInventoryMapper.updateById(inv);
        return Result.success();
    }

    /** 删除库存记录 */
    @DeleteMapping("/inventory/{id}")
    public Result<Void> deleteInventory(@PathVariable Long id) {
        barInventoryMapper.deleteById(id);
        return Result.success();
    }

    /** 库存预警列表：数量 <= 预警阈值 */
    @GetMapping("/inventory/alert")
    public Result<List<BarInventory>> alertInventory(
            @RequestParam(name = "barId", required = false) Long barId) {
        Integer role = UserContextHolder.getRole();
        LambdaQueryWrapper<BarInventory> qw = new LambdaQueryWrapper<>();
        if (role != null && role == 2) {
            Long userBarId = UserContextHolder.get().getBarId();
            qw.eq(BarInventory::getBarId, userBarId);
        } else if (barId != null) {
            qw.eq(BarInventory::getBarId, barId);
        }
        qw.apply("quantity <= alert_threshold");
        return Result.success(barInventoryMapper.selectList(qw));
    }

    // ==================== 补货单 + 到货验收 ====================

    /** 补货单列表（酒吧端看本店，平台端可指定 barId） */
    @GetMapping("/replenish")
    public Result<List<ReplenishOrder>> listReplenish(
            @RequestParam(name = "barId", required = false) Long barId,
            @RequestParam(name = "status", required = false) Integer status) {
        Integer role = UserContextHolder.getRole();
        LambdaQueryWrapper<ReplenishOrder> qw = new LambdaQueryWrapper<>();
        if (role != null && role == 2) {
            qw.eq(ReplenishOrder::getBarId, UserContextHolder.get().getBarId());
        } else if (barId != null) {
            qw.eq(ReplenishOrder::getBarId, barId);
        }
        if (status != null) qw.eq(ReplenishOrder::getStatus, status);
        qw.orderByDesc(ReplenishOrder::getCreateTime);
        return Result.success(replenishOrderMapper.selectList(qw));
    }

    /** 创建补货单（酒吧向酒商下单） */
    @PostMapping("/replenish")
    public Result<ReplenishOrder> createReplenish(@Valid @RequestBody ReplenishCreateReq req) {
        // 酒吧端强制为本店
        Integer role = UserContextHolder.getRole();
        if (role != null && role == 2) {
            req.setBarId(UserContextHolder.get().getBarId());
        }
        return Result.success(replenishService.createReplenish(req));
    }

    /** 酒商发货 */
    @PostMapping("/replenish/{id}/ship")
    public Result<ReplenishOrder> shipReplenish(@PathVariable Long id) {
        return Result.success(replenishService.ship(id));
    }

    /** 到货验收：实收数量入库 + 写库存流水 */
    @PostMapping("/replenish/{id}/receive")
    public Result<ReplenishOrder> receiveReplenish(
            @PathVariable Long id,
            @Valid @RequestBody ReplenishReceiveReq req) {
        return Result.success(replenishService.receive(id, req));
    }

    /** 拒收 */
    @PostMapping("/replenish/{id}/reject")
    public Result<ReplenishOrder> rejectReplenish(
            @PathVariable Long id,
            @RequestParam(name = "reason", required = false) String reason) {
        return Result.success(replenishService.reject(id, reason));
    }

    // ==================== 库存变动流水 ====================

    /** 库存变动流水列表 */
    @GetMapping("/inventory/logs")
    public Result<List<InventoryLog>> listInventoryLogs(
            @RequestParam(name = "barId", required = false) Long barId,
            @RequestParam(name = "wineSkuId", required = false) Long wineSkuId) {
        Integer role = UserContextHolder.getRole();
        LambdaQueryWrapper<InventoryLog> qw = new LambdaQueryWrapper<>();
        if (role != null && role == 2) {
            qw.eq(InventoryLog::getBarId, UserContextHolder.get().getBarId());
        } else if (barId != null) {
            qw.eq(InventoryLog::getBarId, barId);
        }
        if (wineSkuId != null) qw.eq(InventoryLog::getWineSkuId, wineSkuId);
        qw.orderByDesc(InventoryLog::getCreateTime);
        return Result.success(inventoryLogMapper.selectList(qw));
    }
}
