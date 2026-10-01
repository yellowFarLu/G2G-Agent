package com.wikiagent.application.task;

import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.TaskStep;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
import com.wikiagent.dto.NotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务查询服务（规格 4.2）：任务详情/步骤/事件/人工任务/列表。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskQueryService {

    private static final int LIST_LIMIT = 100;

    private final TaskRepositoryPort taskRepo;
    private final TaskStepRepositoryPort stepRepo;
    private final TaskEventRepositoryPort eventRepo;
    private final HumanTaskRepositoryPort humanRepo;

    public TaskQueryService(TaskRepositoryPort taskRepo,
                            TaskStepRepositoryPort stepRepo,
                            TaskEventRepositoryPort eventRepo,
                            HumanTaskRepositoryPort humanRepo) {
        this.taskRepo = taskRepo;
        this.stepRepo = stepRepo;
        this.eventRepo = eventRepo;
        this.humanRepo = humanRepo;
    }

    public TaskInstance get(String taskId) {
        return taskRepo.findByTaskId(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
    }

    public List<TaskStep> steps(String taskId) {
        get(taskId);
        return stepRepo.findByTaskIdOrderByStepNo(taskId);
    }

    public List<TaskEvent> events(String taskId) {
        get(taskId);
        return eventRepo.findByTaskId(taskId);
    }

    public List<HumanTask> humanTasks(String taskId) {
        get(taskId);
        return humanRepo.findByTaskId(taskId);
    }

    /** 列表：status 可空；mine=true 时按 X-User-Id 过滤提交人。 */
    public List<TaskInstance> search(TaskStatus status, boolean mine, String userId) {
        return taskRepo.search(status, mine ? userId : null, LIST_LIMIT);
    }

    public boolean existsByBizKey(String bizKey) {
        return bizKey != null && taskRepo.findByBizKey(bizKey).isPresent();
    }
}
