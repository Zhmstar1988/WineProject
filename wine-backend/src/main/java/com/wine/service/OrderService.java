package com.wine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wine.common.BusinessException;
import com.wine.common.RedisDistributedLock;
import com.wine.domain.*;
import com.wine.dto.CreateOrderReq;
import com.wine.dto.OrderResp;
import com.wine.enums.OrderStatusEnum;
import com.wine.enums.PayStatusEnum;
import com.wine.mapper.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

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

    @Value("${wine.order.pay-timeout-minutes:15}")
    private int payTimeoutMinutes;

    @Value("${wine.lock.in-machine-prefix:wine:lock:slot:}")
    private String slotLockPrefix;

    /**
     * 下单
     * 1. 校验年龄合规
     * 2. Redis 锁瓶位，检查在机余量
     * 3. 后端统一计价（不信任端侧金额）
     * 4. 创建 PENDING 订单，设置支付超时
     */
    @Transactional
    public OrderResp createOrder(Long userId, CreateOrderReq req) {
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

        // 3. Redis 分布式锁锁定瓶位，检查在机余量防超卖
        String lockKey = slotLockPrefix + req.getDispenserId() + ":" + req.getSlotNo();
        String lockValue = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = redisLock.lockWithTimeout(lockKey, lockValue, 3000, 15);
            if (!locked) throw new BusinessException("当前瓶位繁忙，请稍后重试");

            DispenserSlot slot = dispenserSlotMapper.selectOne(
                    new LambdaQueryWrapper<DispenserSlot>()
                            .eq(DispenserSlot::getDispenserId, req.getDispenserId())
                            .eq(DispenserSlot::getSlotNo, req.getSlotNo()));
            if (slot == null) throw new BusinessException("瓶位不存在");
            if (slot.getCurrentCapacity() < req.getVolumeMl()) {
                throw new BusinessException("该酒款余量不足");
            }

            // 4. 创建订单（后端计价）
            OrderMain order = new OrderMain();
            order.setOrderNo(generateOrderNo());
            order.setUserId(userId);
            order.setBarId(req.getBarId());
            order.setDispenserId(req.getDispenserId());
            order.setSlotNo(req.getSlotNo());
            order.setWineSkuId(req.getWineSkuId());
            order.setVolumeMl(req.getVolumeMl());
            order.setOriginalAmount(menu.getPrice());
            order.setDiscountAmount(BigDecimal.ZERO);
            order.setPaidAmount(menu.getPrice()); // paid = original - discount
            order.setStatus(OrderStatusEnum.PENDING.getCode());
            order.setPayStatus(PayStatusEnum.UNPAID.getCode());
            order.setPayExpireTime(LocalDateTime.now().plusMinutes(payTimeoutMinutes));

            Bar bar = barMapper.selectById(req.getBarId());
            if (bar != null) order.setCusid(bar.getCusid());

            orderMainMapper.insert(order);
            log.info("订单创建成功: orderNo={}, userId={}, slot={}:{}, volume={}ml",
                    order.getOrderNo(), userId, req.getDispenserId(), req.getSlotNo(), req.getVolumeMl());

            return toResp(order);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请重试");
        } finally {
            if (locked) redisLock.unlock(lockKey, lockValue);
        }
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
                + String.format("%06d", UUID.randomUUID().toString().hashCode() % 1000000);
    }
}
