package com.wikiagent.domain.task;

/**
 * 任务事件类型（task_event.event_type 取值集，规格 2.3 的 16 个事件 + WAIT_HUMAN）。
 * 其中 HEARTBEAT/STEP_START/STEP_DONE/HUMAN_TAKE 为纯审计事件，不驱动任务状态迁移。
 */
public enum TaskEventType {
    SUBMIT,
    DISPATCH,
    LEASE,
    HEARTBEAT,
    STEP_START,
    STEP_DONE,
    SUSPEND,
    RESUME,
    REQUEST_CANCEL,
    CANCEL,
    HUMAN_TAKE,
    HUMAN_RESOLVE,
    RETRY,
    COMPLETE,
    FAIL,
    REPLAY,
    /** 到达人工检查点，驱动 RUNNING → WAITING_HUMAN。 */
    WAIT_HUMAN
}
