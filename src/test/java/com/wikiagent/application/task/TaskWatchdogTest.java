package com.wikiagent.application.task;

import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9 看门狗测试：仍 RUNNING + owner 匹配 + 心跳停更 → 复用 reclaim；
 * 心跳新鲜 / owner 已变 / 非 RUNNING → 无动作（防重复执行双保险）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TaskWatchdogTest {

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Autowired
    private TaskWatchdog watchdog;

    @Autowired
    private TaskRepositoryPort repo;

    private TaskInstance runningTask(String taskId, String owner, Instant heartbeatAt) {
        return new TaskInstance(taskId, "T9DOG", "biz-" + uid(), TaskStatus.RUNNING,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                1, 3, 0, null, null, null,
                null, "user-1", null, Instant.now(), owner,
                Instant.now().plusSeconds(30), heartbeatAt, Instant.now(), null, 0,
                Instant.now(), Instant.now());
    }

    // ① 心跳停更且 owner 匹配 → 触发 reclaim（RETRY 回 PENDING）
    @Test
    void staleHeartbeatTriggersReclaim() {
        String taskId = "tsk_t9dog_" + uid();
        repo.save(runningTask(taskId, "w-owner-1", Instant.now().minusSeconds(60)));

        watchdog.check(taskId, "w-owner-1");

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.PENDING);
    }

    // ② 心跳新鲜 → 无动作
    @Test
    void freshHeartbeatNoop() {
        String taskId = "tsk_t9dog_" + uid();
        repo.save(runningTask(taskId, "w-owner-1", Instant.now().minusSeconds(1)));

        watchdog.check(taskId, "w-owner-1");

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.RUNNING);
    }

    // ③ owner 已变（任务已被他人重新领取）→ 无动作
    @Test
    void ownerMismatchNoop() {
        String taskId = "tsk_t9dog_" + uid();
        repo.save(runningTask(taskId, "w-owner-1", Instant.now().minusSeconds(60)));

        watchdog.check(taskId, "w-owner-2");

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.RUNNING);
    }

    // ④ 非 RUNNING（消息迟到，任务已回 PENDING）→ 无动作
    @Test
    void nonRunningNoop() {
        String taskId = "tsk_t9dog_" + uid();
        TaskInstance running = runningTask(taskId, "w-owner-1", Instant.now().minusSeconds(60));
        repo.save(running.withStatus(TaskStatus.PENDING));

        watchdog.check(taskId, "w-owner-1");

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.PENDING);
    }
}
