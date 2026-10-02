package com.wikiagent.application.ragcache;

import com.wikiagent.service.retrieve.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E3 缓存测试：命中/未命中/bypass（开关关闭、Redis 缺席）/失效；
 * 缺陷16：ansdoc 反向索引 SET 与按 docId 精确清除。
 */
class RagCacheTest {

    /** 用内存 map 模拟 StringRedisTemplate 的 ValueOperations / SetOperations。 */
    private static final class FakeRedis {
        final Map<String, String> store = new HashMap<>();
        final Map<String, Set<String>> sets = new HashMap<>();
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> ops = mock(ValueOperations.class);
        final SetOperations<String, String> setOps = mock(SetOperations.class);

        FakeRedis() {
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
            doAnswer(inv -> {
                store.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(ops).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
            when(redis.opsForSet()).thenReturn(setOps);
            when(setOps.add(anyString(), any(String[].class))).thenAnswer(inv -> {
                // Mockito varargs：单成员调用时第 2 个位置参数是 String 而非 String[]，
                // 统一取首参之后的全部位置参数作为成员
                Object[] all = inv.getArguments();
                String[] members = java.util.Arrays.copyOfRange(all, 1, all.length, String[].class);
                sets.computeIfAbsent(all[0].toString(), k -> new HashSet<>())
                        .addAll(java.util.Arrays.asList(members));
                return (long) members.length;
            });
            when(setOps.members(anyString())).thenAnswer(inv -> {
                Set<String> m = sets.get(inv.getArgument(0));
                return m == null ? Set.of() : new HashSet<>(m);
            });
            when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(redis.keys(anyString())).thenAnswer(inv -> {
                // 最小 glob：* → .*，其余字符按字面前缀匹配
                String pattern = ((String) inv.getArgument(0))
                        .replace(".", "\\.").replace("*", ".*");
                Set<String> hit = new HashSet<>();
                java.util.regex.Pattern rx = java.util.regex.Pattern.compile(pattern);
                for (String k : store.keySet()) {
                    if (rx.matcher(k).matches()) hit.add(k);
                }
                for (String k : sets.keySet()) {
                    if (rx.matcher(k).matches()) hit.add(k);
                }
                return hit;
            });
            // delete(Collection) 是唯一批量重载（Set/List 均绑定它）
            when(redis.delete(any(Collection.class))).thenAnswer(inv -> {
                Collection<String> keys = inv.getArgument(0);
                long n = 0;
                for (String k : keys) {
                    if (store.remove(k) != null) {
                        n++;
                    }
                    sets.remove(k);
                }
                return n;
            });
        }
    }

    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    private static RetrievalService.RetrievalResult result(String docId) {
        return new RetrievalService.RetrievalResult(
                List.of(new RetrievalService.Source(1, docId, 1, 3, "片段", null, 0.9, "f.md")),
                "上下文");
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
        assertNull(ans.get("anon", "all", "s1", "q"));
        assertEquals(0, ans.evictAll());
    }

    @Test
    void answer缓存命中失效与session清除() {
        FakeRedis fake = new FakeRedis();
        AnswerCacheService svc = new AnswerCacheService(providerOf(fake.redis), true, 60);

        svc.put("anon", "all", "s1", "问题", result("d1"), "答案");
        AnswerCacheService.CacheItem hit = svc.get("anon", "all", "s1", "问题");
        assertNotNull(hit);
        assertEquals("答案", hit.answer());
        assertEquals(1, hit.sourceCount());

        assertEquals(1, svc.evictBySession("s1"));
        assertNull(svc.get("anon", "all", "s1", "问题"));
    }

    @Test
    void answer缓存默认关闭不生效() {
        FakeRedis fake = new FakeRedis();
        AnswerCacheService svc = new AnswerCacheService(providerOf(fake.redis), false, 60);
        assertFalse(svc.active());
        RetrievalService.RetrievalResult rr = new RetrievalService.RetrievalResult(List.of(), "");
        svc.put("anon", "all", "s1", "q", rr, "a");
        assertNull(svc.get("anon", "all", "s1", "q"));
    }

    @Test
    void 不同身份或session不串缓存() {
        FakeRedis fake = new FakeRedis();
        AnswerCacheService svc = new AnswerCacheService(providerOf(fake.redis), true, 60);
        svc.put("anon", "all", "s1", "q", result("d1"), "A");
        assertNotNull(svc.get("anon", "all", "s1", "q"));
        assertNull(svc.get("anon", "all", "s2", "q"), "不同 session 不串");
        assertNull(svc.get("admin", "all", "s1", "q"), "不同身份不串");
    }

    @Test
    void 按docId反向索引清除跨session的引用答案() {
        FakeRedis fake = new FakeRedis();
        AnswerCacheService svc = new AnswerCacheService(providerOf(fake.redis), true, 60);
        // 两个不同 session 的答案引用同一文档 d1
        svc.put("anon", "all", "s1", "问题", result("d1"), "答案一");
        svc.put("anon", "all", "s2", "问题", result("d1"), "答案二");
        assertNotNull(svc.get("anon", "all", "s1", "问题"));
        assertNotNull(svc.get("anon", "all", "s2", "问题"));

        long n = svc.evictByDocId("d1");
        assertEquals(2, n, "应通过 ansdoc SET 精确删掉两个答案键");
        assertNull(svc.get("anon", "all", "s1", "问题"));
        assertNull(svc.get("anon", "all", "s2", "问题"));
        // 索引 SET 自身也被删除
        assertFalse(fake.sets.containsKey(CacheKeys.answerDocKey("d1")));
    }

    @Test
    void 缓存key格式符合约定() {
        assertTrue(CacheKeys.embeddingKey("m", "t").startsWith("emb:m:"));
        assertTrue(CacheKeys.answerKey("anon", "all", "s", "q").startsWith("ans:anon:all:s:"));
        assertTrue(CacheKeys.answerDocKey("d").startsWith("ansdoc:d"));
    }
}
