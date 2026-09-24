package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.domain.*;
import com.wine.enums.DispenseTicketStatusEnum;
import com.wine.enums.OrderStatusEnum;
import com.wine.enums.PayStatusEnum;
import com.wine.mapper.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T+1 履约三单核对引擎
 * 每日凌晨 02:00 跑批：【主订单】↔【履约单】↔【通联授权状态】三向对齐
 * 平台管酒不管钱，核对信息与履约事实一致性，防盗刷与漏单
 */
@Slf4j
@Service
public class ReconcileService {

    @Resource
    private OrderMainMapper orderMainMapper;

    @Resource
    private DispenseTicketMapper dispenseTicketMapper;

    @Resource
    private ReconcileLogMapper reconcileLogMapper;

    @Resource
    private LossAuditLogMapper lossAuditLogMapper;

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    @Resource
    private PaymentService paymentService;

    /**
     * 执行 T+1 三向核对（针对前一日的订单）
     * 三向：主订单状态 ↔ 履约单状态 ↔ 通联授权状态
     */
    @Transactional
    public void reconcile(LocalDate date) {
        log.info("===== 三向核对跑批开始: date={} =====", date);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        // 查询当天所有订单（含各状态）
        List<OrderMain> orders = orderMainMapper.selectList(
                new LambdaQueryWrapper<OrderMain>()
                        .ge(OrderMain::getCreateTime, start)
                        .lt(OrderMain::getCreateTime, end));

        // 跑批汇总统计
        Map<String, Integer> summary = new HashMap<>();
        summary.put("total", orders.size());
        summary.put("consistent", 0);
        summary.put("anomalyA_leak", 0);
        summary.put("anomalyB_fraud", 0);
        summary.put("anomalyC_loss", 0);
        summary.put("anomalyD_auth", 0);
        summary.put("refunded", 0);

        for (OrderMain order : orders) {
            // 第一向：本地主订单状态
            int orderStatus = order.getStatus();
            Integer localPayStatus = order.getPayStatus();

            // 第二向：硬件履约单状态
            DispenseTicket ticket = dispenseTicketMapper.selectOne(
                    new LambdaQueryWrapper<DispenseTicket>().eq(DispenseTicket::getOrderNo, order.getOrderNo()));
            Integer ticketStatus = ticket != null ? ticket.getStatus() : null;

            // 第三向：通联授权状态（以通联侧交易查询为准）
            Integer allinpayAuthStatus = paymentService.queryAllinpayAuthStatus(order.getTransactionId());

            // ===== 三向对齐判定 =====

            // 异常D：通联授权状态与本地 payStatus 不一致 → 以通联为准修正本地
            if (allinpayAuthStatus != null && localPayStatus != null
                    && !allinpayAuthStatus.equals(localPayStatus)) {
                log.warn("异常D(通联授权不一致): orderNo={}, localPayStatus={}, allinpay={}",
                        order.getOrderNo(), localPayStatus, allinpayAuthStatus);
                // 以通联侧为准，修正本地支付状态
                order.setPayStatus(allinpayAuthStatus);
                if (allinpayAuthStatus == PayStatusEnum.SUCCESS.getCode()
                        && orderStatus == OrderStatusEnum.PENDING.getCode()) {
                    order.setStatus(OrderStatusEnum.PAID.getCode());
                }
                orderMainMapper.updateById(order);
                recordReconcile(order, ticket, allinpayAuthStatus, 5,
                        "异常D-通联授权与本地不一致，已以通联为准修正: local=" + localPayStatus + ", allinpay=" + allinpayAuthStatus);
                summary.merge("anomalyD_auth", 1, Integer::sum);
                continue;
            }

            // 统一使用通联授权状态作为支付基准（通联侧无记录则用本地）
            Integer payStatus = allinpayAuthStatus != null ? allinpayAuthStatus : localPayStatus;

            // 正常闭环：支付成功 + 履约成功
            if (orderStatus == OrderStatusEnum.COMPLETED.getCode()
                    && ticketStatus != null
                    && ticketStatus == DispenseTicketStatusEnum.SUCCESS.getCode()
                    && payStatus != null && payStatus == PayStatusEnum.SUCCESS.getCode()) {
                // 检查机械损耗
                if (ticket.getActualMl() != null
                        && ticket.getTargetMl() - ticket.getActualMl() > 5) {
                    // 异常C：机械损耗
                    recordReconcile(order, ticket, payStatus, 4,
                            "出酒损耗超5ml: target=" + ticket.getTargetMl() + ", actual=" + ticket.getActualMl());
                    markSlotCalibration(ticket.getDispenserId(), ticket.getSlotNo());
                    LossAuditLog loss = new LossAuditLog();
                    loss.setOrderNo(order.getOrderNo());
                    loss.setDispenserId(ticket.getDispenserId());
                    loss.setSlotNo(ticket.getSlotNo());
                    loss.setLossType(1);
                    loss.setTargetMl(ticket.getTargetMl());
                    loss.setActualMl(ticket.getActualMl());
                    loss.setLossMl(ticket.getTargetMl() - ticket.getActualMl());
                    loss.setRemark("T+1核对发现出酒损耗超阈值");
                    lossAuditLogMapper.insert(loss);
                    summary.merge("anomalyC_loss", 1, Integer::sum);
                } else {
                    recordReconcile(order, ticket, payStatus, 1, "核对一致");
                    summary.merge("consistent", 1, Integer::sum);
                }
            }
            // 异常A：支付成功但未出酒（漏单）→ 自动补偿退款
            else if (payStatus != null && payStatus == PayStatusEnum.SUCCESS.getCode()
                    && orderStatus != OrderStatusEnum.COMPLETED.getCode()
                    && orderStatus != OrderStatusEnum.REFUNDED.getCode()
                    && (ticket == null
                    || ticketStatus == DispenseTicketStatusEnum.READY.getCode()
                    || ticketStatus == DispenseTicketStatusEnum.FAILED.getCode())) {
                log.error("异常A(漏单): orderNo={}, 自动发起补偿退款", order.getOrderNo());
                recordReconcile(order, ticket, payStatus, 2,
                        "异常A-支付成功但未出酒，触发补偿退款");
                // 自动补偿退款
                try {
                    paymentService.refund(order.getOrderNo());
                    summary.merge("refunded", 1, Integer::sum);
                } catch (Exception e) {
                    log.error("补偿退款失败: orderNo={}", order.getOrderNo(), e);
                }
                summary.merge("anomalyA_leak", 1, Integer::sum);
            }
            // 异常B：未支付但有出酒记录（盗刷/飞单）→ 告警
            else if ((payStatus == null || payStatus != PayStatusEnum.SUCCESS.getCode())
                    && ticket != null
                    && ticketStatus == DispenseTicketStatusEnum.SUCCESS.getCode()) {
                log.error("异常B(盗刷告警): orderNo={}, payStatus={}", order.getOrderNo(), payStatus);
                recordReconcile(order, ticket, payStatus, 3,
                        "异常B-疑似盗刷：无支付授权但出酒成功");
                summary.merge("anomalyB_fraud", 1, Integer::sum);
            }
            // 其他状态（待支付、支付中、已退款等）不记录核对日志
        }

        // 输出跑批汇总报告
        log.info("===== 三向核对跑批完成 =====");
        log.info("核对日期: {}, 订单总数: {}", date, summary.get("total"));
        log.info("一致: {}, 异常A(漏单已退款): {}, 异常B(盗刷): {}, 异常C(损耗): {}, 异常D(授权不一致): {}",
                summary.get("consistent"), summary.get("anomalyA_leak"),
                summary.get("anomalyB_fraud"), summary.get("anomalyC_loss"), summary.get("anomalyD_auth"));
        log.info("自动补偿退款笔数: {}", summary.get("refunded"));
    }

    private void recordReconcile(OrderMain order, DispenseTicket ticket, Integer payStatus,
                                 int result, String desc) {
        ReconcileLog log = new ReconcileLog();
        log.setReconcileDate(LocalDate.now());
        log.setOrderNo(order.getOrderNo());
        log.setOrderStatus(order.getStatus());
        log.setTicketStatus(ticket != null ? ticket.getStatus() : null);
        log.setPayStatus(payStatus);
        log.setReconcileResult(result);
        log.setAnomalyDesc(desc);
        log.setHandled(result == 1 || result == 2 || result == 5);
        reconcileLogMapper.insert(log);
    }

    private void markSlotCalibration(Long dispenserId, Integer slotNo) {
        DispenserSlot slot = dispenserSlotMapper.selectOne(
                new LambdaQueryWrapper<DispenserSlot>()
                        .eq(DispenserSlot::getDispenserId, dispenserId)
                        .eq(DispenserSlot::getSlotNo, slotNo));
        if (slot != null) {
            slot.setNeedCalibration(true);
            dispenserSlotMapper.updateById(slot);
        }
    }
}
