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

    static String answerKey(String sessionId, String query) {
        return ANS_PREFIX + sessionId + ":" + sha256Hex(query);
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
