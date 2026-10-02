package com.wikiagent.application.task;

import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.ControlFlag;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.ControlFlagPort;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.dto.ConflictException;
import com.wikiagent.dto.NotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/**
 * 任务控制服务（规格 3.2/4.2）：暂停/恢复/取消。
 * 双写：Redis 控制标志（乐观版本）+ MySQL 权威痕迹（suspendReason/controlVersion）。
 * MySQL 侧全部为定向/CAS 更新：不与 worker 的全量保存发生丢失更新，
 * 状态迁移以 DB where 条件为准（原子），Redis 侧仅作乐观版本预校验。
 * RUNNING 中的任务不抢占当前动作——worker 仅在步骤边界响应。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskControlService {

    private static final Set<TaskStatus> TERMINAL =
            Set.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED);

    private final TaskRepositoryPort taskRepo;
    private final TaskEventRepositoryPort eventRepo;
    private final ControlFlagPort controlFlagPort;
    private final TaskDispatcherPort dispatcher;

    public TaskControlService(TaskRepositoryPort taskRepo,
                              TaskEventRepositoryPort eventRepo,
                              ControlFlagPort controlFlagPort,
                              TaskDispatcherPort dispatcher) {
        this.taskRepo = taskRepo;
        this.eventRepo = eventRepo;
        this.controlFlagPort = controlFlagPort;
        this.dispatcher = dispatcher;
    }

    /**
     * 暂停：expectedVersion 为空表示不校验（取当前版本）。
     * 已 SUSPENDED 幂等返回；终态拒绝。写 Redis PAUSE 标志 + DB suspendReason（定向更新）。
     */
    public TaskInstance suspend(String taskId, Integer expectedVersion, String actorId) {
        TaskInstance task = requireNonTerminal(taskId);
        if (task.status() == TaskStatus.SUSPENDED) {
            return task;
        }
        int version = expectedVersion != null ? expectedVersion : controlFlagPort.currentVersion(taskId);
        controlFlagPort.requestPause(taskId, version);
        int newVersion = controlFlagPort.currentVersion(taskId);
        // 定向更新：仅痕迹字段；期间任务进入终态则 0 行生效（终态无需暂停痕迹），返回当前视图
        taskRepo.updateControlTrace(taskId, "用户暂停: " + actorId, newVersion);
        eventRepo.append(new TaskEvent(taskId, TaskEventType.SUSPEND, ActorType.USER, actorId,
                null, Instant.now()));
        return taskRepo.findByTaskId(taskId).orElse(task);
    }

    /**
     * 恢复：清 Redis 标志 + DB suspendReason；SUSPENDED → PENDING（CAS 迁移）并重新投递；
     * RUNNING/PENDING（尚未到边界的暂停）只清痕迹。无暂停痕迹时拒绝。
     */
    public TaskInstance resume(String taskId, String actorId) {
        TaskInstance task = taskRepo.findByTaskId(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
        if (TERMINAL.contains(task.status())) {
            throw new ConflictException("终态任务不可恢复: " + task.status());
        }
        boolean hasPauseTrace = task.suspendReason() != null
                || controlFlagPort.read(taskId) == ControlFlag.PAUSE;
        if (!hasPauseTrace) {
            throw new ConflictException("任务无暂停痕迹，无需恢复");
        }
        controlFlagPort.clear(taskId);
        int newVersion = controlFlagPort.currentVersion(taskId);
        if (task.status() == TaskStatus.SUSPENDED) {
            taskRepo.casResume(taskId, newVersion);
            eventRepo.append(new TaskEvent(taskId, TaskEventType.RESUME, ActorType.USER, actorId,
                    null, Instant.now()));
            dispatcher.dispatch(taskId, task.taskType(), 0);
        } else {
            // RUNNING/PENDING：暂停尚未到达步骤边界，仅清痕迹
            taskRepo.updateControlTrace(taskId, null, newVersion);
            eventRepo.append(new TaskEvent(taskId, TaskEventType.RESUME, ActorType.USER, actorId,
                    null, Instant.now()));
        }
        return taskRepo.findByTaskId(taskId).orElse(task);
    }

    /**
     * 取消：Redis CANCEL 标志（乐观版本）+ DB CAS RUNNING/SUSPENDED → CANCELING；
     * worker 在步骤边界响应后置 CANCELLED（终态）。CAS 失败说明状态已变化（含终态），拒绝并撤回标志。
     */
    public TaskInstance cancel(String taskId, Integer expectedVersion, String actorId) {
        TaskInstance task = requireNonTerminal(taskId);
        if (task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.SUSPENDED) {
            throw new ConflictException("当前状态不可取消: " + task.status());
        }
        int version = expectedVersion != null ? expectedVersion : controlFlagPort.currentVersion(taskId);
        controlFlagPort.requestCancel(taskId, version);
        if (taskRepo.casCanceling(taskId, controlFlagPort.currentVersion(taskId)) == 0) {
            controlFlagPort.clear(taskId);
            throw new ConflictException("当前状态不可取消（状态已变化）: " + task.status());
        }
        eventRepo.append(new TaskEvent(taskId, TaskEventType.REQUEST_CANCEL, ActorType.USER, actorId,
                null, Instant.now()));
        return taskRepo.findByTaskId(taskId).orElse(task);
    }

    private TaskInstance requireNonTerminal(String taskId) {
        TaskInstance task = taskRepo.findByTaskId(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
        if (TERMINAL.contains(task.status())) {
            throw new ConflictException("终态任务不可执行控制指令: " + task.status());
        }
        return task;
    }
}
