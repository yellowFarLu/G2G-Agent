package com.wikiagent.application.ragcache;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * E3 embedding 精确缓存：key = "emb:{model}:{sha256(text)}" → float[]（JSON）。
 * <p>
 * Redis 缺席（wikiagent.redis.enabled=false 排除自动装配）或开关关闭时完全 bypass，
 * 所有操作静默放行，不影响主链路。TTL/开关可配：
 * {@code wikiagent.cache.embedding.enabled}（默认 true）、
 * {@code wikiagent.cache.embedding.ttl-seconds}（默认 86400）。
 */
@Service
public class EmbeddingCacheService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheService.class);
    private static final Gson gson = new Gson();

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final long ttlSeconds;

    public EmbeddingCacheService(ObjectProvider<StringRedisTemplate> redis,
                                 @Value("${wikiagent.cache.embedding.enabled:true}") boolean enabled,
                                 @Value("${wikiagent.cache.embedding.ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis == null ? null : redis.getIfAvailable();
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
    }

    /** 缓存是否生效（开关开 + Redis 可用）。 */
    public boolean active() {
        return enabled && redis != null;
    }

    /** 单条查询；未命中/异常返回 null。 */
    public float[] get(String model, String text) {
        if (!active()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(CacheKeys.embeddingKey(model, text));
            return json == null ? null : gson.fromJson(json, float[].class);
        } catch (Exception e) {
            log.warn("embedding 缓存读取失败（bypass）: {}", e.getMessage());
            return null;
        }
    }

    /** 单条写入；异常静默。 */
    public void put(String model, String text, float[] vector) {
        if (!active() || vector == null) {
            return;
        }
        try {
            redis.opsForValue().set(CacheKeys.embeddingKey(model, text),
                    gson.toJson(vector), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("embedding 缓存写入失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 批量 embed：逐条查缓存，未命中的批量调用 embedder 后回填。
     * 任何缓存异常都退化为直接调用 embedder（bypass）。
     */
    public List<float[]> embedAll(String model, List<String> texts,
                                  Function<List<String>, List<float[]>> embedder) {
        if (!active() || texts == null || texts.isEmpty()) {
            return embedder.apply(texts);
        }
        try {
            Map<Integer, String> missingIdx = new LinkedHashMap<>();
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                out.add(null);
            }
            for (int i = 0; i < texts.size(); i++) {
                float[] cached = get(model, texts.get(i));
                if (cached != null) {
                    out.set(i, cached);
                } else {
                    missingIdx.put(i, texts.get(i));
                }
            }
            if (!missingIdx.isEmpty()) {
                List<String> missTexts = new ArrayList<>(missingIdx.values());
                List<float[]> computed = embedder.apply(missTexts);
                int j = 0;
                for (Map.Entry<Integer, String> e : missingIdx.entrySet()) {
                    float[] v = computed.get(j++);
                    out.set(e.getKey(), v);
                    put(model, e.getValue(), v);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("embedding 缓存批处理异常（bypass）: {}", e.getMessage());
            return embedder.apply(texts);
        }
    }

    /** 失效：清空全部 embedding 缓存，返回删除条数。 */
    public long evictAll() {
        if (!active()) {
            return 0;
        }
        try {
            Set<String> keys = redis.keys(CacheKeys.EMB_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return 0;
            }
            Long deleted = redis.delete(keys);
            return deleted == null ? 0 : deleted;
        } catch (Exception e) {
            log.warn("embedding 缓存清空失败: {}", e.getMessage());
            return 0;
        }
    }
}
