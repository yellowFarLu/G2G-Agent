package com.wikiagent.application.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.rule.ReviewCase;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #7 复核闸门并发回归：两 reviewer 同时处置同一任务最后两个 OPEN 案件。
 * <p>
 * H2 文件库（MODE=MySQL，两连接；默认 READ_COMMITTED）下 SELECT … FOR UPDATE
 * 行锁等待同样能将两个 dispose 串行化，断言：human_task RESOLVED 恰好一次、
 * 任务最终 PENDING（修复前可能两边都判定"仍有 OPEN"而永久 WAITING_HUMAN）。
 * TaskDispatcherPort 桩替换，避免本地投递造成状态竞态。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class ReviewGateConcurrencyIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 每类独立 H2 文件（nanoTime 唯一后缀），放宽 LOCK_TIMEOUT 等待行锁。 */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-review-gate-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @MockBean
    private TaskDispatcherPort dispatcher;

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Autowired
    private HumanTaskRepositoryPort humanRepo;

    @Autowired
    private ReviewCaseService reviewCaseService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentDisposeOfLastTwoCasesResolvesExactlyOnce() throws Exception {
        String taskId = "review-gate-c-" + uid();
        String docId = "doc-gate-c-" + uid();
        insertWaitingHumanTask(taskId);

        ReviewCase c1 = reviewCaseService.createLowConfidence(docId, 1, "f1",
                "MODEL", 0.4, "低置信1", taskId);
        ReviewCase c2 = reviewCaseService.createLowConfidence(docId, 1, "f2",
                "MODEL", 0.3, "低置信2", taskId);

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> guard(start, error,
                    () -> reviewCaseService.dispose(c1.id(), ReviewCase.ReviewAction.APPROVE,
                            null, "reviewer-a")));
            Future<?> f2 = pool.submit(() -> guard(start, error,
                    () -> reviewCaseService.dispose(c2.id(), ReviewCase.ReviewAction.APPROVE,
                            null, "reviewer-b")));
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(error.get()).as("并发处置不得有异常逸出").isNull();

        Integer resolved = jdbc.queryForObject("""
                select count(*) from human_task
                where task_id = ? and kind = 'REVIEW' and status = 'RESOLVED'
                """, Integer.class, taskId);
        assertThat(resolved).as("REVIEW 人工任务必须恰好 RESOLVED 一次").isEqualTo(1);

        Integer stillOpen = jdbc.queryForObject(
                "select count(*) from review_case where task_id = ? and status = 'OPEN'",
                Integer.class, taskId);
        assertThat(stillOpen).isZero();

        assertThat(taskRepo.findByTaskId(taskId).orElseThrow().status())
                .as("最后一个案件处置后任务必须恢复 PENDING，不得卡 WAITING_HUMAN")
                .isEqualTo(TaskStatus.PENDING);
        assertThat(humanRepo.findByTaskId(taskId).stream()
                .filter(h -> h.kind() == HumanTaskKind.REVIEW)
                .findFirst().orElseThrow().status())
                .isEqualTo(HumanTaskStatus.RESOLVED);
    }

    private void guard(CountDownLatch start, AtomicReference<Throwable> error, Runnable action) {
        try {
            start.await();
            action.run();
        } catch (Throwable t) {
            error.compareAndSet(null, t);
        }
    }

    private void insertWaitingHumanTask(String taskId) {
        taskRepo.save(new TaskInstance(taskId, "T-GATE", "biz-" + uid(),
                TaskStatus.WAITING_HUMAN,
                JSON.createObjectNode(), 0, 3, 0, null, null, null,
                null, "user-1", null, Instant.now(), null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now()));
        humanRepo.save(new HumanTask(null, taskId, 110, HumanTaskKind.REVIEW, "需要人工复核", "说明",
                null, null, HumanTaskStatus.OPEN, null, null, null, null,
                0, Instant.now()));
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
