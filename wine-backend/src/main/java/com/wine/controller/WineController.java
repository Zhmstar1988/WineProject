package com.wine.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.Result;
import com.wine.common.UserContextHolder;
import com.wine.domain.WineSku;
import com.wine.mapper.WineSkuMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 酒款管理接口
 * 平台运营：全部酒款
 * 供酒商：仅自己供应的酒款（按 supplierId 隔离）
 */
@RestController
@RequestMapping("/wines")
public class WineController {

    @Resource
    private WineSkuMapper wineSkuMapper;

    @GetMapping
    public Result<List<WineSku>> list() {
        Integer role = UserContextHolder.getRole();
        Long supplierId = UserContextHolder.get().getSupplierId();
        LambdaQueryWrapper<WineSku> qw = new LambdaQueryWrapper<>();
        // 供酒商只看自己的酒款
        if (role != null && role == 6 && supplierId != null) {
            qw.eq(WineSku::getSupplierId, supplierId);
        }
        qw.orderByDesc(WineSku::getCreateTime);
        return Result.success(wineSkuMapper.selectList(qw));
    }

    @PostMapping
    public Result<WineSku> create(@RequestBody WineSku wine) {
        Integer role = UserContextHolder.getRole();
        // 供酒商只能创建自己的酒款
        if (role != null && role == 6) {
            wine.setSupplierId(UserContextHolder.get().getSupplierId());
        }
        if (wine.getStatus() == null) wine.setStatus(1);
        wineSkuMapper.insert(wine);
        return Result.success(wine);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody WineSku wine) {
        wine.setId(id);
        wineSkuMapper.updateById(wine);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        wineSkuMapper.deleteById(id);
        return Result.success();
    }
}
