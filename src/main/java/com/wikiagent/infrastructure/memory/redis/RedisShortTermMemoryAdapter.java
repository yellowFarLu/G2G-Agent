package com.wikiagent.infrastructure.memory.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.memory.ShortTermMemoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * v1-v2 §3 短期记忆端口 - Redis List 实现适配器。
 * <p>
 * Key 设计：{@code wikiagent:memory:{userId}:{sessionId}}
 * <p>
 * 以 JSON 串行化 MessageEntry 推入 Redis List（RPUSH 原子追加），
 * 超过 max-turns 时通过 LTRIM 滚动保留最新 N 条（LRU）；
 * TTL 24h（{@code wikiagent.memory.short-term-ttl-hours}）。
 * <p>
 * Redis 不可用时优雅降级：save 失败仅告警不抛，load 返回空列表，
 * 由上层调用方自行处理记忆缺失场景（不阻断对话链路）。
 * <p>
 * 注：与 v6 §20.5 RedisEpisodicMemoryAdapter（不同 key 前缀 + 不同端口）不冲突。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class RedisShortTermMemoryAdapter implements ShortTermMemoryPort {

    private static final Logger log = LoggerFactory.getLogger(RedisShortTermMemoryAdapter.class);

    private static final String KEY_PREFIX = "wikiagent:memory:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final int maxTurns;
    private final long ttlHours;

    public RedisShortTermMemoryAdapter(StringRedisTemplate redis,
                                       @Value("${wikiagent.memory.short-term-max-turns:20}") int maxTurns,
                                       @Value("${wikiagent.memory.short-term-ttl-hours:24}") long ttlHours) {
        this.redis = redis;
        this.mapper = new ObjectMapper();
        this.maxTurns = Math.max(1, maxTurns);
        this.ttlHours = Math.max(1, ttlHours);
    }

    @Override
    public void save(String userId, String sessionId, String role, String content) {
        if (userId == null || sessionId == null || role == null) {
            return;
        }
        String key = key(userId, sessionId);
        MessageEntry entry = new MessageEntry(role, content == null ? "" : content, System.currentTimeMillis());
        try {
            String json = mapper.writeValueAsString(entry);
            redis.opsForList().rightPush(key, json);
            redis.expire(key, Duration.ofHours(ttlHours));
            // 滚动保留最新 maxTurns 条（LRU）
            Long size = redis.opsForList().size(key);
            if (size != null && size > maxTurns) {
                redis.opsForList().trim(key, size - maxTurns, -1);
            }
        } catch (JsonProcessingException e) {
            log.warn("短期记忆序列化失败 userId={} sessionId={}: {}", userId, sessionId, e.getMessage());
        } catch (Exception e) {
            // Redis 不可用时不阻断调用方，仅告警
            log.warn("短期记忆写入 Redis 失败 userId={} sessionId={}: {}", userId, sessionId, e.getMessage());
        }
    }

    @Override
    public List<MessageEntry> load(String userId, String sessionId, int maxTurns) {
        int limit = Math.max(1, Math.min(maxTurns, this.maxTurns));
        String key = key(userId, sessionId);
        try {
            Long size = redis.opsForList().size(key);
            if (size == null || size == 0) {
                return List.of();
            }
            long start = Math.max(0, size - limit);
            List<String> raw = redis.opsForList().range(key, start, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<MessageEntry> out = new ArrayList<>(raw.size());
            for (String s : raw) {
                try {
                    out.add(mapper.readValue(s, MessageEntry.class));
                } catch (Exception e) {
                    log.debug("短期记忆反序列化失败 key={}: {}", key, e.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("短期记忆读取 Redis 失败 userId={} sessionId={}: {}", userId, sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<MessageEntry> loadAll(String userId, String sessionId) {
        return load(userId, sessionId, this.maxTurns);
    }

    @Override
    public void clear(String userId, String sessionId) {
        String key = key(userId, sessionId);
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("短期记忆清除 Redis 失败 userId={} sessionId={}: {}", userId, sessionId, e.getMessage());
        }
    }

    private static String key(String userId, String sessionId) {
        return KEY_PREFIX + userId + ":" + sessionId;
    }
}
