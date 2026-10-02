package com.wikiagent.interfaces.admin;

import com.wikiagent.application.ragcache.AnswerCacheService;
import com.wikiagent.application.ragcache.EmbeddingCacheService;
import com.wikiagent.infrastructure.security.RetrievalIdentityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
 * <p>
 * 缺陷15：三端点最小鉴权——X-Business-Identity 缺失或不含 "admin" 一律 403。
 * 注意：这只是 J 波统一鉴权落地前的临时门控（TODO J波：替换为统一权限框架/角色校验）。
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

    /**
     * 缺陷15 临时门控：身份头缺失/空白，或身份中不含 admin 角色标记 → 403。
     * TODO J波统一鉴权：删除本方法，改由统一安全过滤器/注解（如 @PreAuthorize）接管。
     */
    private ResponseEntity<Map<String, Object>> rejectIfNotAdmin(String identity) {
        if (identity == null || identity.isBlank() || !identity.contains("admin")) {
            log.warn("缓存运维接口拒绝非 admin 身份: identity={}", identity);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "forbidden");
            body.put("message", "需要 admin 身份");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        return null;
    }

    @PostMapping("/embedding/clear")
    public ResponseEntity<Map<String, Object>> clearEmbedding(
            @RequestHeader(value = RetrievalIdentityFilter.HEADER, required = false) String identity) {
        ResponseEntity<Map<String, Object>> forbidden = rejectIfNotAdmin(identity);
        if (forbidden != null) {
            return forbidden;
        }
        long n = embeddingCache.evictAll();
        log.info("admin 清空 embedding 缓存 {} 条", n);
        return ResponseEntity.ok(ok("embedding", n));
    }

    @PostMapping("/answer/clear")
    public ResponseEntity<Map<String, Object>> clearAnswer(
            @RequestHeader(value = RetrievalIdentityFilter.HEADER, required = false) String identity,
            @RequestParam(required = false) String sessionId) {
        ResponseEntity<Map<String, Object>> forbidden = rejectIfNotAdmin(identity);
        if (forbidden != null) {
            return forbidden;
        }
        long n = sessionId == null || sessionId.isBlank()
                ? answerCache.evictAll() : answerCache.evictBySession(sessionId);
        log.info("admin 清空 answer 缓存 {} 条 (sessionId={})", n, sessionId);
        return ResponseEntity.ok(ok("answer", n));
    }

    @PostMapping("/answer/clear-by-doc")
    public ResponseEntity<Map<String, Object>> clearAnswerByDoc(
            @RequestHeader(value = RetrievalIdentityFilter.HEADER, required = false) String identity,
            @RequestParam String docId) {
        ResponseEntity<Map<String, Object>> forbidden = rejectIfNotAdmin(identity);
        if (forbidden != null) {
            return forbidden;
        }
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
