package com.wikiagent.domain.llm;

/**
 * 模型调用打点用途枚举。
 */
public enum ModelCallLogPurpose {
    INTENT, EXTRACT, CHAT, RERANK, JUDGE;

    public static ModelCallLogPurpose from(String raw) {
        if (raw == null) {
            return null;
        }
        for (ModelCallLogPurpose p : values()) {
            if (p.name().equalsIgnoreCase(raw)) {
                return p;
            }
        }
        return null;
    }
}
