package com.wikiagent.interfaces.task.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** 提交任务请求（规格 4.2）：bizKey 缺省时由服务按 taskType+idempotencyKey 兜底。 */
public record SubmitTaskRequest(String taskType, String bizKey, JsonNode args, Integer maxAttempts) {
}
