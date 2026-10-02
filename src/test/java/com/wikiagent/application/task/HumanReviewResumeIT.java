package com.wikiagent.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.rule.ReviewCaseService;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 波2 收口回归：人工处置恢复语义。
 * <ul>
 *   <li>REVIEW 必须走 RESUME→PENDING 续跑（修复前落入 DIRECT_RESOLVE 分支被直接 COMPLETED，
 *       导致复核后的文档跳过 CLEAN/SPLIT/EMBED 永不入库）；</li>
 *   <li>DECRYPT 同为填表续跑语义，必须 PENDING（波1 遗留潜在缺陷）；</li>
 *   <li>复核闸门：同一任务仍有 OPEN 案件时处置不恢复，最后一个案件处置后才 RESUME。</li>
 * </ul>
 * TaskDispatcherPort 用桩替换，避免本地 mq worker 抢消费造成状态竞态。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class HumanReviewResumeIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @MockBean
    private TaskDispatcherPort dispatcher;

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Autowired
    private HumanTaskRepositoryPort humanRepo;

    @Autowired
    private HumanTaskService humanTaskService;

    @Autowired
    private ReviewCaseService reviewCaseService;

    @Test
    void reviewResolveResumesToPendingInsteadOfCompleting() {
        String taskId = "review-resume-" + uid();
        insertWaitingHumanTask(taskId, HumanTaskKind.REVIEW);
        long htId = openHumanTaskId(taskId);

        TaskInstance after = humanTaskService.resolve(htId, "reviewer-1",
                HumanTaskKind.REVIEW, JSON.createObjectNode().put("action", "APPROVE"), null);

        assertThat(after.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(humanRepo.findById(htId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.RESOLVED);
    }

    @Test
    void decryptResolveResumesToPending() {
        String taskId = "decrypt-resume-" + uid();
        insertWaitingHumanTask(taskId, HumanTaskKind.DECRYPT);
        long htId = openHumanTaskId(taskId);

        ObjectNode form = JSON.createObjectNode().put("decryptPassword", "user-open");
        TaskInstance after = humanTaskService.resolve(htId, "user-1",
                HumanTaskKind.DECRYPT, form, null);

        assertThat(after.status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void reviewGateWaitsUntilAllCasesDisposed() {
        String taskId = "review-gate-" + uid();
        String docId = "doc-gate-" + uid();
        insertWaitingHumanTask(taskId, HumanTaskKind.REVIEW);
        long htId = openHumanTaskId(taskId);

        ReviewCase c1 = reviewCaseService.createLowConfidence(docId, 1, "f1",
                "MODEL", 0.4, "低置信", taskId);
        ReviewCase c2 = reviewCaseService.createLowConfidence(docId, 1, "f2",
                "MODEL", 0.3, "低置信", taskId);

        // 处置第一个案件：仍有 OPEN 案件 → 任务保持 WAITING_HUMAN，人工任务未终结
        reviewCaseService.dispose(c1.id(), ReviewCase.ReviewAction.APPROVE, null, "reviewer-1");
        assertThat(taskRepo.findByTaskId(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.WAITING_HUMAN);
        assertThat(humanRepo.findById(htId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.OPEN);

        // 处置最后一个案件 → 恢复 PENDING，人工任务 RESOLVED
        reviewCaseService.dispose(c2.id(), ReviewCase.ReviewAction.APPROVE, null, "reviewer-1");
        assertThat(taskRepo.findByTaskId(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.PENDING);
        assertThat(humanRepo.findById(htId).orElseThrow().status())
                .isEqualTo(HumanTaskStatus.RESOLVED);
    }

    private void insertWaitingHumanTask(String taskId, HumanTaskKind kind) {
        taskRepo.save(new TaskInstance(taskId, "T-RESUME", "biz-" + uid(),
                TaskStatus.WAITING_HUMAN,
                JSON.createObjectNode(), 0, 3, 0, null, null, null,
                null, "user-1", null, Instant.now(), null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now()));
        humanRepo.save(new HumanTask(null, taskId, 110, kind, "需要人工", "说明",
                null, null, HumanTaskStatus.OPEN, null, null, null, null,
                0, Instant.now()));
    }

    private long openHumanTaskId(String taskId) {
        return humanRepo.findByTaskId(taskId).stream()
                .filter(h -> h.status() == HumanTaskStatus.OPEN)
                .findFirst().orElseThrow().id();
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
