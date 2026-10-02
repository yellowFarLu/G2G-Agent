package com.wikiagent.domain.rule;

import java.time.Instant;

/**
 * 规则集（规格 §D1）：code+version 唯一；dslJson 为声明式算子 JSON；checksum 为 dsl 的 sha256。
 */
public record RuleSet(
        Long id,
        String code,
        int version,
        RuleStatus status,
        String dslJson,
        String checksum,
        String description,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {
}
