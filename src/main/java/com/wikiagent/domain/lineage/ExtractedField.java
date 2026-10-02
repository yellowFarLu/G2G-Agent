package com.wikiagent.domain.lineage;

import com.wikiagent.domain.extract.FieldValueType;

import java.time.Instant;

/**
 * 抽取字段当前快照（每文档每字段唯一）。
 */
public record ExtractedField(
        Long id,
        String docId,
        String fieldKey,
        String fieldLabel,
        String valueText,
        FieldValueType valueType,
        double confidence,
        com.wikiagent.domain.extract.FieldSource source,
        String schemaKey,
        String schemaVersion,
        boolean valid,
        boolean reviewRequired,
        int versionNo,
        Integer pageNo,
        String snippet,
        Instant createdAt,
        Instant updatedAt) {
}
