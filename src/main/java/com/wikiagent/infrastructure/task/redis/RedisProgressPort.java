package com.wikiagent.infrastructure.task.redis;

import com.wikiagent.domain.task.ports.ProgressPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 进度端口 Redis 实现（wikiagent.redis.enabled=true）。
 * 键：wikiagent:task:progress:{taskId}（Hash：percent/currentStep），TTL 2h。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class RedisProgressPort implements ProgressPort {

    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    public RedisProgressPort(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String taskId) {
        return "wikiagent:task:progress:" + taskId;
    }

    @Override
    public void publish(String taskId, int percent, String currentStep) {
        String key = key(taskId);
        redis.opsForHash().putAll(key, Map.of(
                "percent", String.valueOf(percent),
                "currentStep", currentStep == null ? "" : currentStep));
        redis.expire(key, TTL);
    }
}
