package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskPayload;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 9 提交服务测试：幂等 bizKey、dispatch 失败 outbox 兜底、TaskIds 唯一性、终态重放。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TaskSubmissionServiceTest {

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
        volatile boolean failNext;

        @Override
        public void dispatch(String taskId, String taskType, int delayLevel) {
            if (failNext) {
                throw new IllegalStateException("模拟 MQ 不可用");
            }
            dispatched.add(taskId + ":" + taskType + ":" + delayLevel);
        }

        @Override
        public void dispatchWatchdog(String taskId, String ownerWorkerId, int delayLevel) {
            dispatched.add(taskId + ":watchdog:" + ownerWorkerId);
        }
    }

    @Autowired
    private TaskSubmissionService service;

    @Autowired
    private TaskRepositoryPort repo;

    @Autowired
    private TaskEventRepositoryPort eventRepo;

    @Autowired
    private TaskDispatcherPort dispatcher;

    private final ObjectMapper mapper = new ObjectMapper();

    // ① 正常提交：tsk_ 前缀、PENDING、立即 dispatch(level 0)、SUBMIT 事件（actor=USER）
    @Test
    void submitAssignsIdAndDispatches() {
        TaskPayload p = new TaskPayload("T9SUB", "biz-sub-1-" + uid(), "user-1", null, null,
                mapper.createObjectNode().put("docId", "d1"), null, null);

        TaskInstance t = service.submit(p);

        assertThat(t.taskId()).startsWith("tsk_");
        assertThat(t.status()).isEqualTo(TaskStatus.PENDING);
        RecordingDispatcher rec = (RecordingDispatcher) dispatcher;
        assertThat(rec.dispatched).contains(t.taskId() + ":T9SUB:0");
        TaskEvent submitEvent = eventRepo.findByTaskId(t.taskId()).stream()
                .filter(e -> e.eventType() == TaskEventType.SUBMIT).findFirst().orElseThrow();
        assertThat(submitEvent.actorType()).isEqualTo(ActorType.USER);
        assertThat(submitEvent.actorId()).isEqualTo("user-1");
    }

    // ② 同 bizKey 重复提交：返回既有任务、不重复投递
    @Test
    void duplicateBizKeyReturnsSameTask() {
        String bizKey = "biz-sub-dup-" + uid();
        TaskPayload p = new TaskPayload("T9SUB", bizKey, "user-1", null, null,
                mapper.createObjectNode(), null, null);
        TaskInstance first = service.submit(p);
        RecordingDispatcher rec = (RecordingDispatcher) dispatcher;
        int dispatchedAfterFirst = (int) rec.dispatched.stream()
                .filter(d -> d.startsWith(first.taskId() + ":")).count();

        TaskInstance second = service.submit(p);

        assertThat(second.taskId()).isEqualTo(first.taskId());
        int dispatchedForTask = (int) rec.dispatched.stream()
                .filter(d -> d.startsWith(first.taskId() + ":")).count();
        assertThat(dispatchedForTask).isEqualTo(dispatchedAfterFirst);
    }

    // ③ dispatcher 抛异常：提交仍成功，任务保持 PENDING（outbox 补偿兜底）
    @Test
    void dispatchFailureStillSubmits() {
        TaskPayload p = new TaskPayload("T9SUB", "biz-sub-fail-" + uid(), "user-1", null, null,
                mapper.createObjectNode(), null, null);
        RecordingDispatcher rec = (RecordingDispatcher) dispatcher;
        rec.failNext = true;
        try {
            TaskInstance t = service.submit(p);
            assertThat(t.status()).isEqualTo(TaskStatus.PENDING);
            assertThat(repo.findByTaskId(t.taskId())).isPresent().get()
                    .extracting(TaskInstance::status).isEqualTo(TaskStatus.PENDING);
        } finally {
            rec.failNext = false;
        }
    }

    // ④ 无 bizKey 无 idempotencyKey：自动生成且互不冲突
    @Test
    void autoGeneratesNonConflictingBizKey() {
        TaskPayload p1 = new TaskPayload("T9SUB", null, "user-1", null, null,
                mapper.createObjectNode(), null, null);
        TaskPayload p2 = new TaskPayload("T9SUB", null, "user-1", null, null,
                mapper.createObjectNode(), null, null);

        TaskInstance t1 = service.submit(p1);
        TaskInstance t2 = service.submit(p2);

        assertThat(t1.taskId()).isNotEqualTo(t2.taskId());
        assertThat(t1.bizKey()).startsWith("T9SUB:");
        assertThat(t2.bizKey()).startsWith("T9SUB:");
        assertThat(t1.bizKey()).isNotEqualTo(t2.bizKey());
    }

    // ⑤ TaskIds 连续生成 1000 个无重复
    @Test
    void taskIdsUniqueInBulk() {
        Set<String> ids = IntStream.range(0, 1000).mapToObj(i -> TaskIds.next()).collect(java.util.stream.Collectors.toSet());
        assertThat(ids).hasSize(1000);
        assertThat(ids).allMatch(id -> id.startsWith("tsk_"));
    }

    private TaskInstance failedTask(String taskId, String type) {
        return new TaskInstance(taskId, type, "biz-" + uid(), TaskStatus.FAILED,
                mapper.createObjectNode(), 3, 3, 0, null, ErrorCode.INTERNAL, "boom",
                null, "user-1", null, Instant.now(), null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now());
    }

    // ⑥ replay FAILED 任务 → PENDING、attempt=0、错误字段清空、REPLAY 事件（actor=USER）、重新投递
    @Test
    void replayFailedTaskResetsAndRedispatches() {
        String taskId = "tsk_t9replay_" + uid();
        repo.save(failedTask(taskId, "T9SUB"));
        RecordingDispatcher rec = (RecordingDispatcher) dispatcher;
        int before = rec.dispatched.size();

        TaskInstance replayed = service.replay(taskId, "admin-1");

        assertThat(replayed.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(replayed.attempt()).isZero();
        assertThat(replayed.errorCode()).isNull();
        assertThat(replayed.errorMsg()).isNull();
        Optional<TaskEvent> replayEvent = eventRepo.findByTaskId(taskId).stream()
                .filter(e -> e.eventType() == TaskEventType.REPLAY).findFirst();
        assertThat(replayEvent).isPresent();
        assertThat(replayEvent.get().actorType()).isEqualTo(ActorType.USER);
        assertThat(replayEvent.get().actorId()).isEqualTo("admin-1");
        assertThat(rec.dispatched).contains(taskId + ":T9SUB:0");
        assertThat(rec.dispatched.size()).isGreaterThan(before);
    }

    // ⑦ 非终态（RUNNING）不可重放
    @Test
    void replayNonTerminalRejected() {
        String taskId = "tsk_t9replay_" + uid();
        TaskInstance running = failedTask(taskId, "T9SUB").withStatus(TaskStatus.RUNNING);
        repo.save(running);

        assertThatThrownBy(() -> service.replay(taskId, "admin-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ⑧ 并发同 bizKey 提交：一方撞 uk_biz_key 后回查返回既有任务（不抛出 500）
    @Test
    void concurrentSameBizKeyFallsBackToExisting() {
        String bizKey = "biz-conc-" + uid();
        TaskPayload p = new TaskPayload("T9SUB", bizKey, "user-1", null, null,
                mapper.createObjectNode(), null, null);
        TaskInstance first = service.submit(p);

        // 直接 save 同 bizKey 模拟并发撞库，再经 submit 回查
        TaskInstance concurrent = new TaskInstance(TaskIds.next(), "T9SUB", bizKey, TaskStatus.PENDING,
                mapper.createObjectNode(), 0, 3, 0, null, null, null,
                null, "user-1", null, Instant.now(), null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now());
        assertThatThrownBy(() -> repo.save(concurrent))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        TaskInstance returned = service.submit(p);
        assertThat(returned.taskId()).isEqualTo(first.taskId());
    }
}
