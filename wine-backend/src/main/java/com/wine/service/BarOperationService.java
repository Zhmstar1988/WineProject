package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.domain.BarInventory;
import com.wine.domain.Dispenser;
import com.wine.domain.DispenserSlot;
import com.wine.domain.InventoryLog;
import com.wine.domain.LossAuditLog;
import com.wine.dto.BatchChangeBottleReq;
import com.wine.dto.ChangeBottleReq;
import com.wine.mapper.BarInventoryMapper;
import com.wine.mapper.DispenserMapper;
import com.wine.mapper.DispenserSlotMapper;
import com.wine.mapper.InventoryLogMapper;
import com.wine.mapper.LossAuditLogMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒吧换瓶 SOP 服务
 * 流程：从后备库存扣减1瓶 → 物理换瓶 → 录入瓶位/容量 → 容量重置 → 写库存流水
 * 损耗口径：残留 <=10ml 自动核销，>10ml 提示异常
 */
@Service
public class BarOperationService {

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    @Resource
    private DispenserMapper dispenserMapper;

    @Resource
    private BarInventoryMapper barInventoryMapper;

    @Resource
    private InventoryLogMapper inventoryLogMapper;

    @Resource
    private LossAuditLogMapper lossAuditLogMapper;

    /**
     * 标准换瓶流程：扣减后备库存 + 重置瓶位容量 + 出库流水
     * 单瓶位换瓶，内部复用批量换瓶逻辑
     */
    @Transactional
    public void changeBottle(ChangeBottleReq req) {
        BatchChangeBottleReq.ChangeBottleItem item = new BatchChangeBottleReq.ChangeBottleItem();
        item.setSlotNo(req.getSlotNo());
        item.setWineSkuId(req.getWineSkuId());
        item.setInitialCapacity(req.getInitialCapacity());
        item.setBatchNo(req.getBatchNo());
        item.setResidualMl(req.getResidualMl());

        BatchChangeBottleReq batch = new BatchChangeBottleReq();
        batch.setDispenserId(req.getDispenserId());
        batch.setItems(List.of(item));
        batchChangeBottle(batch);
    }

    /**
     * 批量换瓶：一次给分酒机的多个瓶位同时换瓶
     * 按酒款聚合扣减后备库存，任一酒款库存不足则全部回滚
     */
    @Transactional
    public void batchChangeBottle(BatchChangeBottleReq req) {
        Dispenser dispenser = dispenserMapper.selectById(req.getDispenserId());
        if (dispenser == null) throw new BusinessException("分酒机不存在");
        Long barId = dispenser.getBarId();

        // 按酒款聚合统计需要扣减的瓶数
        Map<Long, Integer> skuQtyMap = new HashMap<>();
        for (BatchChangeBottleReq.ChangeBottleItem item : req.getItems()) {
            skuQtyMap.merge(item.getWineSkuId(), 1, Integer::sum);
        }

        // 先校验所有酒款库存是否充足
        Map<Long, Integer> beforeQtyMap = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : skuQtyMap.entrySet()) {
            Long skuId = entry.getKey();
            int needQty = entry.getValue();
            BarInventory inv = barInventoryMapper.selectOne(
                    new LambdaQueryWrapper<BarInventory>()
                            .eq(BarInventory::getBarId, barId)
                            .eq(BarInventory::getWineSkuId, skuId));
            int beforeQty = inv != null ? inv.getQuantity() : 0;
            if (beforeQty < needQty) {
                throw new BusinessException(
                        "后备库存不足：酒款SKU=" + skuId + " 需要" + needQty + "瓶，当前仅" + beforeQty + "瓶");
            }
            beforeQtyMap.put(skuId, beforeQty);
        }

        // 原子扣减每个酒款的后备库存 + 写出库流水
        for (Map.Entry<Long, Integer> entry : skuQtyMap.entrySet()) {
            Long skuId = entry.getKey();
            int needQty = entry.getValue();
            int beforeQty = beforeQtyMap.get(skuId);
            int affected = barInventoryMapper.deductQuantity(barId, skuId, needQty);
            if (affected == 0) {
                throw new BusinessException("后备库存扣减失败，酒款SKU: " + skuId);
            }
            int afterQty = beforeQty - needQty;

            InventoryLog log = new InventoryLog();
            log.setBarId(barId);
            log.setWineSkuId(skuId);
            log.setChangeType(2); // 出库
            log.setChangeQty(-needQty);
            log.setBeforeQty(beforeQty);
            log.setAfterQty(afterQty);
            log.setRefType("change_bottle");
            log.setRefNo("BATCH:" + req.getDispenserId());
            log.setRemark("批量换瓶出库，分酒机=" + req.getDispenserId() + " 共" + needQty + "瓶");
            inventoryLogMapper.insert(log);
        }

        // 逐个处理瓶位：重置容量 + 损耗审计
        for (BatchChangeBottleReq.ChangeBottleItem item : req.getItems()) {
            processSlot(req.getDispenserId(), item);
        }
    }

    /** 处理单个瓶位的容量重置和损耗审计 */
    private void processSlot(Long dispenserId, BatchChangeBottleReq.ChangeBottleItem item) {
        DispenserSlot slot = dispenserSlotMapper.selectOne(
                new LambdaQueryWrapper<DispenserSlot>()
                        .eq(DispenserSlot::getDispenserId, dispenserId)
                        .eq(DispenserSlot::getSlotNo, item.getSlotNo()));
        if (slot == null) {
            slot = new DispenserSlot();
            slot.setDispenserId(dispenserId);
            slot.setSlotNo(item.getSlotNo());
        }

        int residual = item.getResidualMl() != null ? item.getResidualMl() : 0;
        int systemCapacity = slot.getCurrentCapacity() != null ? slot.getCurrentCapacity() : 0;

        // 损耗判断：系统记录余量 与 店员实测残留 的差异
        // 差异 = |系统余量 - 实测残留|，超过阈值(50ml)才告警
        // 主动换瓶(剩150ml就换)和用尽换瓶(剩5ml)都是正常操作，不算损耗
        int diff = Math.abs(systemCapacity - residual);
        int lossThreshold = 50; // 计量偏差容忍阈值 50ml
        if (diff > lossThreshold) {
            LossAuditLog loss = new LossAuditLog();
            loss.setDispenserId(dispenserId);
            loss.setSlotNo(item.getSlotNo());
            loss.setLossType(2); // 换瓶损耗
            loss.setTargetMl(lossThreshold);
            loss.setActualMl(diff);
            loss.setLossMl(diff - lossThreshold);
            loss.setRemark("换瓶计量偏差超阈值：系统余量=" + systemCapacity + "ml，实测残留="
                    + residual + "ml，偏差=" + diff + "ml，请核查是否有泄漏/偷酒/设备故障");
            lossAuditLogMapper.insert(loss);
            slot.setNeedCalibration(true); // 标记需要校准
        }

        slot.setWineSkuId(item.getWineSkuId());
        slot.setInitialCapacity(item.getInitialCapacity());
        slot.setCurrentCapacity(item.getInitialCapacity());
        slot.setBatchNo(item.getBatchNo());
        slot.setResidualMl(residual);
        if (diff <= lossThreshold) {
            slot.setNeedCalibration(false);
        }

        if (slot.getId() == null) {
            dispenserSlotMapper.insert(slot);
        } else {
            dispenserSlotMapper.updateById(slot);
        }
    }
}
