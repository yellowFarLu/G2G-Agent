package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 任务事件（规格 2.3）：状态迁移与审计的追加写日志。
 */
public record TaskEvent(
        String taskId,
        TaskEventType eventType,
        ActorType actorType,
        String actorId,
        JsonNode detail,
        Instant createdAt) {
}
