package com.wikiagent.domain.task.ports;

import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.TaskStep;

import java.time.Instant;
import java.util.List;

/**
 * 步骤仓储端口。
 */
public interface TaskStepRepositoryPort {

    /** 幂等落步骤定义：按 (taskId,stepNo) 不存在才插入。 */
    void saveAllIfAbsent(String taskId, List<StepDef> defs);

    List<TaskStep> findByTaskIdOrderByStepNo(String taskId);

    void markRunning(String taskId, int stepNo, Instant at);

    void markDone(String taskId, int stepNo, String checkpoint, Instant at);

    void markFailed(String taskId, int stepNo, String msg, Instant at);

    /** 第一个非 DONE 步骤的 stepNo；全部 DONE 返回 -1。 */
    int firstNonDoneStepNo(String taskId);
}
