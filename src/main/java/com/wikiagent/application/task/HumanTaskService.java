package com.wikiagent.application.task;

import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStateMachine;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.dto.ConflictException;
import com.wikiagent.dto.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * 人工接管服务（规格 3.4）：claim 用 lock_version CAS（同一接管点仅一人）；
 * resolve INPUT → 任务 RESUME 回 PENDING 重新投递（人工输入由 worker 合并进上下文），
 * DIRECT_RESOLVE → 任务 HUMAN_RESOLVE 直接 COMPLETED。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class HumanTaskService {

    private final HumanTaskRepositoryPort humanRepo;
    private final TaskRepositoryPort taskRepo;
    private final TaskEventRepositoryPort eventRepo;
    private final TaskDispatcherPort dispatcher;

    public HumanTaskService(HumanTaskRepositoryPort humanRepo,
                            TaskRepositoryPort taskRepo,
                            TaskEventRepositoryPort eventRepo,
                            TaskDispatcherPort dispatcher) {
        this.humanRepo = humanRepo;
        this.taskRepo = taskRepo;
        this.eventRepo = eventRepo;
        this.dispatcher = dispatcher;
    }

    /** 认领：OPEN→CLAIMED 的 CAS；已被他人认领/处置抛 409。 */
    @Transactional
    public HumanTask claim(Long id, String userId, Integer expectedLockVersion) {
        HumanTask ht = humanRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("人工任务不存在: " + id));
        if (ht.status() != HumanTaskStatus.OPEN) {
            throw new ConflictException("人工任务已被认领或处置: " + ht.status());
        }
        int version = expectedLockVersion != null ? expectedLockVersion : ht.lockVersion();
        if (!humanRepo.casClaim(id, userId, version)) {
            throw new ConflictException("认领冲突：已被他人接管");
        }
        HumanTask claimed = humanRepo.findById(id).orElseThrow();
        eventRepo.append(new TaskEvent(claimed.taskId(), TaskEventType.HUMAN_TAKE, ActorType.USER, userId,
                null, Instant.now()));
        return claimed;
    }

    /**
     * 处置：INPUT / REVIEW / TOOL_APPROVAL → formValue 落库 + 任务 WAITING_HUMAN→PENDING（RESUME）+ 投递
     * （投递在事务提交后执行）：
     * <ul>
     *   <li>INPUT/DECRYPT：表单值由 worker humanInputs 并入上下文续跑；</li>
     *   <li>REVIEW：字段经 review_case 处置定稿，worker 在 EXTRACT 步骤识别 RESOLVED REVIEW 后
     *       跳过 LLM 重抽取，继续 CLEAN/SPLIT/EMBED/VERIFY（不可直接终结，否则文档永不入库）；</li>
     *   <li>TOOL_APPROVAL：批准/驳回决策由 AgentTaskHandler.approvalDecisionsOf 从 RESOLVED 人工任务读回。</li>
     * </ul>
     * DIRECT_RESOLVE → 任务 HUMAN_RESOLVE → COMPLETED + resultRef。
     */
    @Transactional
    public TaskInstance resolve(Long id, String userId, HumanTaskKind kind, JsonNode formValue,
                                String resultRef) {
        HumanTask ht = humanRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("人工任务不存在: " + id));
        if (ht.status() == HumanTaskStatus.RESOLVED || ht.status() == HumanTaskStatus.EXPIRED) {
            throw new ConflictException("人工任务已处置: " + ht.status());
        }
        TaskInstance task = taskRepo.findByTaskId(ht.taskId())
                .orElseThrow(() -> new NotFoundException("任务不存在: " + ht.taskId()));
        if (task.status() != com.wikiagent.domain.task.TaskStatus.WAITING_HUMAN) {
            throw new ConflictException("任务当前状态不可人工处置: " + task.status());
        }
        humanRepo.resolve(id, userId, formValue, kind);
        eventRepo.append(new TaskEvent(ht.taskId(), TaskEventType.HUMAN_RESOLVE, ActorType.USER, userId,
                null, Instant.now()));
        if (kind == HumanTaskKind.INPUT
                || kind == HumanTaskKind.DECRYPT
                || kind == HumanTaskKind.TOOL_APPROVAL
                || kind == HumanTaskKind.REVIEW) {
            TaskInstance updated = task
                    .withStatus(TaskStateMachine.transition(task.status(), TaskEventType.RESUME))
                    .withClearLease();
            taskRepo.save(updated);
            dispatchAfterCommit(ht.taskId(), task.taskType());
            return updated;
        }
        // DIRECT_RESOLVE：人工直接终结剩余步骤
        TaskInstance completed = task
                .withStatus(TaskStateMachine.transition(task.status(), TaskEventType.HUMAN_RESOLVE))
                .withProgress(100)
                .withResultRef(resultRef)
                .withClearLease();
        taskRepo.save(completed);
        return completed;
    }

    private void dispatchAfterCommit(String taskId, String taskType) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    dispatcher.dispatch(taskId, taskType, 0);
                } catch (Exception e) {
                    // 投递失败由 outbox 补偿兜底
                }
            }
        });
    }
}
