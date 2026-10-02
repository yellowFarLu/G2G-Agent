package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 任务流事件（SSE 桥数据源，Task 12 消费）。
 *
 * @param taskId  任务 ID
 * @param type    事件类型：delta/done/error/progress
 * @param payload 事件负载（自由 JSON）
 */
public record StreamEvent(String taskId, String type, JsonNode payload) {
}
