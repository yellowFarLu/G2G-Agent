package com.wikiagent.application.ragcache;

import com.google.gson.Gson;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * E3 答案缓存：key = "ans:{sessionId}:{sha256(query)}" → JSON（RetrievalResult + answerText）。
 * <p>
 * 默认关闭（防幻觉）：{@code wikiagent.cache.answer.enabled=false}，
 * 由运维按场景开启。TTL 默认 600 秒。
 */
@Service
public class AnswerCacheService {

    private static final Logger log = LoggerFactory.getLogger(AnswerCacheService.class);
    private static final Gson gson = new Gson();

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final long ttlSeconds;

    public AnswerCacheService(ObjectProvider<StringRedisTemplate> redis,
                              @Value("${wikiagent.cache.answer.enabled:false}") boolean enabled,
                              @Value("${wikiagent.cache.answer.ttl-seconds:600}") long ttlSeconds) {
        this.redis = redis == null ? null : redis.getIfAvailable();
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
    }

    public boolean active() {
        return enabled && redis != null;
    }

    /** 缓存项（JSON 序列化载体）。 */
    public record CacheItem(String context, String answer, int sourceCount) {
    }

    public CacheItem get(String sessionId, String query) {
        if (!active()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(CacheKeys.answerKey(sessionId, query));
            return json == null ? null : gson.fromJson(json, CacheItem.class);
        } catch (Exception e) {
            log.warn("答案缓存读取失败（bypass）: {}", e.getMessage());
            return null;
        }
    }

    public void put(String sessionId, String query, RetrievalService.RetrievalResult retrieval, String answer) {
        if (!active()) {
            return;
        }
        try {
            CacheItem item = new CacheItem(retrieval.context(), answer,
                    retrieval.sources() == null ? 0 : retrieval.sources().size());
            redis.opsForValue().set(CacheKeys.answerKey(sessionId, query),
                    gson.toJson(item), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("答案缓存写入失败（忽略）: {}", e.getMessage());
        }
    }

    /** 按 sessionId 模式清除（用户会话重置时）。 */
    public long evictBySession(String sessionId) {
        if (!active()) {
            return 0;
        }
        try {
            Set<String> keys = redis.keys(CacheKeys.ANS_PREFIX + sessionId + ":*");
            if (keys == null || keys.isEmpty()) {
                return 0;
            }
            Long deleted = redis.delete(keys);
            return deleted == null ? 0 : deleted;
        } catch (Exception e) {
            log.warn("答案缓存按 session 清除失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 按 docId 清除（文档重解析/删除后）。 */
    public long evictByDocId(String docId) {
        if (!active()) {
            return 0;
        }
        try {
            Set<String> keys = redis.keys(CacheKeys.ANS_DOC_PREFIX + docId + ":*");
            if (keys == null || keys.isEmpty()) {
                return 0;
            }
            Long deleted = redis.delete(keys);
            return deleted == null ? 0 : deleted;
        } catch (Exception e) {
            log.warn("答案缓存按 docId 清除失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 清空全部答案缓存。 */
    public long evictAll() {
        if (!active()) {
            return 0;
        }
        try {
            Set<String> keys = redis.keys(CacheKeys.ANS_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return 0;
            }
            Long deleted = redis.delete(keys);
            return deleted == null ? 0 : deleted;
        } catch (Exception e) {
            log.warn("答案缓存清空失败: {}", e.getMessage());
            return 0;
        }
    }
}
