package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9 崩溃恢复扫描测试：租约过期回收 → RETRY 退避重投；重试耗尽 → FAILED；新鲜租约不动。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TaskRecoveryJobTest {

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        @Primary
        TaskDispatcherPort recordingDispatcher() {
            return RECORDING;
        }
    }

    /** I1 trace 代理会把注入 Bean 包成 JDK 代理，测试直接持有原始实例。 */
    static final RecordingDispatcher RECORDING = new RecordingDispatcher();

    @org.junit.jupiter.api.BeforeEach
    void resetRecording() {
        RECORDING.dispatched.clear();
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

    @Autowired
    private TaskRecoveryJob job;

    @Autowired
    private TaskRepositoryPort repo;

    @Autowired
    private TaskEventRepositoryPort eventRepo;

    @Autowired
    private TaskDispatcherPort dispatcher;

    private final ObjectMapper mapper = new ObjectMapper();

    private TaskInstance runningTask(String taskId, String type, int attempt, Instant leaseExpireAt) {
        return new TaskInstance(taskId, type, "biz-" + uid(), TaskStatus.RUNNING,
                mapper.createObjectNode().put("docId", "d1"), attempt, 3, 0, null, null, null,
                null, "user-1", null, Instant.now(), "w1", leaseExpireAt,
                Instant.now().minusSeconds(20), Instant.now(), null, 0,
                Instant.now(), Instant.now());
    }

    // ① 租约过期 + attempt=1 → RETRY 回 PENDING、attempt=2、按 30s 档（level 4）重投
    @Test
    void recoversExpiredLeaseWithRetry() {
        String taskId = "tsk_t9rec_" + uid();
        repo.save(runningTask(taskId, "T9REC", 1, Instant.now().minusSeconds(10)));
        RecordingDispatcher rec = RECORDING;

        job.runOnce();

        Optional<TaskInstance> task = repo.findByTaskId(taskId);
        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.get().attempt()).isEqualTo(2);
        assertThat(rec.dispatched).contains(taskId + ":T9REC:4");
        assertThat(eventRepo.findByTaskId(taskId))
                .extracting(TaskEvent::eventType)
                .contains(TaskEventType.RETRY);
    }

    // ② attempt 已达 max → FAILED + FAIL 事件，不再投递
    @Test
    void failsWhenRetriesExhausted() {
        String taskId = "tsk_t9rec_" + uid();
        repo.save(runningTask(taskId, "T9REC", 3, Instant.now().minusSeconds(10)));
        RecordingDispatcher rec = RECORDING;
        int before = rec.dispatched.size();

        job.runOnce();

        Optional<TaskInstance> task = repo.findByTaskId(taskId);
        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(eventRepo.findByTaskId(taskId))
                .extracting(TaskEvent::eventType)
                .contains(TaskEventType.FAIL);
        assertThat(rec.dispatched.size()).isEqualTo(before);
    }

    // ③ 租约未过期的 RUNNING 不动
    @Test
    void leavesFreshLeaseAlone() {
        String taskId = "tsk_t9rec_" + uid();
        repo.save(runningTask(taskId, "T9REC", 1, Instant.now().plusSeconds(30)));

        job.runOnce();

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.RUNNING);
    }
}
