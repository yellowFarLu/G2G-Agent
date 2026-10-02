package com.wikiagent.application.task;

import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStateMachine;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 崩溃恢复扫描（规格 1.3）：定时扫「RUNNING 且租约过期」的任务统一回收。
 * reclaim 同时供看门狗（TaskWatchdog）复用，构成崩溃恢复双保险。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryJob.class);

    private static final int SCAN_LIMIT = 50;

    private final TaskRepositoryPort taskRepo;
    private final TaskEventRepositoryPort eventRepo;
    private final TaskDispatcherPort dispatcher;

    public TaskRecoveryJob(TaskRepositoryPort taskRepo,
                           TaskEventRepositoryPort eventRepo,
                           TaskDispatcherPort dispatcher) {
        this.taskRepo = taskRepo;
        this.eventRepo = eventRepo;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${wikiagent.task.recovery-scan-sec:15}000")
    public void runOnce() {
        List<TaskInstance> expired = taskRepo.findExpiredLeases(Instant.now(), SCAN_LIMIT);
        for (TaskInstance task : expired) {
            reclaim(task, "租约过期，恢复扫描回收");
        }
    }

    /**
     * 统一回收：attempt+1 ≤ max → RETRY 事件 + PENDING + 退避重投；超过 → FAILED + 告警日志。
     *
     * @return true 表示已重投（RETRY），false 表示已终态（FAILED）
     */
    public boolean reclaim(TaskInstance task, String reason) {
        int newAttempt = task.attempt() + 1;
        if (newAttempt <= task.maxAttempts()) {
            TaskInstance updated = task
                    .withStatus(TaskStateMachine.transition(task.status(), TaskEventType.RETRY))
                    .withAttempt(newAttempt)
                    .withNextRunAt(Instant.now().plus(Backoff.durationForAttempt(newAttempt)))
                    .withError(ErrorCode.INTERNAL, reason)
                    .withClearLease();
            taskRepo.save(updated);
            eventRepo.append(new TaskEvent(task.taskId(), TaskEventType.RETRY, ActorType.SYSTEM, "recovery",
                    null, Instant.now()));
            dispatcher.dispatch(task.taskId(), task.taskType(), Backoff.delayLevelForAttempt(newAttempt));
            return true;
        }
        TaskInstance failed = task
                .withStatus(TaskStateMachine.transition(task.status(), TaskEventType.FAIL))
                .withError(ErrorCode.INTERNAL, reason)
                .withClearLease();
        taskRepo.save(failed);
        eventRepo.append(new TaskEvent(task.taskId(), TaskEventType.FAIL, ActorType.SYSTEM, "recovery",
                null, Instant.now()));
        log.error("[告警] 任务重试耗尽置 FAILED taskId={} type={} reason={}",
                task.taskId(), task.taskType(), reason);
        return false;
    }
}
