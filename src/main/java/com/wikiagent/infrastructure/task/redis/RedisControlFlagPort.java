package com.wikiagent.infrastructure.task.redis;

import com.wikiagent.application.task.OptimisticControlConflictException;
import com.wikiagent.domain.task.ControlFlag;
import com.wikiagent.domain.task.ports.ControlFlagPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 控制标志端口 Redis 实现（wikiagent.redis.enabled=true）。
 * 键：wikiagent:task:control:{taskId}（Hash：flag/version）；乐观版本校验用 Lua 原子执行。
 * 权威复核仍以 MySQL control_version 为准（worker 每边界双读）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class RedisControlFlagPort implements ControlFlagPort {

    private static final DefaultRedisScript<Long> REQUEST = new DefaultRedisScript<>("""
            local ver = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
            if ver ~= tonumber(ARGV[1]) then
              return -1
            end
            redis.call('HSET', KEYS[1], 'flag', ARGV[2])
            redis.call('HINCRBY', KEYS[1], 'version', 1)
            return tonumber(redis.call('HGET', KEYS[1], 'version'))
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisControlFlagPort(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String taskId) {
        return "wikiagent:task:control:" + taskId;
    }

    private void request(String taskId, int expectedVersion, ControlFlag flag) {
        Long result = redis.execute(REQUEST, List.of(key(taskId)),
                String.valueOf(expectedVersion), flag.name());
        if (result == null || result < 0) {
            throw new OptimisticControlConflictException(
                    "控制版本冲突 taskId=%s expected=%d".formatted(taskId, expectedVersion));
        }
    }

    @Override
    public void requestPause(String taskId, int expectedVersion) {
        request(taskId, expectedVersion, ControlFlag.PAUSE);
    }

    @Override
    public void requestCancel(String taskId, int expectedVersion) {
        request(taskId, expectedVersion, ControlFlag.CANCEL);
    }

    @Override
    public ControlFlag read(String taskId) {
        String flag = (String) redis.opsForHash().get(key(taskId), "flag");
        if (flag == null) {
            return ControlFlag.NONE;
        }
        try {
            return ControlFlag.valueOf(flag);
        } catch (IllegalArgumentException e) {
            return ControlFlag.NONE;
        }
    }

    @Override
    public int currentVersion(String taskId) {
        Object version = redis.opsForHash().get(key(taskId), "version");
        return version == null ? 0 : Integer.parseInt(version.toString());
    }

    @Override
    public void clear(String taskId) {
        redis.delete(key(taskId));
    }
}
