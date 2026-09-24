package com.wine.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.Result;
import com.wine.common.UserContextHolder;
import com.wine.domain.*;
import com.wine.dto.ChangeBottleReq;
import com.wine.mapper.*;
import com.wine.service.BarOperationService;
import com.wine.service.ReconcileService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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

    /** 标准换瓶 SOP */
    @PostMapping("/change-bottle")
    public Result<Void> changeBottle(@Valid @RequestBody ChangeBottleReq req) {
        barOperationService.changeBottle(req);
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
}
