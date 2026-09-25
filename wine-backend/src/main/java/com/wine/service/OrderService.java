package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wine.client.AllinpayIntlClient;
import com.wine.common.BusinessException;
import com.wine.common.RedisDistributedLock;
import com.wine.domain.*;
import com.wine.dto.CreateOrderReq;
import com.wine.dto.OrderResp;
import com.wine.enums.DispenseTicketStatusEnum;
import com.wine.enums.OrderStatusEnum;
import com.wine.enums.PayStatusEnum;
import com.wine.mapper.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 交易订单服务（线上资金结算域）
 * 状态机：PENDING -> PAYING -> PAID -> COMPLETED / REFUNDED
 * 并发保障：Redis 分布式锁锁定瓶位在机容量，防止超卖
 */
@Slf4j
@Service
public class OrderService {

    @Resource
    private OrderMainMapper orderMainMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private BarMapper barMapper;

    @Resource
    private BarWineMenuMapper barWineMenuMapper;

    @Resource
    private DispenserSlotMapper dispenserSlotMapper;

    @Resource
    private WineSkuMapper wineSkuMapper;

    @Resource
    private RedisDistributedLock redisLock;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AllinpayIntlClient allinpayIntlClient;

    @Resource
    private DispenseTicketMapper dispenseTicketMapper;

    @Value("${wine.order.pay-timeout-minutes:15}")
    private int payTimeoutMinutes;

    @Value("${wine.order.paying-poll-minutes:10}")
    private int payingPollMinutes;

    @Value("${wine.lock.in-machine-prefix:wine:lock:slot:}")
    private String slotLockPrefix;

    private static final String IDEM_PREFIX = "wine:idem:order:";
    private static final long IDEM_TTL_HOURS = 24;

    /**
     * 下单
     * 0. 幂等性校验（Idempotency-Key + Redis 锁）：相同 key 只创建一次订单
     * 1. 校验年龄合规
     * 2. Redis 锁瓶位，检查在机余量
     * 3. 后端统一计价（不信任端侧金额）
     * 4. 创建 PENDING 订单，设置支付超时
     */
    @Transactional
    public OrderResp createOrder(Long userId, CreateOrderReq req, String idempotencyKey) {
        // 无幂等键：直接创建订单
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateOrder(userId, req, null);
        }

        // 有幂等键：先查缓存
        String cacheKey = IDEM_PREFIX + idempotencyKey;
        OrderResp cached = getIdemCache(cacheKey);
        if (cached != null) {
            log.info("幂等命中(缓存): idempotencyKey={}, orderNo={}", idempotencyKey, cached.getOrderNo());
            return cached;
        }

        // 尝试获取幂等锁，确保同一 key 只有一个请求创建订单
        String idemLockKey = "idem:" + idempotencyKey;
        String idemLockValue = UUID.randomUUID().toString();
        boolean idemLocked = redisLock.tryLock(idemLockKey, idemLockValue, 30);

        if (idemLocked) {
            try {
                // 双重检查：获取锁后再次查缓存
                cached = getIdemCache(cacheKey);
                if (cached != null) {
                    log.info("幂等命中(锁内二次检查): idempotencyKey={}, orderNo={}", idempotencyKey, cached.getOrderNo());
                    return cached;
                }
                // 创建订单并缓存结果
                OrderResp resp = doCreateOrder(userId, req, cacheKey);
                return resp;
            } finally {
                redisLock.unlock(idemLockKey, idemLockValue);
            }
        } else {
            // 未获取到锁：等待其他请求完成并写入缓存，轮询读取
            return waitForIdemCache(cacheKey, idempotencyKey);
        }
    }

    /** 从 Redis 读取幂等缓存 */
    private OrderResp getIdemCache(String cacheKey) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, OrderResp.class);
            }
        } catch (Exception e) {
            log.warn("幂等缓存读取失败: cacheKey={}", cacheKey, e);
        }
        return null;
    }

    /** 轮询等待幂等缓存写入（其他请求正在处理同一 key） */
    private OrderResp waitForIdemCache(String cacheKey, String idempotencyKey) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            OrderResp cached = getIdemCache(cacheKey);
            if (cached != null) {
                log.info("幂等命中(等待后): idempotencyKey={}, orderNo={}", idempotencyKey, cached.getOrderNo());
                return cached;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("系统繁忙，请重试");
            }
        }
        throw new BusinessException("系统繁忙，请稍后重试");
    }

    /**
     * 实际创建订单（内部方法）
     * @param cacheKey 幂等缓存 key，非空则创建后写入缓存
     */
    private OrderResp doCreateOrder(Long userId, CreateOrderReq req, String cacheKey) {
        // 1. 年龄合规校验
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        if (!Boolean.TRUE.equals(user.getAgeVerified())) {
            throw new BusinessException("请先完成法定年龄校验");
        }

        // 2. 查询酒单配置（后端统一计价）
        BarWineMenu menu = barWineMenuMapper.selectOne(
                new LambdaQueryWrapper<BarWineMenu>()
                        .eq(BarWineMenu::getBarId, req.getBarId())
                        .eq(BarWineMenu::getDispenserId, req.getDispenserId())
                        .eq(BarWineMenu::getWineSkuId, req.getWineSkuId())
                        .eq(BarWineMenu::getVolumeMl, req.getVolumeMl())
                        .eq(BarWineMenu::getStatus, 1));
        if (menu == null) throw new BusinessException("酒单配置不存在或已下架");

        // 3. 锁外预查酒吧（不依赖瓶位并发，提前到锁外减少锁持有时间）
        Bar bar = barMapper.selectById(req.getBarId());

        // 4. Redis 分布式锁锁定瓶位，检查在机余量防超卖
        String lockKey = slotLockPrefix + req.getDispenserId() + ":" + req.getSlotNo();
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;
        OrderMain order = null;
        try {
            locked = redisLock.lockWithTimeout(lockKey, lockValue, 3000, 15);
            if (!locked) throw new BusinessException("当前瓶位繁忙，请稍后重试");

            DispenserSlot slot = dispenserSlotMapper.selectOne(
                    new LambdaQueryWrapper<DispenserSlot>()
                            .eq(DispenserSlot::getDispenserId, req.getDispenserId())
                            .eq(DispenserSlot::getSlotNo, req.getSlotNo()));
            if (slot == null) throw new BusinessException("瓶位不存在");

            // 原子扣减在机容量（数据库行锁保证并发安全，防超卖）
            int affected = dispenserSlotMapper.deductCapacity(
                    req.getDispenserId(), req.getSlotNo(), req.getVolumeMl());
            if (affected == 0) {
                throw new BusinessException("该酒款余量不足");
            }

            // 创建订单（后端计价）—— 锁内只做最小必要操作：容量扣减 + 订单落库
            order = new OrderMain();
            order.setOrderNo(generateOrderNo());
            order.setUserId(userId);
            order.setBarId(req.getBarId());
            order.setDispenserId(req.getDispenserId());
            order.setSlotNo(req.getSlotNo());
            order.setWineSkuId(req.getWineSkuId());
            order.setVolumeMl(req.getVolumeMl());
            order.setOriginalAmount(menu.getPrice());
            order.setDiscountAmount(BigDecimal.ZERO);
            order.setPaidAmount(menu.getPrice());
            order.setStatus(OrderStatusEnum.PENDING.getCode());
            order.setPayStatus(PayStatusEnum.UNPAID.getCode());
            order.setPayExpireTime(LocalDateTime.now().plusMinutes(payTimeoutMinutes));
            if (bar != null) order.setCusid(bar.getCusid());
            orderMainMapper.insert(order);

            log.info("订单创建成功: orderNo={}, userId={}, slot={}:{}, volume={}ml, remain={}ml",
                    order.getOrderNo(), userId, req.getDispenserId(), req.getSlotNo(), req.getVolumeMl(),
                    slot.getCurrentCapacity() - req.getVolumeMl());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请重试");
        } finally {
            if (locked) redisLock.unlock(lockKey, lockValue);
        }

        // 5. 锁外组装响应 + 写幂等缓存（不影响瓶位并发，移出锁外缩短持锁时间）
        OrderResp resp = toResp(order);
        if (cacheKey != null) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(resp),
                        IDEM_TTL_HOURS, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("幂等缓存写入失败: cacheKey={}", cacheKey, e);
            }
        }
        return resp;
    }

    /**
     * 查询订单详情
     */
    public OrderResp getOrder(String orderNo) {
        OrderMain order = orderMainMapper.selectOne(
                new LambdaQueryWrapper<OrderMain>().eq(OrderMain::getOrderNo, orderNo));
        if (order == null) throw new BusinessException("订单不存在");
        return toResp(order);
    }

    private OrderResp toResp(OrderMain order) {
        OrderResp resp = new OrderResp();
        resp.setOrderNo(order.getOrderNo());
        resp.setStatus(order.getStatus());
        OrderStatusEnum se = OrderStatusEnum.of(order.getStatus());
        resp.setStatusName(se != null ? se.getName() : null);
        resp.setBarId(order.getBarId());
        resp.setDispenserId(order.getDispenserId());
        resp.setSlotNo(order.getSlotNo());
        resp.setWineSkuId(order.getWineSkuId());
        resp.setVolumeMl(order.getVolumeMl());
        resp.setOriginalAmount(order.getOriginalAmount());
        resp.setDiscountAmount(order.getDiscountAmount());
        resp.setPaidAmount(order.getPaidAmount());
        resp.setPayStatus(order.getPayStatus());
        resp.setCreateTime(order.getCreateTime());
        resp.setPayExpireTime(order.getPayExpireTime());

        WineSku sku = wineSkuMapper.selectById(order.getWineSkuId());
        if (sku != null) resp.setWineName(sku.getWineName());
        Bar bar = barMapper.selectById(order.getBarId());
        if (bar != null) resp.setBarName(bar.getBarName());
        return resp;
    }

    private String generateOrderNo() {
        return "ORD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 关闭支付超时的 PENDING 订单，返还预占容量
     * 定时任务每分钟扫描
     */
    @Transactional
    public int closeExpiredOrders() {
        List<OrderMain> expired = orderMainMapper.selectList(
                new LambdaQueryWrapper<OrderMain>()
                        .eq(OrderMain::getStatus, OrderStatusEnum.PENDING.getCode())
                        .lt(OrderMain::getPayExpireTime, LocalDateTime.now()));
        int count = 0;
        for (OrderMain order : expired) {
            order.setStatus(OrderStatusEnum.REFUNDED.getCode());
            order.setRemark("支付超时自动关闭");
            orderMainMapper.updateById(order);
            // 返还预占容量
            try {
                dispenserSlotMapper.restoreCapacity(order.getDispenserId(), order.getSlotNo(), order.getVolumeMl());
            } catch (Exception e) {
                log.warn("返还容量失败: orderNo={}", order.getOrderNo(), e);
            }
            count++;
        }
        if (count > 0) {
            log.info("关闭超时未支付订单: count={}", count);
        }
        return count;
    }

    /**
     * 兜底轮询 PAYING 状态订单
     * 通联异步回调可能延时/丢失，定时扫描 PAYING 超过阈值的订单，主动调 queryOrder 核实真实支付状态。
     * - resultCode=SUCCESS → 订单转 PAID，生成履约单
     * - 其他 → 回退 PENDING，允许用户重新支付
     */
    @Transactional
    public int pollPayingOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(payingPollMinutes);
        List<OrderMain> payingOrders = orderMainMapper.selectList(
                new LambdaQueryWrapper<OrderMain>()
                        .eq(OrderMain::getStatus, OrderStatusEnum.PAYING.getCode())
                        .lt(OrderMain::getCreateTime, threshold));
        if (payingOrders.isEmpty()) {
            return 0;
        }
        log.info("PAYING 兜底轮询: 扫描到{}笔超时订单", payingOrders.size());
        int paidCount = 0;
        int pendingCount = 0;
        for (OrderMain order : payingOrders) {
            try {
                Map<String, String> result = allinpayIntlClient.queryOrder(order.getOrderNo());
                String resultCode = result.get("resultCode");
                if ("SUCCESS".equals(resultCode) || "0000".equals(resultCode)) {
                    // 支付成功：转 PAID + 生成履约单
                    order.setStatus(OrderStatusEnum.PAID.getCode());
                    order.setPayStatus(PayStatusEnum.SUCCESS.getCode());
                    order.setTransactionId(result.getOrDefault("transId", order.getOrderNo()));
                    order.setPayTime(LocalDateTime.now());
                    orderMainMapper.updateById(order);

                    DispenseTicket ticket = new DispenseTicket();
                    ticket.setOrderNo(order.getOrderNo());
                    ticket.setOrderId(order.getId());
                    ticket.setUserId(order.getUserId());
                    ticket.setDispenserId(order.getDispenserId());
                    ticket.setSlotNo(order.getSlotNo());
                    ticket.setTargetMl(order.getVolumeMl());
                    ticket.setStatus(DispenseTicketStatusEnum.READY.getCode());
                    ticket.setCupPresent(false);
                    dispenseTicketMapper.insert(ticket);
                    paidCount++;
                    log.info("兜底轮询确认支付成功: orderNo={}", order.getOrderNo());
                } else {
                    // 未支付：回退 PENDING
                    order.setStatus(OrderStatusEnum.PENDING.getCode());
                    order.setPayStatus(PayStatusEnum.FAILED.getCode());
                    order.setRemark("兜底轮询确认未支付，回退待支付");
                    orderMainMapper.updateById(order);
                    pendingCount++;
                    log.info("兜底轮询确认未支付: orderNo={}, resultCode={}", order.getOrderNo(), resultCode);
                }
            } catch (Exception e) {
                log.warn("兜底轮询查询异常: orderNo={}, msg={}", order.getOrderNo(), e.getMessage());
            }
        }
        log.info("PAYING 兜底轮询完成: 成功{}笔, 回退{}笔", paidCount, pendingCount);
        return paidCount + pendingCount;
    }
}
