package com.wikiagent.application.task;

import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 看门狗核对（规格 1.4）：Worker 领取任务时发出的延迟消息到点核对——
 * 仍 RUNNING 且 lease_owner==owner 且心跳停更（heartbeat_at 早于 now-leaseTtl）→
 * 复用 {@link TaskRecoveryJob#reclaim}；否则无动作（防重复执行靠 owner 校验）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskWatchdog {

    private final TaskRepositoryPort taskRepo;
    private final TaskRecoveryJob recoveryJob;
    private final TaskProperties props;

    public TaskWatchdog(TaskRepositoryPort taskRepo,
                        TaskRecoveryJob recoveryJob,
                        TaskProperties props) {
        this.taskRepo = taskRepo;
        this.recoveryJob = recoveryJob;
        this.props = props;
    }

    public void check(String taskId, String ownerWorkerId) {
        TaskInstance task = taskRepo.findByTaskId(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.RUNNING) {
            return;
        }
        if (ownerWorkerId == null || !ownerWorkerId.equals(task.leaseOwner())) {
            return;
        }
        Instant heartbeatAt = task.heartbeatAt();
        Instant staleBefore = Instant.now().minus(Duration.ofSeconds(props.getLeaseTtlSec()));
        if (heartbeatAt != null && heartbeatAt.isAfter(staleBefore)) {
            return;
        }
        recoveryJob.reclaim(task, "看门狗判定心跳停更");
    }
}
