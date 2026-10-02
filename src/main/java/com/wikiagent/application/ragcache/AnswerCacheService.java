package com.wikiagent.application.ragcache;

import com.google.gson.Gson;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * E3 答案缓存：key = "ans:{identity}:{domain}:{sessionId}:{sha256(query)}" → JSON
 * （RetrievalResult 摘要 + answerText）。
 * <p>
 * 反向索引：写入时按答案引用的每个 docId 维护 Redis SET "ansdoc:{docId}"（成员为答案 key），
 * 文档重解析/删除后 {@link #evictByDocId(String)} 直接遍历 SET 精确删除，
 * 不再使用 KEYS 模式扫描。SET 本身按答案 TTL 续期；已过期成员删除为 no-op。
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

    public CacheItem get(String identity, String domain, String sessionId, String query) {
        if (!active()) {
            return null;
        }
        try {
            String json = redis.opsForValue()
                    .get(CacheKeys.answerKey(identity, domain, sessionId, query));
            return json == null ? null : gson.fromJson(json, CacheItem.class);
        } catch (Exception e) {
            log.warn("答案缓存读取失败（bypass）: {}", e.getMessage());
            return null;
        }
    }

    public void put(String identity, String domain, String sessionId, String query,
                    RetrievalService.RetrievalResult retrieval, String answer) {
        if (!active() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            String answerK = CacheKeys.answerKey(identity, domain, sessionId, query);
            CacheItem item = new CacheItem(retrieval.context(), answer,
                    retrieval.sources() == null ? 0 : retrieval.sources().size());
            redis.opsForValue().set(answerK, gson.toJson(item), ttlSeconds, TimeUnit.SECONDS);
            indexByDocIds(answerK, retrieval);
        } catch (Exception e) {
            log.warn("答案缓存写入失败（忽略）: {}", e.getMessage());
        }
    }

    /** 反向索引：ansdoc:{docId} SET 收录该答案 key，TTL 与答案键一致（每次写入续期）。 */
    private void indexByDocIds(String answerKey, RetrievalService.RetrievalResult retrieval) {
        if (retrieval.sources() == null || retrieval.sources().isEmpty()) {
            return;
        }
        Set<String> docIds = new LinkedHashSet<>();
        for (RetrievalService.Source s : retrieval.sources()) {
            if (s.docId() != null && !s.docId().isBlank()) {
                docIds.add(s.docId());
            }
        }
        for (String docId : docIds) {
            String setKey = CacheKeys.answerDocKey(docId);
            redis.opsForSet().add(setKey, answerKey);
            redis.expire(setKey, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    /** 按 sessionId 清除（用户会话重置时；段序 ans:identity:domain:session:hash）。 */
    public long evictBySession(String sessionId) {
        if (!active()) {
            return 0;
        }
        try {
            String seg = sessionId == null ? "-" : sessionId.replace(":", "_");
            Set<String> keys = redis.keys(ANS_PREFIX_GLOB + "*:*:" + seg + ":*");
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

    private static final String ANS_PREFIX_GLOB = "ans:";

    /**
     * 按 docId 清除（文档重解析/删除后）：走 ansdoc:{docId} 反向索引 SET，
     * 精确删除引用该文档的全部答案键，不做 KEYS 模式扫描。
     */
    public long evictByDocId(String docId) {
        if (!active()) {
            return 0;
        }
        String setKey = CacheKeys.answerDocKey(docId);
        try {
            Set<String> answerKeys = redis.opsForSet().members(setKey);
            long n = 0;
            if (answerKeys != null && !answerKeys.isEmpty()) {
                Long deleted = redis.delete(answerKeys);
                n += deleted == null ? 0 : deleted;
            }
            // 索引自身删除（返回值不计入业务清除数）
            redis.delete(List.of(setKey));
            return n;
        } catch (Exception e) {
            log.warn("答案缓存按 docId 清除失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 清空全部答案缓存（ansdoc 反向索引一并清除）。 */
    public long evictAll() {
        if (!active()) {
            return 0;
        }
        try {
            Set<String> keys = redis.keys(ANS_PREFIX_GLOB + "*");
            long n = 0;
            if (keys != null && !keys.isEmpty()) {
                Long deleted = redis.delete(keys);
                n += deleted == null ? 0 : deleted;
            }
            Set<String> docSets = redis.keys(CacheKeys.ANS_DOC_PREFIX + "*");
            if (docSets != null && !docSets.isEmpty()) {
                redis.delete(docSets);
            }
            return n;
        } catch (Exception e) {
            log.warn("答案缓存清空失败: {}", e.getMessage());
            return 0;
        }
    }
}
