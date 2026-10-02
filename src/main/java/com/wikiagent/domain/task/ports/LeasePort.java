package com.wikiagent.domain.task.ports;

import java.time.Duration;

/**
 * 租约端口：Redis 可用时 Redisson/Redis 实现；缺失时 JVM 实现（单机降级）。
 */
public interface LeasePort {

    boolean tryAcquire(String taskId, String workerId, Duration ttl);

    void renew(String taskId, String workerId, Duration ttl);

    void release(String taskId, String workerId);
}
