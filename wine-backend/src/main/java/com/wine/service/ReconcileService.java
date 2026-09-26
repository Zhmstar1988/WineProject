package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.domain.*;
import com.wine.enums.DispenseTicketStatusEnum;
import com.wine.enums.OrderStatusEnum;
import com.wine.enums.PayStatusEnum;
import com.wine.mapper.*;
import com.wine.config.AllinpayIntlConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

    @Resource
    private AllinpayIntlConfig allinpayIntlConfig;

    /**
     * 执行 T+1 三向核对（针对前一日的订单）
     * 三向：主订单状态 ↔ 履约单状态 ↔ 通联授权状态
     * <p>
     * 性能优化：
     * 1. 批量加载履约单（一次 IN 查询），避免 N+1
     * 2. mock 模式下通联授权状态直接复用本地 pay_status（mock 固定返回成功）
     * 3. 批量 INSERT 对账日志（每 1000 条一批）
     * <p>
     * 注意：本方法不加 @Transactional。百万级跑批若包裹在单事务中会长时间占用
     * 数据库连接，触发 HikariCP 连接泄漏告警。批量 INSERT 自带事务即可满足一致性。
     */
    public void reconcile(LocalDate date) {
        log.info("===== 三向核对跑批开始: date={} =====", date);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        // 查询当天所有订单（含各状态）
        List<OrderMain> orders = orderMainMapper.selectList(
                new LambdaQueryWrapper<OrderMain>()
                        .ge(OrderMain::getCreateTime, start)
                        .lt(OrderMain::getCreateTime, end));

        // 批量加载所有履约单，构建 orderNo -> ticket 映射（消除 N+1）
        List<String> orderNos = orders.stream().map(OrderMain::getOrderNo).collect(Collectors.toList());
        Map<String, DispenseTicket> ticketMap = new HashMap<>();
        if (!orderNos.isEmpty()) {
            // 分批 IN 查询，避免 IN 列表过长
            int batchSize = 5000;
            for (int i = 0; i < orderNos.size(); i += batchSize) {
                List<String> sub = orderNos.subList(i, Math.min(i + batchSize, orderNos.size()));
                List<DispenseTicket> tickets = dispenseTicketMapper.selectList(
                        new LambdaQueryWrapper<DispenseTicket>().in(DispenseTicket::getOrderNo, sub));
                for (DispenseTicket t : tickets) ticketMap.put(t.getOrderNo(), t);
            }
        }

        // mock 模式下通联固定返回成功，直接使用本地 pay_status 作为通联授权状态
        boolean mockMode = allinpayIntlConfig.isMock();

        // 跑批汇总统计
        Map<String, Integer> summary = new HashMap<>();
        summary.put("total", orders.size());
        summary.put("consistent", 0);
        summary.put("anomalyA_leak", 0);
        summary.put("anomalyB_fraud", 0);
        summary.put("anomalyC_loss", 0);
        summary.put("anomalyD_auth", 0);
        summary.put("refunded", 0);

        List<ReconcileLog> logBuffer = new ArrayList<>(1000);
        long idGen = System.currentTimeMillis();

        for (OrderMain order : orders) {
            int orderStatus = order.getStatus();
            Integer localPayStatus = order.getPayStatus();

            DispenseTicket ticket = ticketMap.get(order.getOrderNo());
            Integer ticketStatus = ticket != null ? ticket.getStatus() : null;

            // mock 模式：通联授权状态 = 本地支付状态（mock queryOrder 固定返回成功）
            Integer allinpayAuthStatus = mockMode ? localPayStatus
                    : paymentService.queryAllinpayAuthStatus(order.getTransactionId());

            // 异常D：通联授权状态与本地 payStatus 不一致
            if (allinpayAuthStatus != null && localPayStatus != null
                    && !allinpayAuthStatus.equals(localPayStatus)) {
                order.setPayStatus(allinpayAuthStatus);
                if (allinpayAuthStatus == PayStatusEnum.SUCCESS.getCode()
                        && orderStatus == OrderStatusEnum.PENDING.getCode()) {
                    order.setStatus(OrderStatusEnum.PAID.getCode());
                }
                orderMainMapper.updateById(order);
                logBuffer.add(buildLog(order, ticket, allinpayAuthStatus, 5,
                        "异常D-通联授权与本地不一致: local=" + localPayStatus + ", allinpay=" + allinpayAuthStatus, idGen++));
                summary.merge("anomalyD_auth", 1, Integer::sum);
                flushLogs(logBuffer);
                continue;
            }

            Integer payStatus = allinpayAuthStatus != null ? allinpayAuthStatus : localPayStatus;

            if (orderStatus == OrderStatusEnum.COMPLETED.getCode()
                    && ticketStatus != null
                    && ticketStatus == DispenseTicketStatusEnum.SUCCESS.getCode()
                    && payStatus != null && payStatus == PayStatusEnum.SUCCESS.getCode()) {
                if (ticket.getActualMl() != null
                        && ticket.getTargetMl() - ticket.getActualMl() > 5) {
                    logBuffer.add(buildLog(order, ticket, payStatus, 4,
                            "出酒损耗超5ml: target=" + ticket.getTargetMl() + ", actual=" + ticket.getActualMl(), idGen++));
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
                    logBuffer.add(buildLog(order, ticket, payStatus, 1, "核对一致", idGen++));
                    summary.merge("consistent", 1, Integer::sum);
                }
            } else if (payStatus != null && payStatus == PayStatusEnum.SUCCESS.getCode()
                    && orderStatus != OrderStatusEnum.COMPLETED.getCode()
                    && orderStatus != OrderStatusEnum.REFUNDED.getCode()
                    && (ticket == null
                    || ticketStatus == DispenseTicketStatusEnum.READY.getCode()
                    || ticketStatus == DispenseTicketStatusEnum.FAILED.getCode())) {
                logBuffer.add(buildLog(order, ticket, payStatus, 2, "异常A-支付成功但未出酒，触发补偿退款", idGen++));
                try {
                    paymentService.refund(order.getOrderNo());
                    summary.merge("refunded", 1, Integer::sum);
                } catch (Exception e) {
                    log.error("补偿退款失败: orderNo={}", order.getOrderNo(), e);
                }
                summary.merge("anomalyA_leak", 1, Integer::sum);
            } else if ((payStatus == null || payStatus != PayStatusEnum.SUCCESS.getCode())
                    && ticket != null
                    && ticketStatus == DispenseTicketStatusEnum.SUCCESS.getCode()) {
                logBuffer.add(buildLog(order, ticket, payStatus, 3, "异常B-疑似盗刷：无支付授权但出酒成功", idGen++));
                summary.merge("anomalyB_fraud", 1, Integer::sum);
            }
            flushLogs(logBuffer);
        }
        // 最后一批不足 1000 条也需刷盘
        if (!logBuffer.isEmpty()) {
            reconcileLogMapper.batchInsert(logBuffer);
            logBuffer.clear();
        }

        log.info("===== 三向核对跑批完成 =====");
        log.info("核对日期: {}, 订单总数: {}", date, summary.get("total"));
        log.info("一致: {}, 异常A(漏单已退款): {}, 异常B(盗刷): {}, 异常C(损耗): {}, 异常D(授权不一致): {}",
                summary.get("consistent"), summary.get("anomalyA_leak"),
                summary.get("anomalyB_fraud"), summary.get("anomalyC_loss"), summary.get("anomalyD_auth"));
        log.info("自动补偿退款笔数: {}", summary.get("refunded"));
    }

    private ReconcileLog buildLog(OrderMain order, DispenseTicket ticket, Integer payStatus,
                                  int result, String desc, long id) {
        ReconcileLog log = new ReconcileLog();
        log.setId(id);
        log.setReconcileDate(LocalDate.now());
        log.setOrderNo(order.getOrderNo());
        log.setOrderStatus(order.getStatus());
        log.setTicketStatus(ticket != null ? ticket.getStatus() : null);
        log.setPayStatus(payStatus);
        log.setReconcileResult(result);
        log.setAnomalyDesc(desc);
        log.setHandled(result == 1 || result == 2 || result == 5);
        return log;
    }

    private void flushLogs(List<ReconcileLog> buffer) {
        if (buffer.size() >= 1000) {
            reconcileLogMapper.batchInsert(new ArrayList<>(buffer));
            buffer.clear();
        }
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
