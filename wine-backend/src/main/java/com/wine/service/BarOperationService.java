package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.domain.DispenserSlot;
import com.wine.domain.LossAuditLog;
import com.wine.dto.ChangeBottleReq;
import com.wine.mapper.DispenserSlotMapper;
import com.wine.mapper.LossAuditLogMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 酒吧换瓶 SOP 服务
 * 整瓶红酒到货验收 -> 物理换瓶 -> 录入瓶位/容量 -> 容量重置
 * 损耗口径：残留 <=10ml 自动核销，>10ml 提示异常
 */
@Service
public class BarOperationService {

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    @Resource
    private LossAuditLogMapper lossAuditLogMapper;

    /**
     * 标准换瓶流程
     */
    @Transactional
    public void changeBottle(ChangeBottleReq req) {
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
            // 异常损耗，记录审计
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
