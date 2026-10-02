package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 投递补偿测试：滞留 PENDING 任务重投、同租户超限跳过、未到期任务不动。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.task.tenant-max-concurrent=1"
})
class DispatchCompensationJobTest {

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @TestConfiguration
    static class FakeDispatcherConfig {
        @Bean
        @Primary
        TaskDispatcherPort recordingDispatcher() {
            return RECORDING;
        }
    }

    /**
     * 静态实例：I3 背压 BeanPostProcessor 可能对 TaskDispatcherPort 做 JDK 动态代理，
     * 注入的 Bean 强转回实现类会 ClassCastException；测试直接持有原始实例。
     */
    static final RecordingDispatcher RECORDING = new RecordingDispatcher();

    @org.junit.jupiter.api.BeforeEach
    void resetRecording() {
        RECORDING.dispatched.clear();
        RECORDING.watchdogs.clear();
    }

    static class RecordingDispatcher implements TaskDispatcherPort {
        final List<String> dispatched = new CopyOnWriteArrayList<>();
        final List<String> watchdogs = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(String taskId, String taskType, int delayLevel) {
            dispatched.add(taskId + ":" + taskType + ":" + delayLevel);
        }

        @Override
        public void dispatchWatchdog(String taskId, String ownerWorkerId, int delayLevel) {
            watchdogs.add(taskId + ":" + ownerWorkerId + ":" + delayLevel);
        }
    }

    @Autowired
    private TaskRepositoryPort taskRepository;

    @Autowired
    private DispatchCompensationJob job;

    @Autowired
    private TaskDispatcherPort dispatcher;

    private final ObjectMapper mapper = new ObjectMapper();

    private TaskInstance task(String taskId, TaskStatus status, String tenantId,
                              Instant nextRunAt, Instant enqueueAt) {
        return new TaskInstance(taskId, "INGEST", "biz-" + uid(), status,
                mapper.createObjectNode().put("docId", "d1"), 0, 3, 0, null, null, null,
                null, "user-1", tenantId, enqueueAt, null, null, null,
                nextRunAt, null, 0, Instant.now(), Instant.now());
    }

    @Test
    void stuckPendingTaskIsRedispatchedAndMarkedEnqueued() {
        RecordingDispatcher rec = RECORDING;
        String taskId = "tsk_t7_" + uid();
        taskRepository.save(task(taskId, TaskStatus.PENDING, "tn-a-" + uid(),
                Instant.now().minusSeconds(60), null));

        job.runOnce();

        assertThat(rec.dispatched).contains(taskId + ":INGEST:0");
        assertThat(taskRepository.findByTaskId(taskId))
                .isPresent().get()
                .extracting(TaskInstance::enqueueAt).isNotNull();
    }

    @Test
    void tenantAtConcurrencyLimitIsSkipped() {
        RecordingDispatcher rec = RECORDING;
        String tenant = "tn-b-" + uid();
        taskRepository.save(task("tsk_t7_run_" + uid(), TaskStatus.RUNNING, tenant,
                Instant.now(), Instant.now()));
        String pendingId = "tsk_t7_" + uid();
        taskRepository.save(task(pendingId, TaskStatus.PENDING, tenant,
                Instant.now().minusSeconds(60), null));

        job.runOnce();

        assertThat(rec.dispatched).noneMatch(entry -> entry.startsWith(pendingId + ":"));
    }

    @Test
    void futureTaskIsNotDispatched() {
        RecordingDispatcher rec = RECORDING;
        String taskId = "tsk_t7_" + uid();
        taskRepository.save(task(taskId, TaskStatus.PENDING, "tn-c-" + uid(),
                Instant.now().plusSeconds(600), null));

        job.runOnce();

        assertThat(rec.dispatched).noneMatch(entry -> entry.startsWith(taskId + ":"));
    }
}
