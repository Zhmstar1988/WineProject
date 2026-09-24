package com.wine.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁
 * 用于在机容量并发互斥，防止超卖打穿
 * 基于 SETNX + 过期时间实现
 */
@Slf4j
@Component
public class RedisDistributedLock {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_PREFIX = "wine:lock:";
    private static final long DEFAULT_EXPIRE = 10;

    /**
     * 尝试获取锁
     * @param key 锁标识（如 slot:{slotId} 或 user:{userId}）
     * @param value 锁持有者标识（如 requestId/orderNo）
     * @param expireSeconds 过期时间秒
     * @return 是否获取成功
     */
    public boolean tryLock(String key, String value, long expireSeconds) {
        String lockKey = LOCK_PREFIX + key;
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, value, expireSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    public boolean tryLock(String key, String value) {
        return tryLock(key, value, DEFAULT_EXPIRE);
    }

    /**
     * 释放锁（Lua 脚本保证原子性：仅当 value 匹配时才删除）
     */
    public boolean unlock(String key, String value) {
        String lockKey = LOCK_PREFIX + key;
        String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "  return redis.call('del', KEYS[1]) " +
                "else " +
                "  return 0 " +
                "end";
        try {
            Long result = stringRedisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                    java.util.Collections.singletonList(lockKey),
                    value
            );
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            log.error("释放锁异常: key={}", lockKey, e);
            return false;
        }
    }

    /**
     * 带超时的锁（自旋等待）
     */
    public boolean lockWithTimeout(String key, String value, long waitMs, long expireSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (tryLock(key, value, expireSeconds)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
