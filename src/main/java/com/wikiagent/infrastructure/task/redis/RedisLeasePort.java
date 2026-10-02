package com.wikiagent.infrastructure.task.redis;

import com.wikiagent.domain.task.ports.LeasePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 租约端口 Redis 实现（wikiagent.redis.enabled=true）。
 * 键：wikiagent:task:lease:{taskId}，值=workerId，TTL 即租约时长；
 * renew/release 用 Lua 校验 owner，避免误释放他人租约。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class RedisLeasePort implements LeasePort {

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisLeasePort(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String taskId) {
        return "wikiagent:task:lease:" + taskId;
    }

    @Override
    public boolean tryAcquire(String taskId, String workerId, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(key(taskId), workerId, ttl);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void renew(String taskId, String workerId, Duration ttl) {
        redis.execute(RENEW, List.of(key(taskId)), workerId, String.valueOf(ttl.toMillis()));
    }

    @Override
    public void release(String taskId, String workerId) {
        redis.execute(RELEASE, List.of(key(taskId)), workerId);
    }
}
