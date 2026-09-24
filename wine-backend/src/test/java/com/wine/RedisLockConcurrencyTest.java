package com.wine;

import com.wine.common.RedisDistributedLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 分布式锁并发测试
 * 验证在机容量互斥：同一瓶位同一时刻只有一个请求能获取锁
 */
@SpringBootTest
public class RedisLockConcurrencyTest {

    @Autowired
    private RedisDistributedLock redisLock;

    @Test
    public void testLockMutualExclusion() throws InterruptedException {
        String key = "test:slot:1";
        String value1 = "req-1";
        String value2 = "req-2";

        // 第一个请求获取锁
        boolean locked1 = redisLock.tryLock(key, value1, 5);
        assertTrue(locked1, "第一个请求应获取锁成功");

        // 第二个请求应获取失败（互斥）
        boolean locked2 = redisLock.tryLock(key, value2, 5);
        assertFalse(locked2, "第二个请求应获取锁失败");

        // 释放锁后第二个请求才能获取
        redisLock.unlock(key, value1);
        boolean locked3 = redisLock.tryLock(key, value2, 5);
        assertTrue(locked3, "释放后第二个请求应获取锁成功");
        redisLock.unlock(key, value2);
    }

    @Test
    public void testLockOnlyReleasedByOwner() {
        String key = "test:slot:2";
        String owner = "owner-1";
        String thief = "thief-1";

        redisLock.tryLock(key, owner, 5);
        // 非持有者无法释放
        boolean releasedByThief = redisLock.unlock(key, thief);
        assertFalse(releasedByThief, "非持有者不能释放锁");

        // 锁仍然被持有
        boolean lockedByOther = redisLock.tryLock(key, "other", 5);
        assertFalse(lockedByOther, "锁仍被原持有者持有");

        redisLock.unlock(key, owner);
    }

    @Test
    public void testConcurrentLockAcquisition() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        String key = "test:slot:concurrent";

        for (int i = 0; i < threadCount; i++) {
            final String value = "thread-" + i;
            new Thread(() -> {
                try {
                    if (redisLock.lockWithTimeout(key, value, 2000, 3)) {
                        successCount.incrementAndGet();
                        Thread.sleep(100);
                        redisLock.unlock(key, value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();
        // 由于锁是互斥的，每个线程依次获取，所有线程都应成功
        assertEquals(threadCount, successCount.get(), "所有线程应依次获取锁");
    }
}
