package com.wikiagent.interfaces.admin;

import com.wikiagent.application.ragcache.AnswerCacheService;
import com.wikiagent.application.ragcache.EmbeddingCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E3 缓存运维接口：按需清 embedding / answer 缓存。
 * <p>
 * POST /api/admin/cache/embedding/clear
 * POST /api/admin/cache/answer/clear?sessionId=xxx（sessionId 可空，空则全清）
 * POST /api/admin/cache/answer/clear-by-doc?docId=xxx
 */
@RestController
@RequestMapping("/api/admin/cache")
public class CacheAdminController {

    private static final Logger log = LoggerFactory.getLogger(CacheAdminController.class);

    private final EmbeddingCacheService embeddingCache;
    private final AnswerCacheService answerCache;

    public CacheAdminController(EmbeddingCacheService embeddingCache, AnswerCacheService answerCache) {
        this.embeddingCache = embeddingCache;
        this.answerCache = answerCache;
    }

    @PostMapping("/embedding/clear")
    public ResponseEntity<Map<String, Object>> clearEmbedding() {
        long n = embeddingCache.evictAll();
        log.info("admin 清空 embedding 缓存 {} 条", n);
        return ResponseEntity.ok(ok("embedding", n));
    }

    @PostMapping("/answer/clear")
    public ResponseEntity<Map<String, Object>> clearAnswer(
            @RequestParam(required = false) String sessionId) {
        long n = sessionId == null || sessionId.isBlank()
                ? answerCache.evictAll() : answerCache.evictBySession(sessionId);
        log.info("admin 清空 answer 缓存 {} 条 (sessionId={})", n, sessionId);
        return ResponseEntity.ok(ok("answer", n));
    }

    @PostMapping("/answer/clear-by-doc")
    public ResponseEntity<Map<String, Object>> clearAnswerByDoc(@RequestParam String docId) {
        long n = answerCache.evictByDocId(docId);
        log.info("admin 按 docId 清空 answer 缓存 {} 条 (docId={})", n, docId);
        return ResponseEntity.ok(ok("answer-doc", n));
    }

    private Map<String, Object> ok(String type, long deleted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("deleted", deleted);
        m.put("embeddingCacheActive", embeddingCache.active());
        m.put("answerCacheActive", answerCache.active());
        return m;
    }
}
