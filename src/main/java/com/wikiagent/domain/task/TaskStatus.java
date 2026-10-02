package com.wikiagent.domain.task;

/**
 * 任务实例状态（任务级状态机状态集，规格 1.2）。
 * 终态仅 COMPLETED / FAILED / CANCELLED，其余状态皆可恢复。
 */
public enum TaskStatus {
    PENDING,
    DISPATCH,
    RUNNING,
    SUSPENDED,
    WAITING_HUMAN,
    CANCELING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** 是否终态（仅 COMPLETED/FAILED/CANCELLED 为 true）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
