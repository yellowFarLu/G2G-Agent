package com.wikiagent.infrastructure.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * v6 §22.5 实施校正：{@link HandoffLock} 的单 JVM 本地降级实现（开发期 / 单机模式）。
 * <p>
 * 当 {@code wikiagent.redis.enabled=false}（开发默认，Redis 自动装配被
 * {@code ConditionalInfraEnvironmentPostProcessor} 排除）时启用，替代
 * {@link RedissonHandoffLock}，保证应用无需 Redis 即可启动并保持 PERO 主循环、
 * TodoStore 双层锁等链路可用。
 * <p>
 * 语义与 {@link RedissonHandoffLock} 对齐：
 * <ul>
 *   <li>{@code tryLock()} 不阻塞等待，获取失败立即抛 {@link LockBusyException}</li>
 *   <li>finally 释放，仅释放当前线程持有的锁</li>
 * </ul>
 * <p>
 * <b>诚实声明</b>（§22.6 单机限制）：本实现只保证单进程内多线程互斥，
 * 不具备跨节点互斥能力；{@code ttl} 参数在进程内无意义（动作结束即释放，
 * 不存在进程崩溃后的锁残留问题）。多实例部署必须设 {@code REDIS_ENABLED=true}
 * 使用 Redisson 实现。§22 中 todo.json 的跨进程互斥在单机开发期由
 * {@code TodoStore} 内层 {@link java.nio.channels.FileLock}（POSIX flock）兜底。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false", matchIfMissing = true)
public class JvmLocalHandoffLock implements HandoffLock {

    private static final Logger log = LoggerFactory.getLogger(JvmLocalHandoffLock.class);

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withLock(String key, Duration ttl, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired = lock.tryLock();
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
