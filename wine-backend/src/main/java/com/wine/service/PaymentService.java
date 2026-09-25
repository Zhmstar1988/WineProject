package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.client.AllinpayIntlClient;
import com.wine.common.BusinessException;
import com.wine.config.AllinpayIntlConfig;
import com.wine.domain.DispenseTicket;
import com.wine.domain.DispenserSlot;
import com.wine.domain.OrderMain;
import com.wine.dto.OrderResp;
import com.wine.enums.DispenseTicketStatusEnum;
import com.wine.enums.OrderStatusEnum;
import com.wine.enums.PayStatusEnum;
import com.wine.mapper.DispenseTicketMapper;
import com.wine.mapper.DispenserSlotMapper;
import com.wine.mapper.OrderMainMapper;
import com.wine.common.RedisDistributedLock;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 通联国际（Allinpay International）支付服务
 * <p>
 * 新加坡市场合规要点：
 * 1. 页面跳转模式：卡号/CVV 由通联收银台采集，商户侧不存储卡数据 → PCI DSS 合规
 * 2. 3DS 2.0 鉴权由通联收银台自动完成 → SCA 强客户认证
 * 3. 币种 SGD（新加坡元）
 * 4. 支付成功以异步通知（通联公钥验签）为准
 */
@Slf4j
@Service
public class PaymentService {

    private static final String RESULT_SUCCESS = "SUCCESS";

    @Resource
    private OrderMainMapper orderMainMapper;

    @Resource
    private DispenseTicketMapper dispenseTicketMapper;

    @Resource
    private OrderService orderService;

    @Resource
    private AllinpayIntlClient allinpayIntlClient;

    @Resource
    private AllinpayIntlConfig allinpayIntlConfig;

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    @Resource
    private RedisDistributedLock redisLock;

    @Value("${wine.lock.in-machine-prefix:wine:lock:slot:}")
    private String slotLockPrefix;

    /**
     * 发起支付：调用通联国际 CNP 收银台（页面跳转模式）
     * 金额单位为元（通联国际规范），订单转 PAYING
     */
    @Transactional
    public OrderResp pay(String orderNo) {
        OrderMain order = getOrder(orderNo);
        if (order.getStatus() != OrderStatusEnum.PENDING.getCode()) {
            throw new BusinessException("订单状态不允许支付");
        }

        // 通联国际金额单位为元（如 18.00），非分
        BigDecimal amount = order.getPaidAmount().setScale(2, RoundingMode.HALF_UP);
        String subject = "葡萄酒按杯订单-" + orderNo;

        // 调用通联国际 CNP 收银台下单（携带 cusid 分账指令）
        String cashierUrl = allinpayIntlClient.createOrder(orderNo, order.getCusid(), amount, subject);

        order.setStatus(OrderStatusEnum.PAYING.getCode());
        orderMainMapper.updateById(order);

        OrderResp resp = orderService.getOrder(orderNo);
        resp.setCashierUrl(cashierUrl);
        log.info("发起支付成功: orderNo={}, amount={} {}, cashierUrl={}",
                orderNo, amount, allinpayIntlConfig.getCurrency(), cashierUrl);
        return resp;
    }

    /**
     * 通联国际异步通知回调（Webhook）
     * 必须公钥验签 + 幂等
     * 通联国际字段：accessOrderId(商户订单号) transType resultCode amount currency
     */
    @Transactional
    public String handleNotify(Map<String, String> params) {
        // 1. 公钥验签（RSA2）
        if (!allinpayIntlClient.verify(params)) {
            log.warn("通联国际回调验签失败: {}", params);
            return "fail";
        }

        String orderNo = params.get("accessOrderId");
        String resultCode = params.get("resultCode");
        String notifyAmount = params.get("amount");
        String notifyCurrency = params.get("currency");
        log.info("收到通联国际回调: orderNo={}, resultCode={}, amount={}, currency={}",
                orderNo, resultCode, notifyAmount, notifyCurrency);

        if (orderNo == null || orderNo.isEmpty()) {
            log.warn("通联国际回调缺失 accessOrderId: {}", params);
            return "fail";
        }

        OrderMain order = orderMainMapper.selectOne(
                new LambdaQueryWrapper<OrderMain>().eq(OrderMain::getOrderNo, orderNo));
        if (order == null) {
            log.warn("通联国际回调订单不存在: orderNo={}", orderNo);
            return "fail";
        }

        // 2. 幂等：非 PAYING 状态说明已处理过，直接回 success（先于金额校验，避免重复回调因金额差异被拒）
        if (order.getStatus() != OrderStatusEnum.PAYING.getCode()) {
            log.info("通联国际回调幂等命中，订单已处理: orderNo={}, status={}", orderNo, order.getStatus());
            return "success";
        }

        // 3. 防篡改：核对回调金额与订单实付金额一致（单位：元）
        if (notifyAmount != null && !notifyAmount.isEmpty()) {
            BigDecimal orderAmount = order.getPaidAmount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal notifyAmt = new BigDecimal(notifyAmount).setScale(2, RoundingMode.HALF_UP);
            if (orderAmount.compareTo(notifyAmt) != 0) {
                log.warn("通联国际回调金额不匹配: orderNo={}, orderAmount={}, notifyAmount={}",
                        orderNo, orderAmount, notifyAmt);
                return "fail";
            }
        }

        // 4. 防篡改：核对回调币种与配置币种一致
        if (notifyCurrency != null && !notifyCurrency.isEmpty()
                && !notifyCurrency.equals(allinpayIntlConfig.getCurrency())) {
            log.warn("通联国际回调币种不匹配: orderNo={}, configCurrency={}, notifyCurrency={}",
                    orderNo, allinpayIntlConfig.getCurrency(), notifyCurrency);
            return "fail";
        }

        if (RESULT_SUCCESS.equals(resultCode)) {
            // 支付成功：订单转 PAID，生成出酒履约单
            order.setStatus(OrderStatusEnum.PAID.getCode());
            order.setPayStatus(PayStatusEnum.SUCCESS.getCode());
            order.setTransactionId(params.getOrDefault("transId", orderNo));
            order.setPayTime(LocalDateTime.now());
            orderMainMapper.updateById(order);

            DispenseTicket ticket = new DispenseTicket();
            ticket.setOrderNo(orderNo);
            ticket.setOrderId(order.getId());
            ticket.setUserId(order.getUserId());
            ticket.setDispenserId(order.getDispenserId());
            ticket.setSlotNo(order.getSlotNo());
            ticket.setTargetMl(order.getVolumeMl());
            ticket.setStatus(DispenseTicketStatusEnum.READY.getCode());
            ticket.setCupPresent(false);
            dispenseTicketMapper.insert(ticket);

            log.info("支付成功，生成履约单: orderNo={}", orderNo);
        } else {
            // 支付失败：订单回退 PENDING，允许重新发起，同时立即返还预占容量
            order.setStatus(OrderStatusEnum.PENDING.getCode());
            order.setPayStatus(PayStatusEnum.FAILED.getCode());
            orderMainMapper.updateById(order);
            // 立即返还下单时预占的在机容量（避免等到超时定时任务才释放）
            try {
                restoreCapacity(order.getDispenserId(), order.getSlotNo(), order.getVolumeMl());
            } catch (Exception e) {
                log.warn("支付失败返还容量异常: orderNo={}", orderNo, e);
            }
            log.info("支付失败，已返还预占容量: orderNo={}, resultCode={}", orderNo, resultCode);
        }
        return "success";
    }

    /**
     * 原路全额退款
     */
    @Transactional
    public void refund(String orderNo) {
        OrderMain order = getOrder(orderNo);
        if (order.getStatus() != OrderStatusEnum.PAID.getCode()
                && order.getStatus() != OrderStatusEnum.PAYING.getCode()) {
            throw new BusinessException("订单状态不允许退款");
        }

        BigDecimal amount = order.getPaidAmount().setScale(2, RoundingMode.HALF_UP);
        String refundOrderNo = "RF" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + orderNo.hashCode();

        // 已支付订单调用通联国际退款；支付中订单通联侧无交易，仅置本地状态
        if (order.getStatus() == OrderStatusEnum.PAID.getCode()) {
            allinpayIntlClient.refund(refundOrderNo, orderNo, amount);
        } else {
            log.info("订单处于支付中，通联国际侧无交易记录，仅置本地退款状态: orderNo={}", orderNo);
        }

        order.setStatus(OrderStatusEnum.REFUNDED.getCode());
        order.setPayStatus(PayStatusEnum.REFUNDED.getCode());
        order.setRefundTime(LocalDateTime.now());
        order.setRefundNo(refundOrderNo);
        orderMainMapper.updateById(order);

        // 返还下单时预留的在机容量
        restoreCapacity(order.getDispenserId(), order.getSlotNo(), order.getVolumeMl());

        log.info("退款成功: orderNo={}, refundOrderNo={}, amount={} {}",
                orderNo, refundOrderNo, amount, allinpayIntlConfig.getCurrency());
    }

    /** 返还在机容量（退款时调用） */
    private void restoreCapacity(Long dispenserId, Integer slotNo, Integer ml) {
        if (dispenserId == null || slotNo == null || ml == null) return;
        String lockKey = slotLockPrefix + dispenserId + ":" + slotNo;
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = redisLock.lockWithTimeout(lockKey, lockValue, 3000, 10);
            if (!locked) return;
            DispenserSlot slot = dispenserSlotMapper.selectOne(
                    new LambdaQueryWrapper<DispenserSlot>()
                            .eq(DispenserSlot::getDispenserId, dispenserId)
                            .eq(DispenserSlot::getSlotNo, slotNo));
            if (slot != null && slot.getInitialCapacity() != null) {
                slot.setCurrentCapacity(Math.min(slot.getInitialCapacity(), slot.getCurrentCapacity() + ml));
                dispenserSlotMapper.updateById(slot);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) redisLock.unlock(lockKey, lockValue);
        }
    }

    private OrderMain getOrder(String orderNo) {
        OrderMain order = orderMainMapper.selectOne(
                new LambdaQueryWrapper<OrderMain>().eq(OrderMain::getOrderNo, orderNo));
        if (order == null) throw new BusinessException("订单不存在");
        return order;
    }

    /**
     * 查询通联国际侧支付状态（三向对齐的第三方基准）
     *
     * @param transactionId 通联交易单号
     * @return 1-支付成功 0-未支付/失败 null-无交易记录
     */
    public Integer queryAllinpayAuthStatus(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            return null;
        }
        OrderMain order = orderMainMapper.selectOne(
                new LambdaQueryWrapper<OrderMain>().eq(OrderMain::getTransactionId, transactionId));
        if (order == null) {
            log.warn("通联国际状态查询：本地无 transactionId={} 的订单记录", transactionId);
            return null;
        }
        try {
            Map<String, String> result = allinpayIntlClient.queryOrder(order.getOrderNo());
            String resultCode = result.get("resultCode");
            log.info("通联国际交易查询: orderNo={}, transactionId={}, resultCode={}",
                    order.getOrderNo(), transactionId, resultCode);
            if (RESULT_SUCCESS.equals(resultCode)) {
                return 1;
            }
            return 0;
        } catch (Exception e) {
            log.error("通联国际交易查询异常: orderNo={}, msg={}", order.getOrderNo(), e.getMessage());
            return null;
        }
    }
}
