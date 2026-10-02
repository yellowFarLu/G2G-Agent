package com.wikiagent.interfaces.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F2 任务预算集成测试（H2 真实迁移 + mq=local + LLM 全 stub）：
 * ① PAUSE_HUMAN：iterationLimit=1 → NODE_1 第 2 轮迭代边界超限 → WAITING_HUMAN +
 *   INPUT 人工任务，payload.budget 用量已持久化；人工提额（formValue.iterationLimit=10）
 *   后续跑 COMPLETED，且预算用量在既有值上累计（不重置）。
 * ② FAIL：overflowPolicy=FAIL + iterationLimit=1 → FAILED 终态，error_code=BUDGET_EXCEEDED。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AgentBudgetIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLAN_JSON = """
            [{"id":"step1","goal":"检索商家入驻流程","stepType":"search_kb"},
             {"id":"step2","goal":"汇总生成答案","stepType":"generate"}]""";
    private static final String ACTION_JSON =
            "{\"thought\":\"检索\",\"action\":{\"name\":\"search_knowledge_base\",\"args\":\"商家入驻\"},"
                    + "\"finalAnswer\":null}";
    private static final String FINAL_JSON =
            "{\"thought\":\"已获得答案\",\"action\":null,\"finalAnswer\":\"商家入驻流程：注册-审核-上线\"}";

    @MockBean
    private ChatModel chatModel;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Autowired
    private HumanTaskRepositoryPort humanRepo;

    /** 首轮 ReAct 出 ACTION，其后恒 FINAL（跨恢复全局一次性）。 */
    private final AtomicBoolean reActActionUsed = new AtomicBoolean(false);

    @BeforeEach
    void setUpStub() {
        reActActionUsed.set(false);
        when(chatModel.call(any(Prompt.class))).thenAnswer(this::stubChat);
    }

    private ChatResponse stubChat(InvocationOnMock inv) {
        Prompt prompt = inv.getArgument(0);
        List<Message> messages = prompt.getInstructions();
        String system = messages.get(0).getText();
        if (system.contains("任务规划")) {
            return chatResponse(PLAN_JSON);
        }
        if (system.contains("反思器")) {
            return chatResponse("{\"text\":\"ok\",\"needsRework\":false,\"reworkHint\":\"\","
                    + "\"planAdjustments\":[]}");
        }
        if (system.contains("失败反思器")) {
            return chatResponse("{\"text\":\"ok\",\"shouldRetry\":false,\"reason\":\"不再重试\"}");
        }
        if (reActActionUsed.compareAndSet(false, true)) {
            return chatResponse(ACTION_JSON);
        }
        return chatResponse(FINAL_JSON);
    }

    /** ① PAUSE_HUMAN：超限转人工 INPUT，提额后续跑，预算用量不重置。 */
    @Test
    void pauseHumanCreatesInputTaskAndBudgetSurvivesResume() throws Exception {
        String taskId = submitAgentTask("budget-pause-" + uid(),
                JSON.createObjectNode().put("iterationLimit", 1));

        await(() -> "WAITING_HUMAN".equals(safeStatus(taskId)), "预算超限转 WAITING_HUMAN");
        List<HumanTask> humanTasks = humanRepo.findByTaskId(taskId);
        assertThat(humanTasks).anyMatch(h -> h.kind() == HumanTaskKind.INPUT
                && h.title().contains("预算超限"));

        // 预算用量已持久化进 payload（iterationUsed=1），且未重置
        JsonNode budget = taskRepo.findByTaskId(taskId).orElseThrow().payload().get("budget");
        assertThat(budget).isNotNull();
        assertThat(budget.get("iterationUsed").asInt()).isEqualTo(1);

        // 人工提额：resolve INPUT，formValue 携带新 iterationLimit
        long htId = humanTasks.stream().filter(h -> h.kind() == HumanTaskKind.INPUT)
                .findFirst().orElseThrow().id();
        mvc.perform(post("/api/human-tasks/" + htId + "/resolve").header("X-User-Id", "ops-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.createObjectNode()
                                .put("kind", "INPUT")
                                .set("formValue", JSON.createObjectNode().put("iterationLimit", 10))
                                .toString()))
                .andExpect(status().isOk());

        await(() -> "COMPLETED".equals(safeStatus(taskId)), "提额后续跑 COMPLETED");

        // 恢复后预算不重置：在既有 iterationUsed=1 上累计（NODE_1 重跑 1 轮 + NODE_2 1 轮 = 3）
        JsonNode budgetAfter = taskRepo.findByTaskId(taskId).orElseThrow().payload().get("budget");
        assertThat(budgetAfter.get("iterationUsed").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(budgetAfter.get("tokenUsed").asLong()).isPositive();
    }

    /** ② FAIL：超限直接 FAILED 终态（BUDGET_EXCEEDED，不重试）。 */
    @Test
    void failPolicyTerminatesWithBudgetExceeded() throws Exception {
        String taskId = submitAgentTask("budget-fail-" + uid(),
                JSON.createObjectNode().put("iterationLimit", 1).put("overflowPolicy", "FAIL"));

        await(() -> "FAILED".equals(safeStatus(taskId)), "预算超限 FAILED 终态");
        var task = taskRepo.findByTaskId(taskId).orElseThrow();
        assertThat(task.errorCode().name()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(task.attempt()).isZero(); // 不可重试：未消耗重试次数
    }

    private String submitAgentTask(String userId, ObjectNode budget) throws Exception {
        var args = JSON.createObjectNode()
                .put("userId", userId)
                .put("sessionId", "sess-budget")
                .put("userInput", "介绍一下商家入驻流程");
        args.set("budget", budget);
        String body = JSON.createObjectNode()
                .put("taskType", "AGENT")
                .put("bizKey", "agent:" + userId + ":" + UUID.randomUUID())
                .set("args", args)
                .toString();
        MvcResult result = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("等待超时: " + what);
    }

    private String safeStatus(String taskId) {
        try {
            return taskRepo.findByTaskId(taskId).map(t -> t.status().name()).orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
