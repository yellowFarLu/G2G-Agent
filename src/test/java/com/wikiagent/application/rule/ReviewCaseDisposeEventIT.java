package com.wikiagent.application.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.rule.ReviewCase;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #9 复核处置 HUMAN_RESOLVE 事件去重回归。
 * <ul>
 *   <li>存在 REVIEW 人工任务（多案件闸门）：所有案件处置完毕后 HUMAN_RESOLVE
 *       恰好一条（由 HumanTaskService.resolve 写），人工任务 RESOLVED 一次；
 *       APPROVE/REJECT/EDIT 三动作均覆盖。</li>
 *   <li>无人工任务（taskId 存在但无 human_task 行）：由 ReviewCaseService
 *       自行补恰好一条 HUMAN_RESOLVE。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class ReviewCaseDisposeEventIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-review-event-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Autowired
    private HumanTaskRepositoryPort humanRepo;

    @Autowired
    private TaskEventRepositoryPort eventRepo;

    @Autowired
    private ReviewCaseService reviewCaseService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void withHumanTaskExactlyOneHumanResolveAcrossActions() {
        for (ReviewCase.ReviewAction action : ReviewCase.ReviewAction.values()) {
            assertWithHumanTask(action);
        }
    }

    private void assertWithHumanTask(ReviewCase.ReviewAction action) {
        String taskId = "review-ev-h-" + uid();
        String docId = "doc-ev-h-" + uid();
        insertWaitingHumanTask(taskId);

        ReviewCase c1 = reviewCaseService.createLowConfidence(docId, 1, "f1",
                "MODEL", 0.4, "低1", taskId);
        ReviewCase c2 = reviewCaseService.createLowConfidence(docId, 1, "f2",
                "MODEL", 0.3, "低2", taskId);

        reviewCaseService.dispose(c1.id(), ReviewCase.ReviewAction.APPROVE, null, "reviewer-a");
        List<TaskEvent> afterFirst = humanResolveEvents(taskId);
        assertThat(afterFirst).as("动作 %s：首个案件处置后仍有 OPEN 案件，不应有 HUMAN_RESOLVE",
                action).isEmpty();

        var edited = JSON.createObjectNode().put("f2", "人工值");
        reviewCaseService.dispose(c2.id(), action,
                action == ReviewCase.ReviewAction.EDIT ? edited : null, "reviewer-b");

        List<TaskEvent> events = humanResolveEvents(taskId);
        assertThat(events).as("动作 %s：闸门放行时 HUMAN_RESOLVE 必须恰好一条", action)
                .hasSize(1);
        assertThat(events.get(0).actorType()).isNotNull();

        Integer resolved = jdbc.queryForObject("""
                select count(*) from human_task
                where task_id = ? and kind = 'REVIEW' and status = 'RESOLVED'
                """, Integer.class, taskId);
        assertThat(resolved).as("动作 %s：人工任务 RESOLVED 恰好一次", action).isEqualTo(1);

        HumanTask resolvedTask = humanRepo.findByTaskId(taskId).stream()
                .filter(h -> h.kind() == HumanTaskKind.REVIEW)
                .findFirst().orElseThrow();
        assertThat(resolvedTask.status()).isEqualTo(HumanTaskStatus.RESOLVED);
        // resolve 路径以 formValue 承载 caseId/action（detail 为 null 是现状）
        assertThat(resolvedTask.formValue()).isNotNull();
        assertThat(resolvedTask.formValue()).contains("caseId").contains(action.name());
        if (action == ReviewCase.ReviewAction.EDIT) {
            assertThat(resolvedTask.formValue()).contains("editedFields");
        }

        assertThat(taskRepo.findByTaskId(taskId).orElseThrow().status())
                .as("动作 %s：闸门放行后任务恢复 PENDING", action)
                .isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void withoutHumanTaskSelfAppendsExactlyOneHumanResolve() {
        String taskId = "review-ev-n-" + uid();
        String docId = "doc-ev-n-" + uid();
        // 任务存在但没有 human_task 行（例如 review 案件由管理接口直接处置）
        insertWaitingTask(taskId);

        ReviewCase c = reviewCaseService.createLowConfidence(docId, 1, "f1",
                "MODEL", 0.4, "低", taskId);
        reviewCaseService.dispose(c.id(), ReviewCase.ReviewAction.APPROVE, null, "reviewer-a");

        List<TaskEvent> events = humanResolveEvents(taskId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).detail().get("caseId").asLong()).isEqualTo(c.id());
        assertThat(events.get(0).detail().get("action").asText())
                .isEqualTo(ReviewCase.ReviewAction.APPROVE.name());
    }

    private List<TaskEvent> humanResolveEvents(String taskId) {
        return eventRepo.findByTaskId(taskId).stream()
                .filter(e -> e.eventType() == TaskEventType.HUMAN_RESOLVE)
                .toList();
    }

    private void insertWaitingHumanTask(String taskId) {
        insertWaitingTask(taskId);
        humanRepo.save(new HumanTask(null, taskId, 110, HumanTaskKind.REVIEW, "需要人工复核", "说明",
                null, null, HumanTaskStatus.OPEN, null, null, null, null,
                0, Instant.now()));
    }

    private void insertWaitingTask(String taskId) {
        taskRepo.save(new TaskInstance(taskId, "T-EVENT", "biz-" + uid(),
                TaskStatus.WAITING_HUMAN,
                JSON.createObjectNode(), 0, 3, 0, null, null, null,
                null, "user-1", null, Instant.now(), null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now()));
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
