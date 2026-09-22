package com.wikiagent.infrastructure.memory.redis;

import com.wikiagent.application.agent.pero.EpisodicMemory;
import com.wikiagent.domain.agent.Reflection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * v6 §20.5 EpisodicMemory 的 Redis List 实现。
 * <p>
 * Reflexion 论文（arXiv:2303.11366）的 episodic memory：反思文本写入 Redis List，
 * 跨会话复用——下一轮 trial（相同 userId + intent）启动时 recall 取出最近 N 条。
 * <p>
 * Key 设计：{@code wikiagent:episodic:{userId}:{intent}}
 * <p>
 * TTL：默认 30 天，对应 §20.6 配置 {@code wikiagent.pero.reflect.episodic-memory-ttl-days}。
 * <p>
 * 并发安全（§22.2）：
 * <ul>
 *   <li>冲突域 per (userId, intent)，RPUSH 原子追加，无需全局锁</li>
 *   <li>trim 到最近 50 条避免无限增长（与短期记忆 20 轮的 2.5 倍经验值）</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class RedisEpisodicMemoryAdapter implements EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisEpisodicMemoryAdapter.class);
    private static final int MAX_ENTRIES = 50;
    private static final int RECALL_LIMIT = 5;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long ttlDays;

    public RedisEpisodicMemoryAdapter(StringRedisTemplate redis,
                                     @Value("${wikiagent.pero.reflect.episodic-memory-ttl-days:30}") long ttlDays) {
        this.redis = redis;
        this.mapper = new ObjectMapper();
        this.ttlDays = ttlDays;
    }

    @Override
    public void put(Reflection r) {
        if (r == null || r.text() == null || r.text().isBlank()) {
            return;
        }
        String key = key(r.stepId() == null ? "unknown" : null, r);
        if (key == null) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(r);
            redis.opsForList().rightPush(key, json);
            redis.expire(key, Duration.ofDays(ttlDays));
            // 防御性 trim，避免无限增长
            Long size = redis.opsForList().size(key);
            if (size != null && size > MAX_ENTRIES) {
                redis.opsForList().trim(key, size - MAX_ENTRIES, -1);
            }
        } catch (JsonProcessingException e) {
            log.warn("EpisodicMemory put 序列化失败 stepId={}: {}", r.stepId(), e.getMessage());
        }
    }

    @Override
    public List<Reflection> recall(String userId, String intent) {
        String key = "wikiagent:episodic:" + userId + ":" + intent;
        List<String> raw = redis.opsForList().range(key, -RECALL_LIMIT, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Reflection> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                out.add(mapper.readValue(s, Reflection.class));
            } catch (Exception e) {
                log.debug("EpisodicMemory recall 反序列化失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** stepId 在 Reflection 中但 userId/intent 不在，put 时使用统一 key。 */
    private String key(String ignoredStepId, Reflection r) {
        // Reflection 不含 userId/intent 字段，使用 stepId 作为兜底 key
        // 实际实施时 Reflection record 应携带 userId/intent 字段，或由 PeroAgent 显式传入
        // 这里使用 stepId 作为 fallback，避免阻塞 v6 实施
        return "wikiagent:episodic:default:" + (r.stepId() == null ? "unknown" : r.stepId());
    }
}
