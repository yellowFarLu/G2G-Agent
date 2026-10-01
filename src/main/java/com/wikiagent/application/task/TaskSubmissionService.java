package com.wikiagent.application.task;

import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskPayload;
import com.wikiagent.domain.task.TaskStateMachine;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务提交服务（规格 4.2）：biz_key 幂等（命中返回既有任务）、SUBMIT 事件落库、
 * 立即投递（失败不外抛，outbox 补偿兜底）；replay 供控制接口重放终态任务。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(TaskSubmissionService.class);

    private final TaskRepositoryPort taskRepo;
    private final TaskEventRepositoryPort eventRepo;
    private final TaskDispatcherPort dispatcher;
    private final TaskProperties props;

    public TaskSubmissionService(TaskRepositoryPort taskRepo,
                                 TaskEventRepositoryPort eventRepo,
                                 TaskDispatcherPort dispatcher,
                                 TaskProperties props) {
        this.taskRepo = taskRepo;
        this.eventRepo = eventRepo;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    public TaskInstance submit(TaskPayload p) {
        String bizKey = p.bizKey() != null ? p.bizKey()
                : p.taskType() + ":" + (p.idempotencyKey() != null ? p.idempotencyKey() : UUID.randomUUID());
        Optional<TaskInstance> existing = taskRepo.findByBizKey(bizKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        int maxAttempts = p.maxAttempts() != null ? p.maxAttempts() : props.getDefaults().getMaxAttempts();
        Instant now = Instant.now();
        TaskInstance task = new TaskInstance(
                TaskIds.next(), p.taskType(), bizKey, TaskStatus.PENDING, p.args(),
                0, maxAttempts, 0, null, null, null,
                p.idempotencyKey(), p.submittedBy(), p.tenantId(),
                now, null, null, null, now, null, 0, now, now);
        eventRepo.append(new TaskEvent(task.taskId(), TaskEventType.SUBMIT, ActorType.USER,
                p.submittedBy(), null, now));
        taskRepo.save(task);
        try {
            dispatcher.dispatch(task.taskId(), task.taskType(), 0);
            taskRepo.markEnqueued(task.taskId(), Instant.now());
        } catch (Exception e) {
            // 投递失败不外抛：任务保持 PENDING，由 DispatchCompensationJob 补偿重投
            log.warn("提交后投递失败（outbox 补偿兜底）taskId={}: {}", task.taskId(), e.getMessage());
        }
        return task;
    }

    /**
     * 人工重放：仅 FAILED/CANCELLED（COMPLETED 无 REPLAY 迁移，状态机拒绝）→
     * REPLAY 事件（actor=USER）+ attempt=0 + 清错误字段 + PENDING + 立即投递。
     */
    public TaskInstance replay(String taskId, String actorId) {
        TaskInstance task = taskRepo.findByTaskId(taskId)
                .orElseThrow(() -> new com.wikiagent.dto.NotFoundException("任务不存在: " + taskId));
        if (task.status() != TaskStatus.FAILED && task.status() != TaskStatus.CANCELLED) {
            throw new com.wikiagent.dto.ConflictException(
                    "仅 FAILED/CANCELLED 任务可重放，当前状态: " + task.status());
        }
        TaskInstance updated = task
                .withStatus(TaskStateMachine.transition(task.status(), TaskEventType.REPLAY))
                .withAttempt(0)
                .withError(null, null)
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(new TaskEvent(taskId, TaskEventType.REPLAY, ActorType.USER, actorId,
                null, Instant.now()));
        try {
            dispatcher.dispatch(taskId, task.taskType(), 0);
            taskRepo.markEnqueued(taskId, Instant.now());
        } catch (Exception e) {
            log.warn("重放投递失败（outbox 补偿兜底）taskId={}: {}", taskId, e.getMessage());
        }
        return updated;
    }
}
