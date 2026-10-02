package com.wikiagent.domain.lineage;

import java.time.Instant;

/**
 * 文档版本：重解析产生新版本，旧版本 chunk is_active=false，SUPERSEDES 链靠 parentVersionNo。
 */
public record DocVersion(
        Long id,
        String docId,
        int versionNo,
        String status,
        Integer parentVersionNo,
        String changeSummary,
        String artifactSha256,
        String createdBy,
        Instant createdAt) {

    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String SUPERSEDED = "SUPERSEDED";
}
