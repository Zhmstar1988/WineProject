package com.wine.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查端点（Docker/K8s 探针用）
 * GET /api/health — 返回服务+依赖组件状态
 */
@RestController
public class HealthController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> components = new LinkedHashMap<>();

        // 检查数据库
        try {
            jdbcTemplate.queryForObject("SELECT 1", String.class);
            components.put("mysql", "UP");
        } catch (Exception e) {
            components.put("mysql", "DOWN: " + e.getMessage());
        }

        // 检查 Redis
        try {
            if (redisConnectionFactory != null) {
                redisConnectionFactory.getConnection().ping();
                components.put("redis", "UP");
            } else {
                components.put("redis", "NOT_CONFIGURED");
            }
        } catch (Exception e) {
            components.put("redis", "DOWN: " + e.getMessage());
        }

        // 整体状态
        boolean allUp = components.values().stream()
                .allMatch(v -> v.equals("UP") || v.equals("NOT_CONFIGURED"));
        result.put("status", allUp ? "UP" : "DOWN");
        result.put("components", components);
        return result;
    }
}
