package com.wikiagent.interfaces.task.dto;

import com.wikiagent.domain.task.TaskEvent;

import java.time.Instant;

/** 事件视图（追加式事件流按 id 升序 = 时间序）。 */
public record EventView(String eventType, String actorType, String actorId,
                        com.fasterxml.jackson.databind.JsonNode detail, Instant createdAt) {

    public static EventView from(TaskEvent e) {
        return new EventView(e.eventType().name(), e.actorType().name(), e.actorId(),
                e.detail(), e.createdAt());
    }
}
