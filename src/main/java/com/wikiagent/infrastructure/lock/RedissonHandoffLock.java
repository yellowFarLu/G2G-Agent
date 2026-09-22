package com.wikiagent.infrastructure.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * v6 §22.5 HandoffLock 的 Redisson 实现（§22.3 #1 Redis 分布式锁 Redlock）。
 * <p>
 * Redisson RLock 内部：
 * <ul>
 *   <li>SET NX PX 加锁（Redis 单节点 Lua 原子脚本）</li>
 *   <li>token 防误删（释放锁时校验线程持有者）</li>
 *   <li>watchdog 自动续期（默认 30s，可禁用）</li>
 * </ul>
 * 与 §22.5 骨架完全一致（{@code tryLock(0, ttl, MILLISECONDS)}：0=不阻塞等待，获取失败立即返回 false）。
 * <p>
 * 开关：{@code wikiagent.concurrency.lock.provider=none} 时禁用（v3-v5 实施时切换为 {@code NoOpHandoffLock}）。
 * <p>
 * 来源：https://redis.io/docs/manual/patterns/distributed-locks/ + Redisson 文档。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.concurrency.lock.provider",
        havingValue = "redisson", matchIfMissing = true)
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class RedissonHandoffLock implements HandoffLock {

    private static final Logger log = LoggerFactory.getLogger(RedissonHandoffLock.class);

    private static final String LOCK_PREFIX = "lock:";

    private final RedissonClient redisson;

    public RedissonHandoffLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public <T> T withLock(String key, Duration ttl, Supplier<T> action) {
        RLock lock = redisson.getLock(LOCK_PREFIX + key);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockBusyException(key, e);
        }
        if (!acquired) {
            throw new LockBusyException(key);
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
