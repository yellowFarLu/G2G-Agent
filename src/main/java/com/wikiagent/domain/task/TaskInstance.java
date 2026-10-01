package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 任务实例（规格 2.1）。MySQL task_instance 行的领域投影， truth 源在 DB。
 */
public record TaskInstance(
        String taskId,
        String taskType,
        String bizKey,
        TaskStatus status,
        JsonNode payload,
        int attempt,
        int maxAttempts,
        int progressPercent,
        String resultRef,
        ErrorCode errorCode,
        String errorMsg,
        String idempotencyKey,
        String submittedBy,
        String tenantId,
        Instant enqueueAt,
        String leaseOwner,
        Instant leaseExpireAt,
        Instant heartbeatAt,
        Instant nextRunAt,
        String suspendReason,
        int controlVersion,
        Instant createdAt,
        Instant updatedAt) {

    public TaskInstance withStatus(TaskStatus newStatus) {
        return new TaskInstance(taskId, taskType, bizKey, newStatus, payload, attempt, maxAttempts,
                progressPercent, resultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, nextRunAt, suspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withAttempt(int newAttempt) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, newAttempt, maxAttempts,
                progressPercent, resultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, nextRunAt, suspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withNextRunAt(Instant at) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, attempt, maxAttempts,
                progressPercent, resultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, at, suspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withLease(String owner, Instant expireAt) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, attempt, maxAttempts,
                progressPercent, resultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, owner, expireAt, heartbeatAt, nextRunAt, suspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withClearLease() {
        return withLease(null, null);
    }

    public TaskInstance withProgress(int newProgress) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, attempt, maxAttempts,
                newProgress, resultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, nextRunAt, suspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withResultRef(String newResultRef) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, attempt, maxAttempts,
                progressPercent, newResultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, nextRunAt, suspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withError(ErrorCode newErrorCode, String newErrorMsg) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, attempt, maxAttempts,
                progressPercent, resultRef, newErrorCode, newErrorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, nextRunAt, suspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withSuspendReason(String newSuspendReason) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, attempt, maxAttempts,
                progressPercent, resultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, nextRunAt, newSuspendReason,
                controlVersion, createdAt, updatedAt);
    }

    public TaskInstance withControlVersion(int newControlVersion) {
        return new TaskInstance(taskId, taskType, bizKey, status, payload, attempt, maxAttempts,
                progressPercent, resultRef, errorCode, errorMsg, idempotencyKey, submittedBy, tenantId,
                enqueueAt, leaseOwner, leaseExpireAt, heartbeatAt, nextRunAt, suspendReason,
                newControlVersion, createdAt, updatedAt);
    }
}
