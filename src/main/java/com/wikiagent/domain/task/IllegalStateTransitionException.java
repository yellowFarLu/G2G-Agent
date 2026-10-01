package com.wikiagent.domain.task;

/**
 * 非法状态迁移异常：携带迁移前的任务状态与触发事件，由 {@link TaskStateMachine} 抛出。
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final TaskStatus from;
    private final TaskEventType event;

    public IllegalStateTransitionException(TaskStatus from, TaskEventType event) {
        super("Illegal state transition: from=" + from + ", event=" + event);
        this.from = from;
        this.event = event;
    }

    public TaskStatus getFrom() {
        return from;
    }

    public TaskEventType getEvent() {
        return event;
    }
}
