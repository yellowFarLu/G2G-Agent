package com.wikiagent.dto;

import com.wikiagent.entity.KbDocument;

import java.time.Instant;

/** 文档视图（对外 API 返回）。taskId/duplicate 仅上传接口在任务框架路径下返回，其余为 null。 */
public record DocumentView(String id, String filename, String docType, long sizeBytes, String status,
                           int parentCount, int childCount, String error, Instant createdAt,
                           String taskId, Boolean duplicate) {

    public static DocumentView from(KbDocument d) {
        return of(d, null, null);
    }

    public static DocumentView of(KbDocument d, String taskId, Boolean duplicate) {
        return new DocumentView(d.getId(), d.getFilename(), d.getDocType(), d.getSizeBytes(),
                d.getStatus(), d.getParentCount(), d.getChildCount(), d.getError(),
                d.getCreatedAt(), taskId, duplicate);
    }
}
