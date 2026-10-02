package com.wikiagent.interfaces.task.dto;

import com.wikiagent.domain.task.TaskStep;

import java.time.Instant;

/** 步骤视图。checkpoint 中间态详情不外露（仅存续跑用）。 */
public record StepView(int stepNo, String stepType, String stepName, String status,
                       Instant startedAt, Instant endedAt, String errorMsg) {

    public static StepView from(TaskStep s) {
        return new StepView(s.stepNo(), s.stepType(), s.stepName(), s.status().name(),
                s.startedAt(), s.endedAt(), s.errorMsg());
    }
}
