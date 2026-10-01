package com.wikiagent.domain.task;

import java.time.Instant;

/**
 * 任务步骤（规格 2.2）。
 */
public record TaskStep(
        String taskId,
        int stepNo,
        String stepType,
        String stepName,
        StepStatus status,
        String checkpoint,
        Instant startedAt,
        Instant endedAt,
        String errorMsg) {
}
