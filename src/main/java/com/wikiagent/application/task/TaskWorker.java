package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.CancelSignalException;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.PauseSignalException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStateMachine;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.TaskStep;
import com.wikiagent.domain.task.ports.ControlFlagPort;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.LeasePort;
import com.wikiagent.domain.task.ports.ProgressPort;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务执行器（规格 3.1）：MQ 消费入口 → 护栏 → CAS 租约 → 心跳续租 →
 * planSteps 落库/断点续跑 → 逐步执行（边界控制检查 + 步骤超时）→ 重试/终态落库。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskWorker implements TaskMessageSink {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 看门狗延迟消息等级：RocketMQ level 4=30s，与默认 leaseTtl 30s 对齐。 */
    private static final int WATCHDOG_DELAY_LEVEL = 4;

    private final TaskRepositoryPort taskRepo;
    private final TaskStepRepositoryPort stepRepo;
    private final TaskEventRepositoryPort eventRepo;
    private final HumanTaskRepositoryPort humanRepo;
    private final LeasePort leasePort;
    private final ControlFlagPort controlFlagPort;
    private final ProgressPort progressPort;
    private final TaskDispatcherPort dispatcher;
    private final TaskStreamBus streamBus;
    private final TaskProperties props;
    private final TaskHandlerRegistry registry;
    private final ErrorClassifier errorClassifier;

    private final String workerId = "w-" + UUID.randomUUID().toString().substring(0, 8);

    /** 字段注入：保持 12 参构造以支持测试手工实例化（手工 new 时为 null，onWatchdog 空转）。 */
    @Autowired(required = false)
    private TaskWatchdog watchdog;
    private final ExecutorService stepPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-worker-step");
        t.setDaemon(true);
        return t;
    });

    public TaskWorker(TaskRepositoryPort taskRepo,
                      TaskStepRepositoryPort stepRepo,
                      TaskEventRepositoryPort eventRepo,
                      HumanTaskRepositoryPort humanRepo,
                      LeasePort leasePort,
                      ControlFlagPort controlFlagPort,
                      ProgressPort progressPort,
                      TaskDispatcherPort dispatcher,
                      TaskStreamBus streamBus,
                      TaskProperties props,
                      TaskHandlerRegistry registry,
                      ErrorClassifier errorClassifier) {
        this.taskRepo = taskRepo;
        this.stepRepo = stepRepo;
        this.eventRepo = eventRepo;
        this.humanRepo = humanRepo;
        this.leasePort = leasePort;
        this.controlFlagPort = controlFlagPort;
        this.progressPort = progressPort;
        this.dispatcher = dispatcher;
        this.streamBus = streamBus;
        this.props = props;
        this.registry = registry;
        this.errorClassifier = errorClassifier;
    }

    @PreDestroy
    void stop() {
        stepPool.shutdownNow();
    }

    /** 单步默认超时：StepDef 显式声明优先，否则取全局默认。测试可覆盖。 */
    protected Duration stepTimeout(StepDef def) {
        if (def.timeoutSec() != null && def.timeoutSec() > 0) {
            return Duration.ofSeconds(def.timeoutSec());
        }
        return Duration.ofSeconds(Math.max(1, props.getDefaults().getStepTimeoutSec()));
    }

    @Override
    public void onMessage(String taskId, String taskType) {
        // ① 幂等护栏：任务不存在或状态不可领取（非 PENDING/DISPATCH）直接 ACK 跳过
        TaskInstance current = taskRepo.findByTaskId(taskId).orElse(null);
        if (current == null || !TaskStateMachine.canLease(current.status())) {
            return;
        }
        // ② CAS 抢租约（DB 权威 + Redis 租约双写）；抢不到说明另一副本已领取
        Instant expireAt = Instant.now().plus(Duration.ofSeconds(props.getLeaseTtlSec()));
        if (!taskRepo.casLease(taskId, workerId, expireAt)) {
            return;
        }
        leasePort.tryAcquire(taskId, workerId, Duration.ofSeconds(props.getLeaseTtlSec()));
        TaskInstance task = taskRepo.findByTaskId(taskId).orElseThrow();
        // LEASE：PENDING/DISPATCH → RUNNING
        task = taskRepo.save(task.withStatus(TaskStateMachine.transition(task.status(), TaskEventType.LEASE)));
        eventRepo.append(event(taskId, TaskEventType.LEASE, null));
        // 看门狗双保险：leaseTtl 后核对租约/心跳是否存活（规格 1.4）
        dispatcher.dispatchWatchdog(taskId, workerId, WATCHDOG_DELAY_LEVEL);

        ScheduledExecutorService heartbeat = startHeartbeat(taskId);
        try {
            runTask(task, taskType);
        } finally {
            heartbeat.shutdownNow();
            TaskControlContext.clear();
            leasePort.release(taskId, workerId);
        }
    }

    @Override
    public void onWatchdog(String taskId, String ownerWorkerId) {
        // 看门狗核对（Task 9）：仍 RUNNING 且 owner 匹配且心跳停更 → 复用统一回收
        if (watchdog != null) {
            watchdog.check(taskId, ownerWorkerId);
        }
    }

    private ScheduledExecutorService startHeartbeat(String taskId) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-worker-heartbeat-" + taskId);
            t.setDaemon(true);
            return t;
        });
        AtomicInteger progressRef = new AtomicInteger(
                taskRepo.findByTaskId(taskId).map(TaskInstance::progressPercent).orElse(0));
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                leasePort.renew(taskId, workerId, Duration.ofSeconds(props.getLeaseTtlSec()));
                taskRepo.updateHeartbeat(taskId, Instant.now());
                progressPort.publish(taskId, progressRef.get(), null);
                eventRepo.append(event(taskId, TaskEventType.HEARTBEAT, null));
            } catch (Exception ignored) {
                // 心跳失败不影响主流程；租约过期由恢复扫描兜底（Task 9）
            }
        }, props.getHeartbeatSec(), props.getHeartbeatSec(), TimeUnit.SECONDS);
        return heartbeat;
    }

    private void runTask(TaskInstance task, String taskType) {
        String taskId = task.taskId();
        TaskHandler handler = registry.handler(taskType).orElse(null);
        if (handler == null) {
            failTask(task, ErrorCode.INTERNAL, "未注册的任务处理器: " + taskType);
            return;
        }
        TaskExecutionContext ctx = new TaskExecutionContext(taskId, task.taskType(),
                task.payload(), humanInputs(taskId), task.attempt());
        // ④ planSteps 落库（幂等）+ 已存在步骤回填（断点续跑）
        List<StepDef> plan = new ArrayList<>(handler.planSteps(task.payload()));
        plan.sort(Comparator.comparingInt(StepDef::no));
        stepRepo.saveAllIfAbsent(taskId, plan);
        for (TaskStep step : stepRepo.findByTaskIdOrderByStepNo(taskId)) {
            if (step.checkpoint() != null && !validJson(step.checkpoint())) {
                // 恢复数据损坏：重试无意义，直接致命失败，不再投递（Task 8 裁定）
                stepRepo.markFailed(taskId, step.stepNo(), "步骤 checkpoint JSON 损坏", Instant.now());
                failTask(task, ErrorCode.INTERNAL, "步骤 checkpoint JSON 损坏: stepNo=" + step.stepNo());
                return;
            }
            ctx.seedStep(new StepDef(step.stepNo(), step.stepType(), step.stepName(), false, null),
                    step.checkpoint());
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        int startNo = stepRepo.firstNonDoneStepNo(taskId);
        if (startNo == -1) { // 全部步骤已 DONE（崩溃于 COMPLETE 前）：直接补终态
            completeTask(task, resultRef.get());
            return;
        }
        // 索引遍历：循环内 mergeRegistered 会向 plan 追加动态注册步骤，不能用 for-each
        int idx = 0;
        while (idx < plan.size()) {
            // 先并入动态注册步骤再取当前步（AGENT：PLAN 注册的 NODE 必须先于 GENERATE 执行）
            mergeRegistered(ctx, plan);
            StepDef def = plan.get(idx);
            idx++;
            if (def.no() < startNo) {
                continue;
            }
            // ⑤ 步骤边界控制检查（主线程双读）
            TaskControlContext.set(new TaskControlContext.Ctx(taskId, controlFlagPort, taskRepo));
            try {
                TaskControlContext.checkpointAndThrowIfSignaled();
            } catch (PauseSignalException p) {
                suspendTask(task);
                return;
            } catch (CancelSignalException c) {
                cancelTask(task, handler, ctx);
                return;
            } finally {
                TaskControlContext.clear();
            }

            // ⑥ 逐步执行：池内 submit + 步骤超时
            stepRepo.markRunning(taskId, def.no(), Instant.now());
            eventRepo.append(event(taskId, TaskEventType.STEP_START, stepDetail(def)));
            Future<StepResult> future = stepPool.submit(() -> {
                TaskControlContext.set(new TaskControlContext.Ctx(taskId, controlFlagPort, taskRepo));
                try {
                    ctx.setCurrentStepNo(def.no());
                    return handler.executeStep(ctx);
                } finally {
                    TaskControlContext.clear();
                }
            });
            StepResult result;
            try {
                result = future.get(stepTimeout(def).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                stepRepo.markFailed(taskId, def.no(),
                        "步骤超时(" + stepTimeout(def).toSeconds() + "s)", Instant.now());
                classifyAndTerminate(task, ErrorCode.TIMEOUT, "步骤超时: " + def.name());
                return;
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                if (cause instanceof PauseSignalException) {
                    suspendTask(task);
                    return;
                }
                if (cause instanceof CancelSignalException) {
                    cancelTask(task, handler, ctx);
                    return;
                }
                if (cause instanceof HumanRequiredException h) {
                    waitHuman(task, def, h);
                    return;
                }
                stepRepo.markFailed(taskId, def.no(), String.valueOf(cause.getMessage()), Instant.now());
                ErrorClassifier.Decision decision = errorClassifier.classify(cause);
                classifyAndTerminate(task, decision.code(), cause.getMessage());
                return;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                failTask(task, ErrorCode.INTERNAL, "worker 线程被中断");
                return;
            }

            // 步骤成功：落步骤终态 + 推进进度
            stepRepo.markDone(taskId, def.no(), result.skip() ? null : result.checkpointJson(), Instant.now());
            // 动态注册步骤立即持久化（AgentTaskExecutionContext 契约）：否则恢复时
            // 未开始执行的 NODE 步无行可种子，会被 firstNonDone 跳过
            stepRepo.saveAllIfAbsent(taskId, ctx.registeredSteps());
            eventRepo.append(event(taskId, TaskEventType.STEP_DONE, stepDetail(def)));
            if (result.resultRef() != null) {
                resultRef.set(result.resultRef());
            }
            if (!result.skip()) {
                progressPort.publish(taskId, result.progressPercent(), def.name());
                streamBus.publish(new StreamEvent(taskId, "progress",
                        MAPPER.createObjectNode().put("percent", result.progressPercent())
                                .put("step", def.name())));
            }
        }
        completeTask(task, resultRef.get());
    }

    /** ⑦ 可重试且未超上限 → RETRY 退避重投；否则 FAILED。 */
    private void classifyAndTerminate(TaskInstance task, ErrorCode code, String msg) {
        if (code.retryable() && task.attempt() < task.maxAttempts()) {
            retryTask(task, code, msg);
        } else {
            failTask(task, code, msg);
        }
    }

    private void retryTask(TaskInstance task, ErrorCode code, String msg) {
        if (yieldToCancelFlow(task.taskId(), task)) {
            return;
        }
        // 以库内最新记录为基：步骤执行期间控制面的 suspendReason/controlVersion 不被覆盖
        TaskInstance base = taskRepo.findByTaskId(task.taskId()).orElse(task);
        int newAttempt = task.attempt() + 1;
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.RETRY))
                .withAttempt(newAttempt)
                .withNextRunAt(Instant.now().plus(Backoff.durationForAttempt(newAttempt)))
                .withError(code, msg)
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(task.taskId(), TaskEventType.RETRY,
                MAPPER.createObjectNode().put("errorCode", code.name())
                        .put("attempt", newAttempt)
                        .put("backoffSec", Backoff.durationForAttempt(newAttempt).toSeconds())));
        dispatcher.dispatch(task.taskId(), task.taskType(), Backoff.delayLevelForAttempt(newAttempt));
    }

    private void failTask(TaskInstance task, ErrorCode code, String msg) {
        if (yieldToCancelFlow(task.taskId(), task)) {
            return;
        }
        TaskInstance base = taskRepo.findByTaskId(task.taskId()).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.FAIL))
                .withError(code, msg)
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(task.taskId(), TaskEventType.FAIL,
                MAPPER.createObjectNode().put("errorCode", code.name()).put("msg", String.valueOf(msg))));
        streamBus.publish(new StreamEvent(task.taskId(), "error",
                MAPPER.createObjectNode().put("errorCode", code.name()).put("msg", String.valueOf(msg))));
    }

    private void suspendTask(TaskInstance task) {
        if (yieldToCancelFlow(task.taskId(), task)) {
            return;
        }
        TaskInstance base = taskRepo.findByTaskId(task.taskId()).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.SUSPEND))
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(task.taskId(), TaskEventType.SUSPEND, null));
        streamBus.publish(new StreamEvent(task.taskId(), "progress",
                MAPPER.createObjectNode().put("status", TaskEventType.SUSPEND.name())));
    }

    private void cancelTask(TaskInstance task, TaskHandler handler, TaskExecutionContext ctx) {
        String taskId = task.taskId();
        // REQUEST_CANCEL：RUNNING/SUSPENDED → CANCELING（取消 API 可能已抢先迁移，幂等跳过）
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(task);
        if (base.status() != TaskStatus.CANCELING) {
            taskRepo.save(base.withStatus(
                    TaskStateMachine.transition(base.status(), TaskEventType.REQUEST_CANCEL)));
            eventRepo.append(event(taskId, TaskEventType.REQUEST_CANCEL, null));
        }
        handler.onCancel(ctx);
        // CANCEL：CANCELING → CANCELLED（终态）
        finalizeCancel(taskId, taskRepo.findByTaskId(taskId).orElse(base));
    }

    /** CANCELING → CANCELLED（终态）：cancelTask 与让位路径共用。 */
    private void finalizeCancel(String taskId, TaskInstance canceling) {
        TaskInstance cancelled = canceling
                .withStatus(TaskStateMachine.transition(canceling.status(), TaskEventType.CANCEL))
                .withClearLease();
        taskRepo.save(cancelled);
        eventRepo.append(event(taskId, TaskEventType.CANCEL, null));
        streamBus.publish(new StreamEvent(taskId, "done",
                MAPPER.createObjectNode().put("status", "CANCELLED")));
    }

    /**
     * 控制面取消流程已介入时 worker 让位：CANCELING 代为收尾到 CANCELLED
     * （取消请求到达时步骤已结束、不再有边界检查点），已 CANCELLED 直接让位。
     * 返回 true 表示取消流程已接管，调用方跳过本次保存与后续动作。
     */
    private boolean yieldToCancelFlow(String taskId, TaskInstance snapshot) {
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(snapshot);
        if (base.status() == TaskStatus.CANCELLED) {
            return true;
        }
        if (base.status() == TaskStatus.CANCELING) {
            finalizeCancel(taskId, base);
            return true;
        }
        return false;
    }

    private void waitHuman(TaskInstance task, StepDef def, HumanRequiredException h) {
        String taskId = task.taskId();
        if (yieldToCancelFlow(taskId, task)) {
            return;
        }
        humanRepo.save(new HumanTask(null, taskId, def.no(), h.getKind(), h.getTitle(),
                h.getInstruction(), h.getFormSchema(), null, HumanTaskStatus.OPEN,
                null, null, null, null, 0, Instant.now()));
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.WAIT_HUMAN))
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(taskId, TaskEventType.WAIT_HUMAN,
                MAPPER.createObjectNode().put("stepNo", def.no())
                        .put("kind", h.getKind().name())
                        .put("title", h.getTitle())));
        streamBus.publish(new StreamEvent(taskId, "progress",
                MAPPER.createObjectNode().put("status", "WAITING_HUMAN")
                        .put("stepNo", def.no())));
    }

    private void completeTask(TaskInstance task, String resultRef) {
        String taskId = task.taskId();
        if (yieldToCancelFlow(taskId, task)) {
            return;
        }
        TaskInstance base = taskRepo.findByTaskId(taskId).orElse(task);
        TaskInstance updated = base
                .withStatus(TaskStateMachine.transition(base.status(), TaskEventType.COMPLETE))
                .withProgress(100)
                .withResultRef(resultRef)
                .withClearLease();
        taskRepo.save(updated);
        eventRepo.append(event(taskId, TaskEventType.COMPLETE,
                resultRef == null ? null : MAPPER.createObjectNode().put("resultRef", resultRef)));
        progressPort.publish(taskId, 100, null);
        streamBus.publish(new StreamEvent(taskId, "done",
                MAPPER.createObjectNode().put("status", "COMPLETED")
                        .put("resultRef", String.valueOf(resultRef))));
    }

    /** 已决人工接管点的表单值并入上下文（RESOLVED INPUT → 字段平铺）。 */
    private Map<String, JsonNode> humanInputs(String taskId) {
        Map<String, JsonNode> inputs = new HashMap<>();
        for (HumanTask ht : humanRepo.findByTaskId(taskId)) {
            if (ht.status() == HumanTaskStatus.RESOLVED && ht.kind() == HumanTaskKind.INPUT
                    && ht.formValue() != null) {
                ht.formValue().fields().forEachRemaining(e -> inputs.put(e.getKey(), e.getValue()));
            }
        }
        return inputs;
    }

    /** AGENT 动态注册步骤并入执行队列（同 no 幂等）。 */
    private void mergeRegistered(TaskExecutionContext ctx, List<StepDef> plan) {
        for (StepDef registered : ctx.registeredSteps()) {
            if (plan.stream().noneMatch(p -> p.no() == registered.no())) {
                plan.add(registered);
            }
        }
        plan.sort(Comparator.comparingInt(StepDef::no));
    }

    private boolean validJson(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private TaskEvent event(String taskId, TaskEventType type, JsonNode detail) {
        return new TaskEvent(taskId, type, ActorType.WORKER, workerId, detail, Instant.now());
    }

    private JsonNode stepDetail(StepDef def) {
        return MAPPER.createObjectNode().put("stepNo", def.no()).put("stepType", def.type());
    }
}
