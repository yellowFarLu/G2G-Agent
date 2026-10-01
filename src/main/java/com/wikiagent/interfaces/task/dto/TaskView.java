package com.wikiagent.interfaces.task.dto;

import com.wikiagent.domain.task.TaskInstance;

import java.time.Instant;

/** 任务详情视图（规格 4.2 GET /api/tasks/{id}）。 */
public record TaskView(
        String taskId,
        String taskType,
        String bizKey,
        String status,
        int attempt,
        int maxAttempts,
        int progressPercent,
        String resultRef,
        String errorCode,
        String errorMsg,
        String suspendReason,
        String submittedBy,
        String leaseOwner,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskView from(TaskInstance t) {
        return new TaskView(t.taskId(), t.taskType(), t.bizKey(), t.status().name(),
                t.attempt(), t.maxAttempts(), t.progressPercent(), t.resultRef(),
                t.errorCode() == null ? null : t.errorCode().name(), t.errorMsg(),
                t.suspendReason(), t.submittedBy(), t.leaseOwner(), t.createdAt(), t.updatedAt());
    }
}
