package com.wikiagent.domain.task;

/**
 * 步骤级状态（task_step.status 取值集，规格 2.2）。
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    DONE,
    SKIPPED,
    FAILED
}
