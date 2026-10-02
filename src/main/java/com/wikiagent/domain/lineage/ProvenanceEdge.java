package com.wikiagent.domain.lineage;

import java.time.Instant;

/**
 * 血缘有向边：from → to，类型化依赖，带可选说明。
 */
public record ProvenanceEdge(
        Long id,
        String docId,
        int versionNo,
        String fromRef,
        String fromType,
        String toRef,
        String toType,
        EdgeType edgeType,
        String note,
        Instant createdAt) {
}
