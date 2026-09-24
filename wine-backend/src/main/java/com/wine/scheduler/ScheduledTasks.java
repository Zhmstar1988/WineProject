package com.wine.scheduler;

import com.wine.service.ReconcileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 定时任务调度器
 */
@Slf4j
@Component
public class ScheduledTasks {

    @Resource
    private ReconcileService reconcileService;

    /**
     * 每日凌晨 02:00 执行 T+1 履约三单核对
     * 三向对齐：【主订单】↔【履约单】↔【通联授权状态】
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyReconcile() {
        log.info("===== 定时任务触发：T+1 三向核对跑批 =====");
        try {
            reconcileService.reconcile(LocalDate.now().minusDays(1));
            log.info("===== 定时任务完成：T+1 三向核对跑批 =====");
        } catch (Exception e) {
            log.error("定时任务异常：T+1 三向核对跑批失败", e);
        }
    }

    /**
     * 支付超时订单关闭（每分钟扫描）
     * PENDING 超过 payExpireTime 自动关闭，释放 Redis 锁
     */
    @Scheduled(cron = "0 * * * * ?")
    public void closeExpiredOrders() {
        // TODO: 扫描超时未支付订单并关闭
    }
}
