package com.wikiagent.infrastructure.task.jvm;

import com.wikiagent.domain.task.ports.LeasePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租约端口 JVM 降级实现（wikiagent.redis.enabled=false，开发默认）。
 * 单机语义：ConcurrentHashMap + 过期时间戳，compute 原子操作保证互斥。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false", matchIfMissing = true)
public class JvmLeasePort implements LeasePort {

    private record LeaseEntry(String workerId, long expireAtMillis) {
    }

    private final ConcurrentHashMap<String, LeaseEntry> leases = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String taskId, String workerId, Duration ttl) {
        long now = System.currentTimeMillis();
        boolean[] acquired = {false};
        leases.compute(taskId, (k, current) -> {
            if (current == null || current.expireAtMillis() < now) {
                acquired[0] = true;
                return new LeaseEntry(workerId, now + ttl.toMillis());
            }
            return current;
        });
        return acquired[0];
    }

    @Override
    public void renew(String taskId, String workerId, Duration ttl) {
        leases.computeIfPresent(taskId, (k, current) ->
                current.workerId().equals(workerId)
                        ? new LeaseEntry(workerId, System.currentTimeMillis() + ttl.toMillis())
                        : current);
    }

    @Override
    public void release(String taskId, String workerId) {
        leases.compute(taskId, (k, current) ->
                (current != null && current.workerId().equals(workerId)) ? null : current);
    }
}
