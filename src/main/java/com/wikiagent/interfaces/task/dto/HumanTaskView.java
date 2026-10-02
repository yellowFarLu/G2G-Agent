package com.wikiagent.interfaces.task.dto;

import com.wikiagent.domain.task.HumanTask;

import java.time.Instant;

/** 人工接管任务视图（OPEN/CLAIMED 可操作；RESOLVED/EXPIRED 只读）。 */
public record HumanTaskView(Long id, String taskId, int stepNo, String kind, String title,
                            String instruction, com.fasterxml.jackson.databind.JsonNode formSchema,
                            com.fasterxml.jackson.databind.JsonNode formValue, String status,
                            String claimedBy, Instant claimedAt, String resolvedBy, Instant resolvedAt) {

    public static HumanTaskView from(HumanTask h) {
        return new HumanTaskView(h.id(), h.taskId(), h.stepNo(), h.kind().name(), h.title(),
                h.instruction(), h.formSchema(), h.formValue(), h.status().name(),
                h.claimedBy(), h.claimedAt(), h.resolvedBy(), h.resolvedAt());
    }
}
