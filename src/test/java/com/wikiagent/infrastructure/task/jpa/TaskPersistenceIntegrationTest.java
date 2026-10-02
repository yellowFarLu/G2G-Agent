package com.wikiagent.infrastructure.task.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 4 JPA 持久化集成测试：真实执行 Flyway V1..V9（H2 MODE=MySQL），
 * 验证端口适配器的幂等/CAS 语义。ddl-auto=none，schema 全部来自迁移脚本。
 * 注意：H2 是持久文件库，测试 ID 必须每次运行唯一，避免跨运行数据残留。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TaskPersistenceIntegrationTest {

    /** 每次调用唯一的短后缀（8 位十六进制）。 */
    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Autowired
    private TaskRepositoryPort taskRepository;

    @Autowired
    private TaskStepRepositoryPort stepRepository;

    @Autowired
    private TaskEventRepositoryPort eventRepository;

    @Autowired
    private HumanTaskRepositoryPort humanTaskRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    private TaskInstance pendingTask(String taskId, String bizKey) {
        return new TaskInstance(taskId, "INGEST", bizKey, TaskStatus.PENDING,
                mapper.createObjectNode().put("docId", "d1"), 0, 3, 0, null, null, null,
                null, "user-1", null, null, null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now());
    }

    @Test
    void saveAndFindByBizKey() {
        String taskId = "tsk_t4_" + uid() + "_1";
        String bizKey = "biz-uk-1-" + uid();
        taskRepository.save(pendingTask(taskId, bizKey));
        Optional<TaskInstance> found = taskRepository.findByBizKey(bizKey);
        assertThat(found).isPresent();
        assertThat(found.get().taskId()).isEqualTo(taskId);
        assertThat(found.get().status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void duplicateBizKeyRejected() {
        String bizKey = "biz-uk-dup-" + uid();
        taskRepository.save(pendingTask("tsk_t4_" + uid() + "_2", bizKey));
        assertThatThrownBy(() -> taskRepository.save(pendingTask("tsk_t4_" + uid() + "_3", bizKey)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void casLeaseOnlyFirstWins() {
        String taskId = "tsk_t4_" + uid() + "_4";
        taskRepository.save(pendingTask(taskId, "biz-lease-1-" + uid()));
        Instant expire = Instant.now().plusSeconds(30);
        boolean first = taskRepository.casLease(taskId, "worker-a", expire);
        boolean second = taskRepository.casLease(taskId, "worker-b", expire);
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(taskRepository.findByTaskId(taskId))
                .isPresent().get()
                .extracting(TaskInstance::leaseOwner).isEqualTo("worker-a");
    }

    @Test
    void saveAllIfAbsentIsIdempotent() {
        String taskId = "tsk_t4_" + uid() + "_5";
        List<StepDef> defs = List.of(
                StepDef.of(0, "PARSE", "解析"),
                StepDef.of(1, "CLEAN", "清洗"));
        stepRepository.saveAllIfAbsent(taskId, defs);
        stepRepository.saveAllIfAbsent(taskId, defs);
        assertThat(stepRepository.findByTaskIdOrderByStepNo(taskId)).hasSize(2);
        assertThat(stepRepository.firstNonDoneStepNo(taskId)).isEqualTo(0);
        stepRepository.markDone(taskId, 0, "{\"p\":1}", Instant.now());
        assertThat(stepRepository.firstNonDoneStepNo(taskId)).isEqualTo(1);
    }

    @Test
    void eventsKeepInsertionOrder() {
        String taskId = "tsk_t4_" + uid() + "_6";
        eventRepository.append(new TaskEvent(taskId, TaskEventType.SUBMIT,
                ActorType.USER, "user-1", mapper.createObjectNode().put("k", 1), Instant.now()));
        eventRepository.append(new TaskEvent(taskId, TaskEventType.DISPATCH,
                ActorType.SYSTEM, null, mapper.createObjectNode().put("k", 2), Instant.now()));
        List<TaskEvent> events = eventRepository.findByTaskId(taskId);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventType()).isEqualTo(TaskEventType.SUBMIT);
        assertThat(events.get(1).eventType()).isEqualTo(TaskEventType.DISPATCH);
    }

    @Test
    void casClaimOnlyFirstWins() {
        String taskId = "tsk_t4_" + uid() + "_7";
        HumanTask ht = new HumanTask(null, taskId, 1, HumanTaskKind.INPUT,
                "补全表单", "说明", mapper.createObjectNode(), null,
                HumanTaskStatus.OPEN, null, null, null, null, 0, Instant.now());
        HumanTask saved = humanTaskRepository.save(ht);
        boolean firstClaim = humanTaskRepository.casClaim(saved.id(), "alice", 0);
        boolean secondClaim = humanTaskRepository.casClaim(saved.id(), "bob", 0);
        assertThat(firstClaim).isTrue();
        assertThat(secondClaim).isFalse();
        assertThat(humanTaskRepository.findById(saved.id()))
                .isPresent().get()
                .extracting(HumanTask::status).isEqualTo(HumanTaskStatus.CLAIMED);
        assertThat(humanTaskRepository.findById(saved.id()))
                .isPresent().get()
                .extracting(HumanTask::claimedBy).isEqualTo("alice");
    }

    // ⑫ renewLease：仅本 worker + RUNNING 才续期（返回 true）；他人/非 RUNNING 返回 false
    @Test
    void renewLeaseOnlyOwnerAndRunning() {
        String taskId = "tsk_t4_" + uid() + "_8";
        taskRepository.save(pendingTask(taskId, "biz-renew-" + uid()));
        Instant expire = Instant.now().plusSeconds(30);
        assertThat(taskRepository.casLease(taskId, "worker-a", expire)).isTrue();

        // RUNNING 由 casLease 后 save 迁移（模拟 worker 领取流程）
        TaskInstance t = taskRepository.findByTaskId(taskId).orElseThrow();
        taskRepository.save(t.withStatus(TaskStatus.RUNNING));

        Instant newExpire = Instant.now().plusSeconds(60);
        assertThat(taskRepository.renewLease(taskId, "worker-a", newExpire, Instant.now())).isTrue();
        TaskInstance renewed = taskRepository.findByTaskId(taskId).orElseThrow();
        assertThat(renewed.leaseExpireAt()).isAfterOrEqualTo(newExpire.minusSeconds(1));
        assertThat(renewed.heartbeatAt()).isNotNull();

        // 非 owner 续期 → false，leaseExpireAt 不被覆盖
        assertThat(taskRepository.renewLease(taskId, "worker-b",
                Instant.now().plusSeconds(120), Instant.now())).isFalse();
        assertThat(taskRepository.findByTaskId(taskId).orElseThrow().leaseExpireAt())
                .isEqualTo(renewed.leaseExpireAt());

        // 任务已完成 → false
        taskRepository.save(taskRepository.findByTaskId(taskId).orElseThrow().withStatus(TaskStatus.COMPLETED));
        assertThat(taskRepository.renewLease(taskId, "worker-a",
                Instant.now().plusSeconds(120), Instant.now())).isFalse();
    }
}
