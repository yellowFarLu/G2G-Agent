package com.wikiagent.dto;

import com.wikiagent.entity.KbDocument;

import java.time.Instant;

/** 文档视图（对外 API 返回）。 */
public record DocumentView(String id, String filename, String docType, long sizeBytes, String status,
                           int parentCount, int childCount, String error, Instant createdAt) {

    public static DocumentView from(KbDocument d) {
        return new DocumentView(d.getId(), d.getFilename(), d.getDocType(), d.getSizeBytes(),
                d.getStatus(), d.getParentCount(), d.getChildCount(), d.getError(), d.getCreatedAt());
    }
}
