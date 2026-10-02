package com.wikiagent.interfaces.admin;

import com.wikiagent.application.ragcache.AnswerCacheService;
import com.wikiagent.application.ragcache.EmbeddingCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺陷15：缓存运维三端点最小鉴权——无 X-Business-Identity 或不含 admin → 403，
 * 含 admin → 200 且缓存服务被调用。J 波统一鉴权落地前的临时门控。
 */
class CacheAdminControllerTest {

    private EmbeddingCacheService embeddingCache;
    private AnswerCacheService answerCache;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        embeddingCache = mock(EmbeddingCacheService.class);
        answerCache = mock(AnswerCacheService.class);
        when(embeddingCache.evictAll()).thenReturn(1L);
        when(answerCache.evictAll()).thenReturn(2L);
        when(answerCache.evictBySession("s1")).thenReturn(3L);
        when(answerCache.evictByDocId("d1")).thenReturn(4L);
        mvc = MockMvcBuilders.standaloneSetup(
                new CacheAdminController(embeddingCache, answerCache)).build();
    }

    @Test
    void 无身份头时三端点全部403且不触碰缓存() throws Exception {
        mvc.perform(post("/api/admin/cache/embedding/clear")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/cache/answer/clear")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/cache/answer/clear-by-doc").param("docId", "d1"))
                .andExpect(status().isForbidden());
        verify(embeddingCache, never()).evictAll();
        verify(answerCache, never()).evictAll();
        verify(answerCache, never()).evictBySession(org.mockito.ArgumentMatchers.anyString());
        verify(answerCache, never()).evictByDocId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 非admin身份403() throws Exception {
        mvc.perform(post("/api/admin/cache/embedding/clear")
                        .header("X-Business-Identity", "user-a"))
                .andExpect(status().isForbidden());
        verify(embeddingCache, never()).evictAll();
    }

    @Test
    void 空白身份403() throws Exception {
        mvc.perform(post("/api/admin/cache/answer/clear")
                        .header("X-Business-Identity", "   "))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin身份三端点200并执行清除() throws Exception {
        mvc.perform(post("/api/admin/cache/embedding/clear")
                        .header("X-Business-Identity", "admin"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/cache/answer/clear")
                        .header("X-Business-Identity", "admin"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/cache/answer/clear").param("sessionId", "s1")
                        .header("X-Business-Identity", "admin"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/cache/answer/clear-by-doc").param("docId", "d1")
                        .header("X-Business-Identity", "role:admin"))
                .andExpect(status().isOk());
        verify(embeddingCache).evictAll();
        verify(answerCache).evictBySession("s1");
        verify(answerCache).evictByDocId("d1");
    }
}
