package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 提交载荷（规格 4.2 提交请求的领域形态）。
 * bizKey 为空时由提交服务按 taskType+idempotencyKey 兜底生成。
 */
public record TaskPayload(
        String taskType,
        String bizKey,
        String submittedBy,
        String tenantId,
        String idempotencyKey,
        JsonNode args,
        Integer maxAttempts,
        Integer deadlineSec) {
}
