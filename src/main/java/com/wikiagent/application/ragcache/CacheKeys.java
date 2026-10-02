package com.wikiagent.application.ragcache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 缓存 key 工具：sha256 摘要 + 统一前缀。
 */
final class CacheKeys {

    static final String EMB_PREFIX = "emb:";
    static final String ANS_PREFIX = "ans:";
    static final String ANS_DOC_PREFIX = "ansdoc:";

    private CacheKeys() {
    }

    static String embeddingKey(String model, String text) {
        return EMB_PREFIX + model + ":" + sha256Hex(text);
    }

    /**
     * 答案缓存 key：身份（identity，无身份 anon）+ 过滤后 domain（无 domain 过滤为 all）
     * + sessionId + query 摘要，任一段不同都不会串用缓存。
     */
    static String answerKey(String identity, String domain, String sessionId, String query) {
        return ANS_PREFIX + seg(identity) + ":" + seg(domain) + ":" + seg(sessionId)
                + ":" + sha256Hex(query);
    }

    /** key 段清洗：null/空白 → "-"；段内不允许出现 ":" 以免破坏 key 结构。 */
    private static String seg(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String s = raw.replace(":", "_").trim();
        return s.isEmpty() ? "-" : s;
    }

    static String answerDocKey(String docId) {
        return ANS_DOC_PREFIX + docId;
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 计算失败", e);
        }
    }
}
