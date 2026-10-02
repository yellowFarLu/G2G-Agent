package com.wikiagent.domain.prompt;

import java.time.Instant;

/**
 * 提示词模板领域模型（纯 Java record，DDD domain 层）。
 * <p>
 * 按 {@code code} 标识模板族，同 code 多版本并存，
 * 仅 {@link Status#ACTIVE} 版本参与渲染。
 */
public record PromptTemplate(
        Long id,
        String code,
        int version,
        String content,
        Status status,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        DRAFT, ACTIVE, ARCHIVED;

        public static Status from(String raw) {
            if (raw == null) {
                return DRAFT;
            }
            for (Status s : values()) {
                if (s.name().equalsIgnoreCase(raw)) {
                    return s;
                }
            }
            return DRAFT;
        }
    }
}
