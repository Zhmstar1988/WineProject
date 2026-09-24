package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.domain.*;
import com.wine.dto.MenuResp;
import com.wine.mapper.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 酒单浏览服务
 * 按酒吧 × 分酒机 × 酒款展示可售酒品及杯量规格
 */
@Service
public class MenuService {

    @Resource
    private BarMapper barMapper;

    @Resource
    private BarWineMenuMapper barWineMenuMapper;

    @Resource
    private WineSkuMapper wineSkuMapper;

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    /**
     * 根据酒吧编码获取可售酒单
     */
    public MenuResp getMenuByBarCode(String barCode) {
        Bar bar = barMapper.selectOne(new LambdaQueryWrapper<Bar>().eq(Bar::getBarCode, barCode));
        if (bar == null) {
            throw new BusinessException("酒吧不存在");
        }
        return buildMenu(bar);
    }

    public MenuResp getMenuByBarId(Long barId) {
        Bar bar = barMapper.selectById(barId);
        if (bar == null) throw new BusinessException("酒吧不存在");
        return buildMenu(bar);
    }

    private MenuResp buildMenu(Bar bar) {
        MenuResp resp = new MenuResp();
        resp.setBarId(bar.getId());
        resp.setBarName(bar.getBarName());

        List<BarWineMenu> menus = barWineMenuMapper.selectList(
                new LambdaQueryWrapper<BarWineMenu>()
                        .eq(BarWineMenu::getBarId, bar.getId())
                        .eq(BarWineMenu::getStatus, 1));

        Map<Long, List<BarWineMenu>> grouped = menus.stream()
                .collect(Collectors.groupingBy(BarWineMenu::getWineSkuId));

        List<MenuResp.WineMenuItem> items = new ArrayList<>();
        for (Map.Entry<Long, List<BarWineMenu>> entry : grouped.entrySet()) {
            WineSku sku = wineSkuMapper.selectById(entry.getKey());
            if (sku == null || sku.getStatus() != 1) continue;

            MenuResp.WineMenuItem item = new MenuResp.WineMenuItem();
            item.setWineSkuId(sku.getId());
            item.setWineName(sku.getWineName());
            item.setOrigin(sku.getOrigin());
            item.setVintage(sku.getVintage());
            item.setGrapeType(sku.getGrapeType());
            item.setAlcohol(sku.getAlcohol());
            item.setCoverImage(sku.getCoverImage());
            item.setDescription(sku.getDescription());

            List<MenuResp.CupOption> cupOptions = new ArrayList<>();
            Integer currentCapacity = 0;
            for (BarWineMenu m : entry.getValue()) {
                MenuResp.CupOption cup = new MenuResp.CupOption();
                cup.setVolumeMl(m.getVolumeMl());
                cup.setVolumeName(m.getVolumeName());
                cup.setPrice(m.getPrice());
                cup.setSlotNo(m.getSlotId() != null ? getSlotNo(m.getSlotId()) : null);
                cup.setDispenserId(m.getDispenserId());
                cupOptions.add(cup);
                if (m.getSlotId() != null) {
                    DispenserSlot slot = dispenserSlotMapper.selectById(m.getSlotId());
                    if (slot != null) currentCapacity = slot.getCurrentCapacity();
                }
            }
            item.setCurrentCapacity(currentCapacity);
            item.setCupOptions(cupOptions);
            items.add(item);
        }
        resp.setWines(items);
        return resp;
    }

    private Integer getSlotNo(Long slotId) {
        DispenserSlot slot = dispenserSlotMapper.selectById(slotId);
        return slot != null ? slot.getSlotNo() : null;
    }
}
