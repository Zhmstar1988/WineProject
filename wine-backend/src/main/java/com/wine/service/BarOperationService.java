package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.domain.BarInventory;
import com.wine.domain.Dispenser;
import com.wine.domain.DispenserSlot;
import com.wine.domain.InventoryLog;
import com.wine.domain.LossAuditLog;
import com.wine.dto.ChangeBottleReq;
import com.wine.mapper.BarInventoryMapper;
import com.wine.mapper.DispenserMapper;
import com.wine.mapper.DispenserSlotMapper;
import com.wine.mapper.InventoryLogMapper;
import com.wine.mapper.LossAuditLogMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     */
    @Transactional
    public void changeBottle(ChangeBottleReq req) {
        // 查询分酒机获取 barId
        Dispenser dispenser = dispenserMapper.selectById(req.getDispenserId());
        if (dispenser == null) throw new BusinessException("分酒机不存在");
        Long barId = dispenser.getBarId();

        // 从后备库存扣减 1 瓶（原子扣减，库存不足则失败）
        BarInventory inv = barInventoryMapper.selectOne(
                new LambdaQueryWrapper<BarInventory>()
                        .eq(BarInventory::getBarId, barId)
                        .eq(BarInventory::getWineSkuId, req.getWineSkuId()));
        if (inv == null || inv.getQuantity() < 1) {
            throw new BusinessException("后备库存不足，请先补货酒款SKU: " + req.getWineSkuId());
        }
        int beforeQty = inv.getQuantity();
        int affected = barInventoryMapper.deductQuantity(barId, req.getWineSkuId(), 1);
        if (affected == 0) {
            throw new BusinessException("后备库存扣减失败，请重试");
        }
        int afterQty = beforeQty - 1;

        // 写出库流水
        InventoryLog log = new InventoryLog();
        log.setBarId(barId);
        log.setWineSkuId(req.getWineSkuId());
        log.setChangeType(2); // 出库
        log.setChangeQty(-1);
        log.setBeforeQty(beforeQty);
        log.setAfterQty(afterQty);
        log.setRefType("change_bottle");
        log.setRefNo(req.getDispenserId() + ":" + req.getSlotNo());
        log.setRemark("换瓶出库，分酒机=" + req.getDispenserId() + " 瓶位=" + req.getSlotNo());
        inventoryLogMapper.insert(log);

        // 查找或创建瓶位
        DispenserSlot slot = dispenserSlotMapper.selectOne(
                new LambdaQueryWrapper<DispenserSlot>()
                        .eq(DispenserSlot::getDispenserId, req.getDispenserId())
                        .eq(DispenserSlot::getSlotNo, req.getSlotNo()));

        if (slot == null) {
            slot = new DispenserSlot();
            slot.setDispenserId(req.getDispenserId());
            slot.setSlotNo(req.getSlotNo());
        }

        // 上一瓶残留量损耗处理
        int residual = req.getResidualMl() != null ? req.getResidualMl() : 0;
        if (residual > 10) {
            LossAuditLog loss = new LossAuditLog();
            loss.setDispenserId(req.getDispenserId());
            loss.setSlotNo(req.getSlotNo());
            loss.setLossType(2);
            loss.setTargetMl(10);
            loss.setActualMl(residual);
            loss.setLossMl(residual - 10);
            loss.setRemark("换瓶残留量超过10ml合理损耗阈值，请确认异常原因");
            lossAuditLogMapper.insert(loss);
        }

        // 重置瓶位容量
        slot.setWineSkuId(req.getWineSkuId());
        slot.setInitialCapacity(req.getInitialCapacity());
        slot.setCurrentCapacity(req.getInitialCapacity());
        slot.setBatchNo(req.getBatchNo());
        slot.setResidualMl(residual);
        slot.setNeedCalibration(false);

        if (slot.getId() == null) {
            dispenserSlotMapper.insert(slot);
        } else {
            dispenserSlotMapper.updateById(slot);
        }
    }
}
