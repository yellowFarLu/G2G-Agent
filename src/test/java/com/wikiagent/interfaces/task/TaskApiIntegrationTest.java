package com.wikiagent.interfaces.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 10 任务 REST API 集成测试（规格 4.2 HTTP 契约）：
 * 提交幂等、查询、claim/resolve、suspend/resume/cancel、replay、409/404/503 映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TaskApiIntegrationTest {

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 流程 handler：每个任务首次执行步骤 1 要求人工补充，之后读取合并的人工输入完成。 */
    static class FlowHandler implements TaskHandler {
        volatile boolean requireHuman = true;
        final java.util.Set<String> requiredHuman = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicReference<Map<String, com.fasterxml.jackson.databind.JsonNode>> seenHumanInputs =
                new AtomicReference<>();

        @Override
        public String taskType() {
            return "T10API";
        }

        @Override
        public java.util.List<StepDef> planSteps(com.fasterxml.jackson.databind.JsonNode payloadArgs) {
            return java.util.List.of(
                    StepDef.of(1, "NEED_INPUT", "待补充").withHumanCheckpoint(true),
                    StepDef.of(2, "FINISH", "收尾"));
        }

        @Override
        public StepResult executeStep(TaskExecutionContext ctx)
                throws com.wikiagent.domain.task.RetryableTaskException,
                com.wikiagent.domain.task.FatalTaskException, HumanRequiredException {
            if (ctx.currentStepNo() == 1) {
                if (requireHuman && requiredHuman.add(ctx.taskId())) {
                    throw new HumanRequiredException(HumanTaskKind.INPUT, "需要补充答案", "请提供答案",
                            new ObjectMapper().createObjectNode().put("answer", "string"));
                }
                seenHumanInputs.set(ctx.humanInputs());
                return StepResult.done(50);
            }
            return StepResult.done(100, "ref-final");
        }
    }

    /** 慢 handler：步骤 1 阻塞，供取消流程在 RUNNING 时插入控制指令。 */
    static class SlowHandler implements TaskHandler {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        @Override
        public String taskType() {
            return "T10SLOW";
        }

        @Override
        public java.util.List<StepDef> planSteps(com.fasterxml.jackson.databind.JsonNode payloadArgs) {
            return java.util.List.of(StepDef.of(1, "WORK", "干活"));
        }

        @Override
        public StepResult executeStep(TaskExecutionContext ctx)
                throws com.wikiagent.domain.task.RetryableTaskException,
                com.wikiagent.domain.task.FatalTaskException, HumanRequiredException {
            entered.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            com.wikiagent.application.task.TaskControlContext.checkpointAndThrowIfSignaled();
            return StepResult.done(100);
        }
    }

    @TestConfiguration
    static class FakeHandlers {
        @Bean
        TaskHandler flowHandler() {
            return new FlowHandler();
        }

        @Bean
        TaskHandler slowHandler() {
            return new SlowHandler();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRepositoryPort repo;

    @Autowired
    private HumanTaskRepositoryPort humanRepo;

    @Autowired
    private FlowHandler flowHandler;

    @Autowired
    private SlowHandler slowHandler;

    private final ObjectMapper json = new ObjectMapper();

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("等待超时: " + what);
    }

    private String submit(String taskType, String user) throws Exception {
        MvcResult result = mvc.perform(post("/api/tasks")
                        .header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("taskType", taskType).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
    }

    private String statusOf(String taskId) throws Exception {
        return json.readTree(mvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("status").asText();
    }

    // ① 提交 → 查询 PENDING → steps/events 可查
    @Test
    void submitAndQuery() throws Exception {
        MvcResult result = mvc.perform(post("/api/tasks")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("taskType", "T10API").toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn();
        String taskId = json.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
        assertThat(taskId).startsWith("tsk_");

        await(() -> "PENDING".equals(safeStatus(taskId)) || "RUNNING".equals(safeStatus(taskId))
                || "WAITING_HUMAN".equals(safeStatus(taskId)), "任务被消费");
        // 步骤由 worker 异步规划，轮询直至可见（避免 PENDING 期查询竞态）
        await(() -> stepsCount(taskId) >= 2, "步骤已规划");
        mvc.perform(get("/api/tasks/" + taskId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("T10API"))
                .andExpect(jsonPath("$.submittedBy").value("user-1"));
        mvc.perform(get("/api/tasks/" + taskId + "/steps")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/tasks/" + taskId + "/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("SUBMIT"));
        mvc.perform(get("/api/tasks/nonexistent-" + uid())).andExpect(status().isNotFound());
    }

    private String safeStatus(String taskId) {
        try {
            return statusOf(taskId);
        } catch (Exception e) {
            return "";
        }
    }

    private int stepsCount(String taskId) {
        try {
            return json.readTree(mvc.perform(get("/api/tasks/" + taskId + "/steps"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ② 同 bizKey 重复提交 → duplicate:true 且同 taskId
    @Test
    void duplicateSubmissionMarked() throws Exception {
        String body = json.createObjectNode()
                .put("taskType", "T10API")
                .put("bizKey", "biz-t10-dup-" + uid())
                .toString();
        MvcResult first = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String taskId = json.readTree(first.getResponse().getContentAsString()).get("taskId").asText();
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.taskId").value(taskId));
    }

    // ③ WAITING_HUMAN 全流程：claim 200 → 第二人 409 → resolve INPUT → 任务续跑 COMPLETED
    @Test
    void humanInputFlowCompletes() throws Exception {
        flowHandler.requireHuman = true;
        String taskId = submit("T10API", "user-1");

        await(() -> "WAITING_HUMAN".equals(safeStatus(taskId)), "任务等待人工");
        MvcResult htResult = mvc.perform(get("/api/tasks/" + taskId + "/human-tasks"))
                .andExpect(status().isOk()).andReturn();
        long humanTaskId = json.readTree(htResult.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        mvc.perform(post("/api/human-tasks/" + humanTaskId + "/claim").header("X-User-Id", "user-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"));
        mvc.perform(post("/api/human-tasks/" + humanTaskId + "/claim").header("X-User-Id", "user-b"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/human-tasks/" + humanTaskId + "/resolve").header("X-User-Id", "user-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("kind", "INPUT")
                                .set("formValue", json.createObjectNode().put("answer", "42"))
                                .toString()))
                .andExpect(status().isOk());

        await(() -> "COMPLETED".equals(safeStatus(taskId)), "人工补充后任务完成");
        assertThat(flowHandler.seenHumanInputs.get()).containsEntry("answer",
                json.createObjectNode().put("answer", "42").get("answer"));
        mvc.perform(get("/api/tasks/" + taskId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultRef").value("ref-final"));
    }

    // ④ DIRECT_RESOLVE 直接终结 → COMPLETED + resultRef
    @Test
    void directResolveCompletes() throws Exception {
        String taskId = "tsk_t10dr_" + uid();
        insertWaitingHumanTask(taskId, HumanTaskKind.DIRECT_RESOLVE);

        mvc.perform(post("/api/human-tasks/" + openHumanTaskId(taskId) + "/resolve")
                        .header("X-User-Id", "admin-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("kind", "DIRECT_RESOLVE")
                                .put("resultRef", "ref-direct")
                                .toString()))
                .andExpect(status().isOk());

        assertThat(repo.findByTaskId(taskId)).isPresent().get()
                .extracting(TaskInstance::status).isEqualTo(TaskStatus.COMPLETED);
        assertThat(repo.findByTaskId(taskId).orElseThrow().resultRef()).isEqualTo("ref-direct");
    }

    // ⑤ suspend/resume 往返
    @Test
    void suspendResumeRoundTrip() throws Exception {
        String taskId = submit("T10API", "user-2");
        // 等待任务进入 WAITING_HUMAN（步骤 1 抛出 HumanRequiredException），消除 suspend 与 worker 保存的竞态
        await(() -> "WAITING_HUMAN".equals(safeStatus(taskId)), "任务进入 WAITING_HUMAN");

        mvc.perform(post("/api/tasks/" + taskId + "/suspend")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(repo.findByTaskId(taskId).orElseThrow().suspendReason()).isNotNull();

        mvc.perform(post("/api/tasks/" + taskId + "/resume"))
                .andExpect(status().isOk());
        assertThat(repo.findByTaskId(taskId).orElseThrow().suspendReason()).isNull();
    }

    // ⑥ expectedVersion 过期 → 409
    @Test
    void staleExpectedVersionConflicts() throws Exception {
        String taskId = submit("T10API", "user-3");
        mvc.perform(post("/api/tasks/" + taskId + "/suspend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\": 999}"))
                .andExpect(status().isConflict());
    }

    // ⑦ cancel：RUNNING 中取消 → CANCELLED 终态
    @Test
    void cancelReachesCancelledTerminal() throws Exception {
        slowHandler.release = new CountDownLatch(1);
        String taskId = submit("T10SLOW", "user-4");

        await(() -> {
            try {
                return slowHandler.entered.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }, "步骤 1 进入");
        await(() -> "RUNNING".equals(safeStatus(taskId)), "任务 RUNNING");

        mvc.perform(post("/api/tasks/" + taskId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        slowHandler.release.countDown();

        await(() -> "CANCELLED".equals(safeStatus(taskId)), "任务取消终态");
    }

    // ⑧ replay FAILED 任务回 PENDING
    @Test
    void replayFailedTask() throws Exception {
        String taskId = "tsk_t10rp_" + uid();
        repo.save(new TaskInstance(taskId, "T10API", "biz-rp-" + uid(), TaskStatus.FAILED,
                json.createObjectNode(), 3, 3, 0, null,
                com.wikiagent.domain.task.ErrorCode.INTERNAL, "boom",
                null, "user-1", null, null, null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now()));

        mvc.perform(post("/api/tasks/" + taskId + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ⑨ 列表过滤
    @Test
    void listWithFilters() throws Exception {
        submit("T10API", "list-user-" + uid());
        mvc.perform(get("/api/tasks").param("status", "PENDING"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks").param("mine", "true").header("X-User-Id", "nobody-" + uid()))
                .andExpect(status().isOk());
    }

    private void insertWaitingHumanTask(String taskId, HumanTaskKind kind) {
        repo.save(new TaskInstance(taskId, "T10API", "biz-" + uid(), TaskStatus.WAITING_HUMAN,
                json.createObjectNode(), 0, 3, 0, null, null, null,
                null, "user-1", null, Instant.now(), null, null, null,
                Instant.now(), null, 0, Instant.now(), Instant.now()));
        humanRepo.save(new HumanTask(null, taskId, 1, kind, "处置确认", "说明",
                null, null, HumanTaskStatus.OPEN, null, null, null, null, 0, Instant.now()));
    }

    private long openHumanTaskId(String taskId) {
        return humanRepo.findByTaskId(taskId).stream()
                .filter(h -> h.status() == HumanTaskStatus.OPEN)
                .findFirst().orElseThrow().id();
    }
}
