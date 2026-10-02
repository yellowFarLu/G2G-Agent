package com.wikiagent.application.ragcache;

import com.wikiagent.service.retrieve.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E3 缓存测试：命中/未命中/bypass（开关关闭、Redis 缺席）/失效。
 */
class RagCacheTest {

    /** 用内存 map 模拟 StringRedisTemplate 的 ValueOperations。 */
    private static final class FakeRedis {
        final Map<String, String> store = new HashMap<>();
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> ops = mock(ValueOperations.class);

        FakeRedis() {
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
            doAnswer(inv -> {
                store.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(ops).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            when(redis.keys(anyString())).thenAnswer(inv -> {
                String pattern = ((String) inv.getArgument(0)).replace("*", "");
                Set<String> hit = new java.util.HashSet<>();
                for (String k : store.keySet()) {
                    if (k.startsWith(pattern)) hit.add(k);
                }
                return hit;
            });
            when(redis.delete(any(Set.class))).thenAnswer(inv -> {
                Set<String> keys = inv.getArgument(0);
                keys.forEach(store::remove);
                return (long) keys.size();
            });
        }
    }

    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    @Test
    void embedding缓存未命中后回填再命中() {
        FakeRedis fake = new FakeRedis();
        EmbeddingCacheService svc = new EmbeddingCacheService(providerOf(fake.redis), true, 60);
        assertTrue(svc.active());

        float[] v = new float[]{1.0f, 2.0f};
        assertNull(svc.get("m1", "文本"));
        svc.put("m1", "文本", v);
        assertArrayEquals(v, svc.get("m1", "文本"));
    }

    @Test
    void embedding批量缓存只计算未命中部分() {
        FakeRedis fake = new FakeRedis();
        EmbeddingCacheService svc = new EmbeddingCacheService(providerOf(fake.redis), true, 60);
        svc.put("m1", "a", new float[]{1f});

        List<String> calls = new java.util.ArrayList<>();
        List<float[]> out = svc.embedAll("m1", List.of("a", "b"), texts -> {
            calls.addAll(texts);
            return texts.stream().map(t -> new float[]{9f}).toList();
        });
        assertEquals(List.of("b"), calls);            // a 命中缓存，只有 b 走 embedder
        assertArrayEquals(new float[]{1f}, out.get(0));
        assertArrayEquals(new float[]{9f}, out.get(1));
    }

    @Test
    void 开关关闭时embedding缓存bypass() {
        FakeRedis fake = new FakeRedis();
        EmbeddingCacheService svc = new EmbeddingCacheService(providerOf(fake.redis), false, 60);
        assertFalse(svc.active());
        assertNull(svc.get("m1", "文本"));
        svc.put("m1", "文本", new float[]{1f});
        assertNull(svc.get("m1", "文本"));
    }

    @Test
    void redis缺席时bypass() {
        ObjectProvider<StringRedisTemplate> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(null);
        EmbeddingCacheService emb = new EmbeddingCacheService(op, true, 60);
        assertFalse(emb.active());
        assertNull(emb.get("m1", "t"));
        assertEquals(0, emb.evictAll());

        AnswerCacheService ans = new AnswerCacheService(op, true, 60);
        assertFalse(ans.active());
        assertNull(ans.get("s1", "q"));
        assertEquals(0, ans.evictAll());
    }

    @Test
    void answer缓存命中失效与session清除() {
        FakeRedis fake = new FakeRedis();
        AnswerCacheService svc = new AnswerCacheService(providerOf(fake.redis), true, 60);

        RetrievalService.RetrievalResult rr = new RetrievalService.RetrievalResult(
                List.of(new RetrievalService.Source(1, "d1", 1, 3, "片段", null, 0.9, "f.md")), "上下文");
        svc.put("s1", "问题", rr, "答案");
        AnswerCacheService.CacheItem hit = svc.get("s1", "问题");
        assertNotNull(hit);
        assertEquals("答案", hit.answer());
        assertEquals(1, hit.sourceCount());

        assertEquals(1, svc.evictBySession("s1"));
        assertNull(svc.get("s1", "问题"));
    }

    @Test
    void answer缓存默认关闭不生效() {
        FakeRedis fake = new FakeRedis();
        AnswerCacheService svc = new AnswerCacheService(providerOf(fake.redis), false, 60);
        assertFalse(svc.active());
        RetrievalService.RetrievalResult rr = new RetrievalService.RetrievalResult(List.of(), "");
        svc.put("s1", "q", rr, "a");
        assertNull(svc.get("s1", "q"));
    }

    @Test
    void 缓存key格式符合约定() {
        assertTrue(CacheKeys.embeddingKey("m", "t").startsWith("emb:m:"));
        assertTrue(CacheKeys.answerKey("s", "q").startsWith("ans:s:"));
        assertTrue(CacheKeys.answerDocKey("d").startsWith("ansdoc:d"));
    }
}
