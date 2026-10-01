package com.wikiagent.domain.task;

import java.util.Map;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * 任务级状态机（规格 1.2）：唯一事实来源为闭合迁移表，
 * 表外任意 (from, event) 组合一律抛 {@link IllegalStateTransitionException}，无默认放行。
 */
public final class TaskStateMachine {

    /** 迁移键：from + event。 */
    private record TransitionKey(TaskStatus from, TaskEventType event) {
    }

    private static final Map<TransitionKey, TaskStatus> TRANSITIONS = Stream.of(
                    // 规格 1.2：新建 → PENDING（from=null，走 assertInitial）
                    // PENDING --DISPATCH--> DISPATCH
                    entry(new TransitionKey(TaskStatus.PENDING, TaskEventType.DISPATCH), TaskStatus.DISPATCH),
                    // PENDING/DISPATCH --LEASE--> RUNNING
                    entry(new TransitionKey(TaskStatus.PENDING, TaskEventType.LEASE), TaskStatus.RUNNING),
                    entry(new TransitionKey(TaskStatus.DISPATCH, TaskEventType.LEASE), TaskStatus.RUNNING),
                    // RUNNING --SUSPEND--> SUSPENDED（步骤边界检查到 PAUSE）
                    entry(new TransitionKey(TaskStatus.RUNNING, TaskEventType.SUSPEND), TaskStatus.SUSPENDED),
                    // RUNNING --WAIT_HUMAN--> WAITING_HUMAN（到达 HumanCheckpoint）
                    entry(new TransitionKey(TaskStatus.RUNNING, TaskEventType.WAIT_HUMAN), TaskStatus.WAITING_HUMAN),
                    // SUSPENDED/WAITING_HUMAN --RESUME--> PENDING（重新投递）
                    entry(new TransitionKey(TaskStatus.SUSPENDED, TaskEventType.RESUME), TaskStatus.PENDING),
                    entry(new TransitionKey(TaskStatus.WAITING_HUMAN, TaskEventType.RESUME), TaskStatus.PENDING),
                    // WAITING_HUMAN --HUMAN_RESOLVE--> COMPLETED（人工 DIRECT_RESOLVE）
                    entry(new TransitionKey(TaskStatus.WAITING_HUMAN, TaskEventType.HUMAN_RESOLVE), TaskStatus.COMPLETED),
                    // RUNNING/SUSPENDED --REQUEST_CANCEL--> CANCELING
                    entry(new TransitionKey(TaskStatus.RUNNING, TaskEventType.REQUEST_CANCEL), TaskStatus.CANCELING),
                    entry(new TransitionKey(TaskStatus.SUSPENDED, TaskEventType.REQUEST_CANCEL), TaskStatus.CANCELING),
                    // CANCELING --CANCEL--> CANCELLED（补偿完成）
                    entry(new TransitionKey(TaskStatus.CANCELING, TaskEventType.CANCEL), TaskStatus.CANCELLED),
                    // RUNNING --RETRY--> PENDING（可重试失败，退避重投）
                    entry(new TransitionKey(TaskStatus.RUNNING, TaskEventType.RETRY), TaskStatus.PENDING),
                    // RUNNING --COMPLETE--> COMPLETED（全部步骤 DONE）
                    entry(new TransitionKey(TaskStatus.RUNNING, TaskEventType.COMPLETE), TaskStatus.COMPLETED),
                    // RUNNING --FAIL--> FAILED（不可重试失败 / 重试耗尽）
                    entry(new TransitionKey(TaskStatus.RUNNING, TaskEventType.FAIL), TaskStatus.FAILED),
                    // FAILED/CANCELLED --REPLAY--> PENDING（人工重放）
                    entry(new TransitionKey(TaskStatus.FAILED, TaskEventType.REPLAY), TaskStatus.PENDING),
                    entry(new TransitionKey(TaskStatus.CANCELLED, TaskEventType.REPLAY), TaskStatus.PENDING))
            .collect(toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private TaskStateMachine() {
    }

    /**
     * 执行状态迁移；非法迁移抛 {@link IllegalStateTransitionException}。
     *
     * @param from  当前状态，null 表示新建场景（仅接受 SUBMIT）
     * @param event 触发事件
     * @return 迁移后的目标状态
     */
    public static TaskStatus transition(TaskStatus from, TaskEventType event) {
        if (from == null) {
            return assertInitial(event);
        }
        TaskStatus target = TRANSITIONS.get(new TransitionKey(from, event));
        if (target == null) {
            throw new IllegalStateTransitionException(from, event);
        }
        return target;
    }

    /**
     * 新建场景：仅 SUBMIT 合法，任务初始状态为 PENDING。
     */
    public static TaskStatus assertInitial(TaskEventType event) {
        if (event == TaskEventType.SUBMIT) {
            return TaskStatus.PENDING;
        }
        throw new IllegalStateTransitionException(null, event);
    }

    /**
     * 该状态是否可被 Worker 领取（租约抢占比对条件 status IN (PENDING, DISPATCH)）。
     */
    public static boolean canLease(TaskStatus status) {
        return status == TaskStatus.PENDING || status == TaskStatus.DISPATCH;
    }
}
