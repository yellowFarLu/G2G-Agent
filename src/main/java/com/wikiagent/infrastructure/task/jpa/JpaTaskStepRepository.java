package com.wikiagent.infrastructure.task.jpa;

import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepStatus;
import com.wikiagent.domain.task.TaskStep;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * TaskStepRepositoryPort 的 JPA 适配器。worker 单 owner 执行，saveAllIfAbsent
 * 用存在性检查实现幂等即可满足语义。
 */
@Repository
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
@Transactional
public class JpaTaskStepRepository implements TaskStepRepositoryPort {

    private final TaskStepJpaDao dao;

    public JpaTaskStepRepository(TaskStepJpaDao dao) {
        this.dao = dao;
    }

    @Override
    public void saveAllIfAbsent(String taskId, List<StepDef> defs) {
        for (StepDef def : defs) {
            if (dao.existsByTaskIdAndStepNo(taskId, def.no())) {
                continue;
            }
            TaskStepEntity e = new TaskStepEntity();
            e.setTaskId(taskId);
            e.setStepNo(def.no());
            e.setStepType(def.type());
            e.setStepName(def.name());
            e.setStatus(StepStatus.PENDING.name());
            dao.save(e);
        }
        dao.flush();
    }

    @Override
    public List<TaskStep> findByTaskIdOrderByStepNo(String taskId) {
        return dao.findByTaskIdOrderByStepNo(taskId).stream().map(this::toRecord).toList();
    }

    @Override
    public void markRunning(String taskId, int stepNo, Instant at) {
        dao.markRunning(taskId, stepNo, JpaTaskRepository.toLocalDateTime(at));
    }

    @Override
    public void markDone(String taskId, int stepNo, String checkpoint, Instant at) {
        dao.markDone(taskId, stepNo, checkpoint, JpaTaskRepository.toLocalDateTime(at));
    }

    @Override
    public void markFailed(String taskId, int stepNo, String msg, Instant at) {
        dao.markFailed(taskId, stepNo, msg, JpaTaskRepository.toLocalDateTime(at));
    }

    @Override
    public int firstNonDoneStepNo(String taskId) {
        return dao.findFirstByTaskIdAndStatusNotOrderByStepNoAsc(taskId, StepStatus.DONE.name())
                .map(TaskStepEntity::getStepNo)
                .orElse(-1);
    }

    private TaskStep toRecord(TaskStepEntity e) {
        return new TaskStep(e.getTaskId(), e.getStepNo(), e.getStepType(), e.getStepName(),
                StepStatus.valueOf(e.getStatus()), e.getCheckpoint(),
                JpaTaskRepository.toInstant(e.getStartedAt()), JpaTaskRepository.toInstant(e.getEndedAt()),
                e.getErrorMsg());
    }
}
