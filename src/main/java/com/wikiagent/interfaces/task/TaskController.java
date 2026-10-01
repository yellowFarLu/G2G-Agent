package com.wikiagent.interfaces.task;

import com.wikiagent.application.task.TaskControlService;
import com.wikiagent.application.task.TaskQueryService;
import com.wikiagent.application.task.TaskSubmissionService;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskPayload;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.interfaces.task.dto.EventView;
import com.wikiagent.interfaces.task.dto.HumanTaskView;
import com.wikiagent.interfaces.task.dto.StepView;
import com.wikiagent.interfaces.task.dto.SubmitTaskRequest;
import com.wikiagent.interfaces.task.dto.SubmitTaskResponse;
import com.wikiagent.interfaces.task.dto.SuspendRequest;
import com.wikiagent.interfaces.task.dto.TaskView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务 REST API（规格 4.2）：提交（bizKey 幂等）、查询、暂停/恢复/取消/重放。
 * 服务 Bean 带 @ConditionalOnProperty，enabled=false 时用 ObjectProvider 探测，未开启统一 503。
 */
@RestController
public class TaskController {

    private final ObjectProvider<TaskSubmissionService> submission;
    private final ObjectProvider<TaskControlService> control;
    private final ObjectProvider<TaskQueryService> query;

    public TaskController(ObjectProvider<TaskSubmissionService> submission,
                          ObjectProvider<TaskControlService> control,
                          ObjectProvider<TaskQueryService> query) {
        this.submission = submission;
        this.control = control;
        this.query = query;
    }

    /** 提交任务：bizKey 命中既有任务时返回 duplicate=true + 同一 taskId（幂等 200+标志）。 */
    @PostMapping("/api/tasks")
    public SubmitTaskResponse submit(@RequestBody SubmitTaskRequest request,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        boolean duplicate = query().existsByBizKey(request.bizKey());
        TaskPayload payload = new TaskPayload(request.taskType(), request.bizKey(), userId,
                null, idempotencyKey, request.args(), request.maxAttempts(), null);
        TaskInstance task = submission().submit(payload);
        return new SubmitTaskResponse(task.taskId(), duplicate);
    }

    /** 列表：status 过滤（非法值忽略）、mine=true 按提交人过滤。 */
    @GetMapping("/api/tasks")
    public List<TaskView> list(@RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "false") boolean mine,
                               @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        TaskStatus st = parseStatus(status);
        return query().search(st, mine, userId).stream().map(TaskView::from).toList();
    }

    @GetMapping("/api/tasks/{taskId}")
    public TaskView get(@PathVariable String taskId) {
        return TaskView.from(query().get(taskId));
    }

    @GetMapping("/api/tasks/{taskId}/steps")
    public List<StepView> steps(@PathVariable String taskId) {
        return query().steps(taskId).stream().map(StepView::from).toList();
    }

    @GetMapping("/api/tasks/{taskId}/events")
    public List<EventView> events(@PathVariable String taskId) {
        return query().events(taskId).stream().map(EventView::from).toList();
    }

    @GetMapping("/api/tasks/{taskId}/human-tasks")
    public List<HumanTaskView> humanTasks(@PathVariable String taskId) {
        return query().humanTasks(taskId).stream().map(HumanTaskView::from).toList();
    }

    @PostMapping("/api/tasks/{taskId}/suspend")
    public TaskView suspend(@PathVariable String taskId,
                            @RequestBody(required = false) SuspendRequest body,
                            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return TaskView.from(control().suspend(taskId,
                body == null ? null : body.expectedVersion(), userId));
    }

    @PostMapping("/api/tasks/{taskId}/resume")
    public TaskView resume(@PathVariable String taskId,
                           @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return TaskView.from(control().resume(taskId, userId));
    }

    @PostMapping("/api/tasks/{taskId}/cancel")
    public TaskView cancel(@PathVariable String taskId,
                           @RequestBody(required = false) SuspendRequest body,
                           @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return TaskView.from(control().cancel(taskId,
                body == null ? null : body.expectedVersion(), userId));
    }

    @PostMapping("/api/tasks/{taskId}/replay")
    public TaskView replay(@PathVariable String taskId,
                           @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return TaskView.from(submission().replay(taskId, userId));
    }

    private static TaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(status);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private TaskSubmissionService submission() {
        return available(submission);
    }

    private TaskControlService control() {
        return available(control);
    }

    private TaskQueryService query() {
        return available(query);
    }

    private static <T> T available(ObjectProvider<T> provider) {
        T service = provider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("任务框架未开启（wikiagent.task.enabled=false）");
        }
        return service;
    }
}
