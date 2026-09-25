package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.domain.BarInventory;
import com.wine.domain.InventoryLog;
import com.wine.domain.ReplenishOrder;
import com.wine.dto.ReplenishCreateReq;
import com.wine.dto.ReplenishReceiveReq;
import com.wine.mapper.BarInventoryMapper;
import com.wine.mapper.InventoryLogMapper;
import com.wine.mapper.ReplenishOrderMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 补货单服务：酒商补货 + 到货验收 + 入库联动
 * 状态流转：待发货(0) → 已发货(1) → 已验收(2) / 已拒收(3)
 */
@Service
public class ReplenishService {

    @Resource
    private ReplenishOrderMapper replenishOrderMapper;

    @Resource
    private BarInventoryMapper barInventoryMapper;

    @Resource
    private InventoryLogMapper inventoryLogMapper;

    /**
     * 1. 酒吧创建补货单（向酒商下单）
     */
    @Transactional
    public ReplenishOrder createReplenish(ReplenishCreateReq req) {
        ReplenishOrder order = new ReplenishOrder();
        order.setOrderNo(generateReplenishNo());
        order.setBarId(req.getBarId());
        order.setSupplierId(req.getSupplierId());
        order.setWineSkuId(req.getWineSkuId());
        order.setQuantity(req.getQuantity());
        order.setUnitPrice(req.getUnitPrice());
        if (req.getUnitPrice() != null) {
            order.setTotalAmount(req.getUnitPrice().multiply(new BigDecimal(req.getQuantity())));
        }
        order.setStatus(0); // 待发货
        order.setRemark(req.getRemark());
        replenishOrderMapper.insert(order);
        return order;
    }

    /**
     * 2. 酒商发货
     */
    @Transactional
    public ReplenishOrder ship(Long orderId) {
        ReplenishOrder order = getAndCheck(orderId);
        if (order.getStatus() != 0) {
            throw new BusinessException("仅待发货状态可发货");
        }
        order.setStatus(1); // 已发货
        order.setShippedTime(LocalDateTime.now());
        replenishOrderMapper.updateById(order);
        return order;
    }

    /**
     * 3. 酒吧到货验收：实收数量入库 + 写库存流水
     * 幂等：已验收订单重复调用不重复入库
     */
    @Transactional
    public ReplenishOrder receive(Long orderId, ReplenishReceiveReq req) {
        ReplenishOrder order = getAndCheck(orderId);
        if (order.getStatus() != 1) {
            throw new BusinessException("仅已发货状态可验收");
        }

        int receivedQty = req.getReceivedQty();
        if (receivedQty <= 0) {
            throw new BusinessException("实收数量必须大于0");
        }

        // 记录变动前库存
        BarInventory inv = barInventoryMapper.selectOne(
                new LambdaQueryWrapper<BarInventory>()
                        .eq(BarInventory::getBarId, order.getBarId())
                        .eq(BarInventory::getWineSkuId, order.getWineSkuId()));
        int beforeQty = inv != null ? inv.getQuantity() : 0;

        // 入库：不存在则新增，存在则原子增加
        if (inv == null) {
            inv = new BarInventory();
            inv.setBarId(order.getBarId());
            inv.setWineSkuId(order.getWineSkuId());
            inv.setQuantity(receivedQty);
            inv.setStatus(1);
            barInventoryMapper.insert(inv);
        } else {
            barInventoryMapper.addQuantity(order.getBarId(), order.getWineSkuId(), receivedQty);
        }

        int afterQty = beforeQty + receivedQty;

        // 写库存流水（入库）
        InventoryLog log = new InventoryLog();
        log.setBarId(order.getBarId());
        log.setWineSkuId(order.getWineSkuId());
        log.setChangeType(1); // 入库
        log.setChangeQty(receivedQty);
        log.setBeforeQty(beforeQty);
        log.setAfterQty(afterQty);
        log.setRefType("replenish");
        log.setRefNo(order.getOrderNo());
        log.setRemark("补货验收入库，订单：" + order.getOrderNo());
        inventoryLogMapper.insert(log);

        // 更新补货单状态
        order.setStatus(2); // 已验收
        order.setReceivedQty(receivedQty);
        order.setReceivedTime(LocalDateTime.now());
        order.setRemark(req.getRemark());
        replenishOrderMapper.updateById(order);

        return order;
    }

    /**
     * 4. 拒收（酒商发货后酒吧拒收）
     */
    @Transactional
    public ReplenishOrder reject(Long orderId, String reason) {
        ReplenishOrder order = getAndCheck(orderId);
        if (order.getStatus() != 1) {
            throw new BusinessException("仅已发货状态可拒收");
        }
        order.setStatus(3); // 已拒收
        order.setRejectReason(reason);
        replenishOrderMapper.updateById(order);
        return order;
    }

    /**
     * 查询补货单
     */
    public ReplenishOrder getById(Long orderId) {
        return getAndCheck(orderId);
    }

    private ReplenishOrder getAndCheck(Long orderId) {
        ReplenishOrder order = replenishOrderMapper.selectById(orderId);
        if (order == null) throw new BusinessException("补货单不存在");
        return order;
    }

    private String generateReplenishNo() {
        return "RPL" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
