package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.RetryableTaskException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.ControlFlagPort;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 TaskWorker 执行器测试：规格 3.1 全流程（租约/心跳/步骤循环/超时/重试/控制响应/断点续跑）。
 * fake handler 手动注册 registry；重投通过 fake dispatcher 记录 + 手动 onMessage 模拟延迟消息。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TaskWorkerTest {

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        @Primary
        TaskDispatcherPort recordingDispatcher() {
            return new RecordingDispatcher();
        }
    }

    static class RecordingDispatcher implements TaskDispatcherPort {
        final List<String> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(String taskId, String taskType, int delayLevel) {
            dispatched.add(taskId + ":" + taskType + ":" + delayLevel);
        }

        @Override
        public void dispatchWatchdog(String taskId, String ownerWorkerId, int delayLevel) {
            dispatched.add(taskId + ":watchdog:" + ownerWorkerId);
        }
    }

    /** fake handler：按 stepNo 行为表执行，逐步计数，支持暂停钩子与取消记录。 */
    static class FakeHandler implements TaskHandler {
        final String type;
        final List<StepDef> plan;
        final BiFunction<Integer, com.wikiagent.domain.task.TaskExecutionContext, StepResult> behavior;
        final Map<Integer, AtomicInteger> counts = new HashMap<>();
        final AtomicInteger cancelCount = new AtomicInteger();
        volatile boolean sleepOnEveryStep;

        FakeHandler(String type, List<StepDef> plan,
                    BiFunction<Integer, com.wikiagent.domain.task.TaskExecutionContext, StepResult> behavior) {
            this.type = type;
            this.plan = plan;
            this.behavior = behavior;
        }

        int countOf(int stepNo) {
            return counts.computeIfAbsent(stepNo, k -> new AtomicInteger()).get();
        }

        void touch(int stepNo) {
            counts.computeIfAbsent(stepNo, k -> new AtomicInteger()).incrementAndGet();
            if (sleepOnEveryStep) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public String taskType() {
            return type;
        }

        @Override
        public List<StepDef> planSteps(com.fasterxml.jackson.databind.JsonNode payloadArgs) {
            return plan;
        }

        @Override
        public StepResult executeStep(com.wikiagent.domain.task.TaskExecutionContext ctx)
                throws RetryableTaskException, com.wikiagent.domain.task.FatalTaskException, HumanRequiredException {
            return behavior.apply(ctx.currentStepNo(), ctx);
        }

        @Override
        public void onCancel(com.wikiagent.domain.task.TaskExecutionContext ctx) {
            cancelCount.incrementAndGet();
        }
    }

    @Autowired
    private TaskWorker worker;

    @Autowired
    private TaskHandlerRegistry registry;

    @Autowired
    private TaskRepositoryPort repo;

    @Autowired
    private TaskStepRepositoryPort stepRepo;

    @Autowired
    private TaskEventRepositoryPort eventRepo;

    @Autowired
    private HumanTaskRepositoryPort humanRepo;

    @Autowired
    private ControlFlagPort controlPort;

    @Autowired
    private TaskDispatcherPort dispatcher;

    private final ObjectMapper mapper = new ObjectMapper();

    private TaskInstance pendingTask(String taskId, String type) {
        return new TaskInstance(taskId, type, "biz-" + uid(), TaskStatus.PENDING,
                mapper.createObjectNode().put("docId", "d1"), 0, 3, 0, null, null, null,
                null, "user-1", null, null, null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now());
    }

    private TaskInstance withStatus(TaskInstance t, TaskStatus status) {
        return new TaskInstance(t.taskId(), t.taskType(), t.bizKey(), status, t.payload(),
                t.attempt(), t.maxAttempts(), t.progressPercent(), t.resultRef(), t.errorCode(),
                t.errorMsg(), t.idempotencyKey(), t.submittedBy(), t.tenantId(), t.enqueueAt(),
                t.leaseOwner(), t.leaseExpireAt(), t.heartbeatAt(), t.nextRunAt(), t.suspendReason(),
                t.controlVersion(), t.createdAt(), Instant.now());
    }

    // ① happy path
    @Test
    void happyPathCompletesAllSteps() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8HAPPY"));
        FakeHandler handler = new FakeHandler("T8HAPPY",
                List.of(StepDef.of(1, "A", "步骤一"), StepDef.of(2, "B", "步骤二"), StepDef.of(3, "C", "步骤三")),
                (no, ctx) -> {
                    handlerRef.get().touch(no);
                    return StepResult.done(no * 30 + 10, no == 3 ? "ref-1" : null);
                });
        handlerRef.set(handler);
        registry.register(handler);

        worker.onMessage(taskId, "T8HAPPY");

        Optional<TaskInstance> task = repo.findByTaskId(taskId);
        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.get().progressPercent()).isEqualTo(100);
        assertThat(task.get().resultRef()).isEqualTo("ref-1");
        assertThat(handler.countOf(1)).isEqualTo(1);
        assertThat(handler.countOf(2)).isEqualTo(1);
        assertThat(handler.countOf(3)).isEqualTo(1);
        List<TaskEventType> events = eventRepo.findByTaskId(taskId).stream()
                .map(com.wikiagent.domain.task.TaskEvent::eventType).toList();
        assertThat(events).containsSubsequence(TaskEventType.LEASE,
                TaskEventType.STEP_START, TaskEventType.STEP_START, TaskEventType.STEP_START,
                TaskEventType.COMPLETE);
    }

    private final AtomicReference<FakeHandler> handlerRef = new AtomicReference<>();

    // ② 状态护栏
    @Test
    void nonLeasableStatusIsSkipped() {
        String taskId = "tsk_t8_" + uid();
        repo.save(withStatus(pendingTask(taskId, "T8GUARD"), TaskStatus.RUNNING));
        FakeHandler handler = new FakeHandler("T8GUARD",
                List.of(StepDef.of(1, "A", "s1")), (no, ctx) -> StepResult.done(50));
        registry.register(handler);

        worker.onMessage(taskId, "T8GUARD");

        assertThat(handler.countOf(1)).isZero();
        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.RUNNING);
    }

    // ③ 可重试：前两次抛 TIMEOUT，第三次成功
    @Test
    void retryableFailureRetriesThenCompletes() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8RETRY"));
        AtomicInteger step1Attempts = new AtomicInteger();
        FakeHandler handler = new FakeHandler("T8RETRY",
                List.of(StepDef.of(1, "A", "s1"), StepDef.of(2, "B", "s2")),
                (no, ctx) -> {
                    handlerRef.get().touch(no);
                    if (no == 1 && step1Attempts.incrementAndGet() < 3) {
                        throw new RetryableTaskException(ErrorCode.TIMEOUT, "模拟超时");
                    }
                    return StepResult.done(no == 1 ? 50 : 100, no == 2 ? "ref-x" : null);
                });
        handlerRef.set(handler);
        registry.register(handler);
        RecordingDispatcher rec = (RecordingDispatcher) dispatcher;

        worker.onMessage(taskId, "T8RETRY");  // attempt0 失败 → RETRY → PENDING(attempt=1)
        assertThat(rec.dispatched).contains(taskId + ":T8RETRY:3");
        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.PENDING);

        worker.onMessage(taskId, "T8RETRY");  // attempt1 失败 → RETRY → PENDING(attempt=2)
        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::attempt).isEqualTo(2);

        worker.onMessage(taskId, "T8RETRY");  // attempt2 成功
        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(handler.countOf(1)).isEqualTo(3);
        assertThat(handler.countOf(2)).isEqualTo(1);
    }

    // ④ 致命失败
    @Test
    void fatalFailureFailsWithoutRetry() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8FATAL"));
        FakeHandler handler = new FakeHandler("T8FATAL",
                List.of(StepDef.of(1, "A", "s1")),
                (no, ctx) -> {
                    throw new com.wikiagent.domain.task.FatalTaskException(ErrorCode.VALIDATION_FAILED, "参数校验失败");
                });
        registry.register(handler);
        RecordingDispatcher rec = (RecordingDispatcher) dispatcher;
        int before = normalDispatchCount(rec);

        worker.onMessage(taskId, "T8FATAL");

        Optional<TaskInstance> task = repo.findByTaskId(taskId);
        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.get().errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(normalDispatchCount(rec)).isEqualTo(before);
    }

    /** 普通任务投递数（排除看门狗消息——LEASE 后固定发出，与任务重投无关）。 */
    private static int normalDispatchCount(RecordingDispatcher rec) {
        return (int) rec.dispatched.stream().filter(d -> !d.contains(":watchdog:")).count();
    }

    // ⑤ 暂停 → 恢复
    @Test
    void pauseSuspendsAndResumeCompletes() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8PAUSE"));
        FakeHandler handler = new FakeHandler("T8PAUSE",
                List.of(StepDef.of(1, "A", "s1"), StepDef.of(2, "B", "s2")),
                (no, ctx) -> {
                    handlerRef.get().touch(no);
                    return StepResult.done(no * 50);
                });
        handlerRef.set(handler);
        registry.register(handler);

        controlPort.requestPause(taskId, 0);
        worker.onMessage(taskId, "T8PAUSE");
        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.SUSPENDED);
        assertThat(handler.countOf(1)).isZero();

        // 模拟控制服务 resume：清标志 + RESUME 回 PENDING（事件由 Task 10 服务写）
        controlPort.clear(taskId);
        repo.save(withStatus(repo.findByTaskId(taskId).orElseThrow(), TaskStatus.PENDING));
        worker.onMessage(taskId, "T8PAUSE");
        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(handler.countOf(1)).isEqualTo(1);
        assertThat(handler.countOf(2)).isEqualTo(1);
    }

    // ⑥ 取消
    @Test
    void cancelRunsOnCancelOnceAndMarksCancelled() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8CANCEL"));
        FakeHandler handler = new FakeHandler("T8CANCEL",
                List.of(StepDef.of(1, "A", "s1")), (no, ctx) -> StepResult.done(50));
        registry.register(handler);

        controlPort.requestCancel(taskId, 0);
        worker.onMessage(taskId, "T8CANCEL");

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.CANCELLED);
        assertThat(handler.cancelCount.get()).isEqualTo(1);
    }

    // ⑦ HumanRequired
    @Test
    void humanRequiredCreatesOpenHumanTask() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8HUMAN"));
        FakeHandler handler = new FakeHandler("T8HUMAN",
                List.of(StepDef.of(1, "A", "s1").withHumanCheckpoint(true)),
                (no, ctx) -> {
                    throw new HumanRequiredException(HumanTaskKind.INPUT, "需要补充信息",
                            "请提供文档密级", mapper.createObjectNode().put("field", "level"));
                });
        registry.register(handler);

        worker.onMessage(taskId, "T8HUMAN");

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.WAITING_HUMAN);
        List<HumanTask> humanTasks = humanRepo.findByTaskId(taskId);
        assertThat(humanTasks).hasSize(1);
        assertThat(humanTasks.get(0).status()).isEqualTo(com.wikiagent.domain.task.HumanTaskStatus.OPEN);
        assertThat(humanTasks.get(0).kind()).isEqualTo(HumanTaskKind.INPUT);
    }

    // ⑧ 步骤超时按可重试处理（TIMEOUT）
    @Test
    void stepTimeoutIsRetryable() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8SLOW"));
        FakeHandler handler = new FakeHandler("T8SLOW",
                List.of(StepDef.of(1, "A", "s1")), (no, ctx) -> {
                    handlerRef.get().touch(no);
                    return StepResult.done(50);
                });
        handler.sleepOnEveryStep = true;
        handlerRef.set(handler);
        registry.register(handler);

        // worker 用 200ms 步骤超时（构造时通过 TaskProperties 覆盖默认）
        workerWithShortTimeout().onMessage(taskId, "T8SLOW");

        Optional<TaskInstance> task = repo.findByTaskId(taskId);
        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.get().attempt()).isEqualTo(1);
        assertThat(task.get().errorCode()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(handler.countOf(1)).isEqualTo(1);
    }

    private TaskWorker workerWithShortTimeout() {
        TaskProperties props = new TaskProperties();
        props.getDefaults().setStepTimeoutSec(0); // 0 → 由 worker 内部以测试覆盖的 200ms 兜底
        return new TaskWorker(repo, stepRepo, eventRepo, humanRepo,
                new com.wikiagent.infrastructure.task.jvm.JvmLeasePort(),
                controlPort,
                new com.wikiagent.infrastructure.task.jvm.JvmProgressPort(),
                dispatcher,
                new com.wikiagent.infrastructure.task.jvm.InProcessTaskStreamBus(),
                props, registry, new ErrorClassifier()) {
            @Override
            protected java.time.Duration stepTimeout(StepDef def) {
                return java.time.Duration.ofMillis(200);
            }
        };
    }

    // ⑨ 断点续跑
    @Test
    void resumesFromFirstNonDoneStep() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8RESUME"));
        List<StepDef> plan = List.of(StepDef.of(1, "A", "s1"), StepDef.of(2, "B", "s2"));
        FakeHandler handler = new FakeHandler("T8RESUME", plan, (no, ctx) -> {
            handlerRef.get().touch(no);
            return StepResult.done(no * 50);
        });
        handlerRef.set(handler);
        registry.register(handler);
        stepRepo.saveAllIfAbsent(taskId, plan);
        stepRepo.markDone(taskId, 1, null, Instant.now());

        worker.onMessage(taskId, "T8RESUME");

        assertThat(handler.countOf(1)).isZero();
        assertThat(handler.countOf(2)).isEqualTo(1);
        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.COMPLETED);
    }

    // ⑩ 脏 checkpoint：快速失败不再重试
    @Test
    void corruptCheckpointFailsFastWithoutDispatch() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8CORRUPT"));
        List<StepDef> plan = List.of(StepDef.of(1, "A", "s1"), StepDef.of(2, "B", "s2"));
        FakeHandler handler = new FakeHandler("T8CORRUPT", plan, (no, ctx) -> {
            handlerRef.get().touch(no);
            return StepResult.done(50);
        });
        registry.register(handler);
        stepRepo.saveAllIfAbsent(taskId, plan);
        stepRepo.markDone(taskId, 1, "{坏json", Instant.now());
        RecordingDispatcher rec = (RecordingDispatcher) dispatcher;
        int before = normalDispatchCount(rec);

        worker.onMessage(taskId, "T8CORRUPT");

        Optional<TaskInstance> task = repo.findByTaskId(taskId);
        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.get().errorCode()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(normalDispatchCount(rec)).isEqualTo(before);
        assertThat(handler.countOf(2)).isZero();
    }

    // ⑪ Redis 标志丢失 → DB 权威复核仍生效
    @Test
    void dbPauseTakesEffectWhenFlagMissing() {
        String taskId = "tsk_t8_" + uid();
        repo.save(pendingTask(taskId, "T8DBPAUSE"));
        FakeHandler handler = new FakeHandler("T8DBPAUSE",
                List.of(StepDef.of(1, "A", "s1")), (no, ctx) -> StepResult.done(50));
        registry.register(handler);
        // 不写 Redis 标志（ControlFlagPort 读为 NONE），仅经定向更新写 DB 暂停痕迹
        // （suspendReason/controlVersion 由控制面定向更新独占，taskRepo.save 不再覆盖）
        repo.updateControlTrace(taskId, "管理员暂停", controlPort.currentVersion(taskId) + 1);

        worker.onMessage(taskId, "T8DBPAUSE");

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.SUSPENDED);
        assertThat(handler.countOf(1)).isZero();
    }
}
