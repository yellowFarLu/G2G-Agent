package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 人工接管任务（规格 2.4）：kind=INPUT 人工补表单后任务继续；kind=DIRECT_RESOLVE 人工直接终结任务。
 */
public record HumanTask(
        Long id,
        String taskId,
        int stepNo,
        HumanTaskKind kind,
        String title,
        String instruction,
        JsonNode formSchema,
        JsonNode formValue,
        HumanTaskStatus status,
        String claimedBy,
        Instant claimedAt,
        String resolvedBy,
        Instant resolvedAt,
        int lockVersion,
        Instant createdAt) {
}
