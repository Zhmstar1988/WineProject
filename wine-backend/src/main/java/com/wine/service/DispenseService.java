package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.common.RedisDistributedLock;
import com.wine.domain.*;
import com.wine.enums.DispenseTicketStatusEnum;
import com.wine.enums.OrderStatusEnum;
import com.wine.mapper.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 分酒机 IoT 出酒履约服务（线下机械出酒域）
 * 履约单状态机：READY -> DISPENSING -> SUCCESS / FAILED
 * 幂等：以 order_id（orderNo）作为全局唯一幂等键
 * SLA：指令响应<=3s，出酒<=30s，网络容忍<=45s
 */
@Slf4j
@Service
public class DispenseService {

    @Resource
    private DispenseTicketMapper dispenseTicketMapper;

    @Resource
    private OrderMainMapper orderMainMapper;

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    @Resource
    private LossAuditLogMapper lossAuditLogMapper;

    @Resource
    private RedisDistributedLock redisLock;

    @Value("${wine.lock.in-machine-prefix:wine:lock:slot:}")
    private String slotLockPrefix;

    /**
     * 查询分酒机就绪状态（含杯位感应 cup_present）
     * GET /api/v1/dispenser/{device_id}/status
     */
    public Map<String, Object> checkStatus(Long dispenserId, Integer slotNo) {
        // TODO: 调用分酒机状态读取接口
        // 模拟返回：设备在线，目标瓶位正常，杯位有杯
        Map<String, Object> status = new HashMap<>();
        status.put("status", "READY");
        status.put("dispenserId", dispenserId);
        status.put("slotId", slotNo);
        status.put("cup_present", 1);
        status.put("temperature", "16.5°C");
        return status;
    }

    /**
     * 一键出酒：携带 order_id 幂等键下发出酒指令
     * POST /api/v1/dispenser/{device_id}/dispense
     */
    @Transactional
    public void startDispense(String orderNo) {
        DispenseTicket ticket = getTicket(orderNo);
        if (ticket.getStatus() != DispenseTicketStatusEnum.READY.getCode()) {
            throw new BusinessException("履约单状态不允许出酒");
        }

        // 校验杯位感应
        Map<String, Object> status = checkStatus(ticket.getDispenserId(), ticket.getSlotNo());
        Integer cupPresent = (Integer) status.get("cup_present");
        if (cupPresent == null || cupPresent != 1) {
            throw new BusinessException("未检测到酒杯，请先放好酒杯");
        }

        // 幂等下发：orderNo 作为幂等键
        ticket.setStatus(DispenseTicketStatusEnum.DISPENSING.getCode());
        ticket.setCupPresent(true);
        ticket.setDispenseStartTime(LocalDateTime.now());
        dispenseTicketMapper.updateById(ticket);

        // TODO: 调用分酒机出酒接口，载荷 {order_id, slot_id, volume_ml}
        // 分酒机以 order_id 物理去重
        log.info("下发出酒指令: orderNo={}, slot={}:{}, targetMl={}",
                orderNo, ticket.getDispenserId(), ticket.getSlotNo(), ticket.getTargetMl());
    }

    /**
     * 分酒机出酒结果回调
     * POST /api/v1/dispenser/callback
     * 载荷: {order_id, status: SUCCESS/FAILED, actual_ml}
     */
    @Transactional
    public void handleCallback(Map<String, Object> params) {
        String orderNo = (String) params.get("order_id");
        String status = (String) params.get("status");
        Integer actualMl = params.get("actual_ml") != null
                ? ((Number) params.get("actual_ml")).intValue() : null;

        DispenseTicket ticket = getTicket(orderNo);
        if (ticket.getStatus() != DispenseTicketStatusEnum.DISPENSING.getCode()
                && ticket.getStatus() != DispenseTicketStatusEnum.READY.getCode()) {
            // 幂等：已处理直接返回
            log.warn("履约单已处理，忽略回调: orderNo={}", orderNo);
            return;
        }

        ticket.setActualMl(actualMl);
        ticket.setDispenseEndTime(LocalDateTime.now());

        if ("SUCCESS".equals(status) && actualMl != null && actualMl >= ticket.getTargetMl() - 5) {
            // 出酒成功：履约单 SUCCESS，驱动主订单 COMPLETED，扣减在机余量
            ticket.setStatus(DispenseTicketStatusEnum.SUCCESS.getCode());
            ticket.setSlaMet(checkSla(ticket));
            dispenseTicketMapper.updateById(ticket);

            OrderMain order = orderMainMapper.selectById(ticket.getOrderId());
            order.setStatus(OrderStatusEnum.COMPLETED.getCode());
            order.setCompleteTime(LocalDateTime.now());
            orderMainMapper.updateById(order);

            // 扣减在机物理余量（加锁保证原子性）
            deductCapacity(ticket.getDispenserId(), ticket.getSlotNo(), ticket.getTargetMl());

            log.info("出酒成功: orderNo={}, actualMl={}", orderNo, actualMl);
        } else {
            // 出酒失败：履约单 FAILED，驱动主订单 REFUNDED，损耗审计
            ticket.setStatus(DispenseTicketStatusEnum.FAILED.getCode());
            ticket.setFailReason("出酒失败或不足");
            ticket.setSlaMet(false);
            dispenseTicketMapper.updateById(ticket);

            // 损耗审计
            LossAuditLog loss = new LossAuditLog();
            loss.setOrderNo(orderNo);
            loss.setDispenserId(ticket.getDispenserId());
            loss.setSlotNo(ticket.getSlotNo());
            loss.setLossType(1);
            loss.setTargetMl(ticket.getTargetMl());
            loss.setActualMl(actualMl != null ? actualMl : 0);
            loss.setLossMl(ticket.getTargetMl() - (actualMl != null ? actualMl : 0));
            loss.setRemark("出酒不足，全额退款，差额记入损耗审计");
            lossAuditLogMapper.insert(loss);

            // 驱动主订单退款
            OrderMain order = orderMainMapper.selectById(ticket.getOrderId());
            order.setStatus(OrderStatusEnum.REFUNDED.getCode());
            order.setRefundTime(LocalDateTime.now());
            order.setRefundNo("RF" + System.currentTimeMillis());
            orderMainMapper.updateById(order);

            log.warn("出酒失败，自动退款: orderNo={}, actualMl={}", orderNo, actualMl);
        }
    }

    /**
     * 出酒前用户取消订单
     */
    @Transactional
    public void cancelBeforeDispense(String orderNo) {
        DispenseTicket ticket = getTicket(orderNo);
        if (ticket.getStatus() == DispenseTicketStatusEnum.DISPENSING.getCode()
                || ticket.getStatus() == DispenseTicketStatusEnum.SUCCESS.getCode()) {
            throw new BusinessException("出酒进行中或已完成，不可取消");
        }
        ticket.setStatus(DispenseTicketStatusEnum.FAILED.getCode());
        ticket.setFailReason("用户主动取消");
        dispenseTicketMapper.updateById(ticket);

        OrderMain order = orderMainMapper.selectById(ticket.getOrderId());
        order.setStatus(OrderStatusEnum.REFUNDED.getCode());
        order.setRefundTime(LocalDateTime.now());
        orderMainMapper.updateById(order);
    }

    private void deductCapacity(Long dispenserId, Integer slotNo, Integer ml) {
        String lockKey = slotLockPrefix + dispenserId + ":" + slotNo;
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = redisLock.lockWithTimeout(lockKey, lockValue, 3000, 10);
            if (!locked) throw new BusinessException("容量扣减繁忙");
            DispenserSlot slot = dispenserSlotMapper.selectOne(
                    new LambdaQueryWrapper<DispenserSlot>()
                            .eq(DispenserSlot::getDispenserId, dispenserId)
                            .eq(DispenserSlot::getSlotNo, slotNo));
            if (slot != null) {
                slot.setCurrentCapacity(Math.max(0, slot.getCurrentCapacity() - ml));
                dispenserSlotMapper.updateById(slot);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) redisLock.unlock(lockKey, lockValue);
        }
    }

    private boolean checkSla(DispenseTicket ticket) {
        if (ticket.getDispenseStartTime() == null || ticket.getDispenseEndTime() == null) return false;
        long seconds = java.time.Duration.between(ticket.getDispenseStartTime(), ticket.getDispenseEndTime()).getSeconds();
        return seconds <= 30;
    }

    private DispenseTicket getTicket(String orderNo) {
        DispenseTicket ticket = dispenseTicketMapper.selectOne(
                new LambdaQueryWrapper<DispenseTicket>().eq(DispenseTicket::getOrderNo, orderNo));
        if (ticket == null) throw new BusinessException("履约单不存在");
        return ticket;
    }
}
