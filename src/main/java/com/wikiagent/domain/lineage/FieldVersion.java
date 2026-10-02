package com.wikiagent.domain.lineage;

import com.wikiagent.domain.extract.FieldSource;

import java.time.Instant;

/**
 * 字段版本（不可变历史）：每次值/置信/来源变更写一行，SUPERSEDES 链靠 versionNo 递增。
 */
public record FieldVersion(
        Long id,
        String docId,
        String fieldKey,
        int versionNo,
        String valueText,
        double confidence,
        FieldSource source,
        String editedBy,
        String changeReason,
        Instant createdAt) {
}
