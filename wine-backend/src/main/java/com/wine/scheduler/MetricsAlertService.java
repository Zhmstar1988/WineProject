package com.wine.scheduler;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 连接池指标告警服务
 *
 * <p>定时检查 HikariCP / Redis 连接池指标，超过阈值时输出 warn 日志。
 * 生产环境建议在 Prometheus AlertManager 中配置正式告警规则，
 * 此类作为应用内兜底告警，便于压测时快速发现连接池瓶颈。</p>
 *
 * <p>相关 Micrometer 指标：</p>
 * <ul>
 *   <li>HikariCP: hikaricp.connections.active / idle / pending / max</li>
 *   <li>Redis Lettuce: lettuce.connection.connected</li>
 * </ul>
 */
@Slf4j
@Component
public class MetricsAlertService {

    @Resource
    private MeterRegistry meterRegistry;

    @Value("${wine.alert.enabled:true}")
    private boolean enabled;

    @Value("${wine.alert.hikari-active-ratio-warn:0.80}")
    private double hikariActiveRatioWarn;

    @Value("${wine.alert.hikari-pending-threshold:10}")
    private int hikariPendingThreshold;

    @Value("${wine.alert.redis-active-ratio-warn:0.80}")
    private double redisActiveRatioWarn;

    /**
     * 每 30 秒检查一次连接池指标
     */
    @Scheduled(fixedDelayString = "${wine.alert.check-interval-ms:30000}")
    public void checkConnectionPools() {
        if (!enabled) {
            return;
        }
        try {
            checkHikariPool();
            checkRedisPool();
        } catch (Exception e) {
            log.warn("连接池指标检查异常", e);
        }
    }

    /**
     * 检查 HikariCP 连接池：
     * - 活跃连接占比 >= 阈值 告警
     * - 等待连接的线程数 >= 阈值 告警
     */
    private void checkHikariPool() {
        double active = gaugeValue("hikaricp.connections.active");
        double idle = gaugeValue("hikaricp.connections.idle");
        double pending = gaugeValue("hikaricp.connections.pending");
        double max = gaugeValue("hikaricp.connections.max");

        if (max <= 0) {
            return;
        }

        double activeRatio = active / max;
        if (activeRatio >= hikariActiveRatioWarn) {
            log.warn("[ALERT] HikariCP 活跃连接占比过高: active={}, idle={}, max={}, ratio={}%, pendingThreads={}",
                    (int) active, (int) idle, (int) max, String.format("%.1f", activeRatio * 100), (int) pending);
        }

        if (pending >= hikariPendingThreshold) {
            log.warn("[ALERT] HikariCP 等待连接线程数过多: pending={}, threshold={}",
                    (int) pending, hikariPendingThreshold);
        }
    }

    /**
     * 检查 Redis Lettuce 连接池
     * Lettuce 通过 Micrometer 暴露 lettuce.connection.connected 指标
     */
    private void checkRedisPool() {
        double connected = gaugeValue("lettuce.connection.connected");
        // Lettuce 没有直接的 max 指标，这里用配置值 100 作为参考上限
        double max = 100.0;
        if (connected > 0) {
            double ratio = connected / max;
            if (ratio >= redisActiveRatioWarn) {
                log.warn("[ALERT] Redis 连接数占比过高: connected={}, max={}, ratio={}%",
                        (int) connected, (int) max, String.format("%.1f", ratio * 100));
            }
        }
    }

    /**
     * 从 MeterRegistry 读取 gauge 指标值
     */
    private double gaugeValue(String name) {
        Optional<Meter> meter = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(name))
                .findFirst();
        return meter.map(m -> {
            if (m instanceof io.micrometer.core.instrument.Gauge g) {
                return g.value();
            }
            return 0.0;
        }).orElse(0.0);
    }
}
