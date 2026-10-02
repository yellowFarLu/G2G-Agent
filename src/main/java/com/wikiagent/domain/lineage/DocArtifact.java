package com.wikiagent.domain.lineage;

import java.time.Instant;

/**
 * 文档产物：内容落盘（{@code contentRef}），DB 仅存元数据 + sha256 校验。
 */
public record DocArtifact(
        Long id,
        String docId,
        int versionNo,
        ArtifactType type,
        String contentRef,
        String sha256,
        long sizeBytes,
        Integer pageNo,
        Instant createdAt) {
}
