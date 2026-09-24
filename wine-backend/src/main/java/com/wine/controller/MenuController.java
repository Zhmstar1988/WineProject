package com.wine.controller;

import com.wine.common.Result;
import com.wine.domain.Bar;
import com.wine.dto.MenuResp;
import com.wine.mapper.BarMapper;
import com.wine.service.MenuService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 酒单与酒吧接口
 */
@RestController
@RequestMapping("/menu")
public class MenuController {

    @Resource
    private MenuService menuService;

    @Resource
    private BarMapper barMapper;

    /** 根据酒吧编码获取酒单（扫码入口） */
    @GetMapping("/bar/{barCode}")
    public Result<MenuResp> getMenuByBarCode(@PathVariable String barCode) {
        return Result.success(menuService.getMenuByBarCode(barCode));
    }

    /** 根据4位酒吧码查询 */
    @GetMapping("/bar/by-code/{shortCode}")
    public Result<Bar> getBarByShortCode(@PathVariable String shortCode) {
        return Result.success(barMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Bar>()
                        .eq(Bar::getShortCode, shortCode)));
    }
}
