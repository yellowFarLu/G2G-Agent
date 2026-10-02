package com.wikiagent.interfaces.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import com.wikiagent.domain.task.TaskStep;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskStepRepositoryPort;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 12 AGENT 任务控制集成测试（mq=local，LLM 全 stub）：
 * ① 提交 AGENT → RUNNING 后 suspend → SUSPENDED、步骤已记录（PLAN + 动态 NODE + GENERATE）；
 *    resume → COMPLETED，且 planner 不被再次调用（恢复走 handler 持有的 plan checkpoint，
 *    完成过的节点不重跑）；SSE 流（TaskStreamBus）收到 delta 与 done。
 * ② cancel → CANCELLED 且不产生最终答案（无 delta 事件）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AgentTaskControlIntegrationTest {

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
    private TaskStepRepositoryPort stepRepo;

    @Autowired
    private TaskStreamBus streamBus;

    private final AtomicInteger plannerCalls = new AtomicInteger();
    private final AtomicBoolean blockFirstReAct = new AtomicBoolean(false);
    private final AtomicBoolean reActActionUsed = new AtomicBoolean(false);
    private CountDownLatch reActEntered = new CountDownLatch(1);
    private CountDownLatch reActRelease = new CountDownLatch(1);

    @BeforeEach
    void setUpStub() {
        plannerCalls.set(0);
        blockFirstReAct.set(false);
        reActActionUsed.set(false);
        reActEntered = new CountDownLatch(1);
        reActRelease = new CountDownLatch(1);
        when(chatModel.call(any(Prompt.class))).thenAnswer(this::stubChat);
    }

    /** 按系统提示词路由 stub：规划 / 反思 / 失败反思 / ReAct（首轮可阻塞）。 */
    private ChatResponse stubChat(InvocationOnMock inv) {
        Prompt prompt = inv.getArgument(0);
        List<Message> messages = prompt.getInstructions();
        String system = messages.get(0).getText();
        if (system.contains("任务规划")) {
            plannerCalls.incrementAndGet();
            return chatResponse(PLAN_JSON);
        }
        if (system.contains("反思器")) {
            return chatResponse("{\"text\":\"ok\",\"needsRework\":false,\"reworkHint\":\"\","
                    + "\"planAdjustments\":[]}");
        }
        if (system.contains("失败反思器")) {
            return chatResponse("{\"text\":\"ok\",\"shouldRetry\":false,\"reason\":\"不再重试\"}");
        }
        // ReAct 执行器：首轮可阻塞，供测试在阻塞窗口插入 suspend/cancel 控制指令
        reActEntered.countDown();
        if (blockFirstReAct.compareAndSet(true, false)) {
            try {
                reActRelease.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reActActionUsed.compareAndSet(false, true)) {
            return chatResponse(ACTION_JSON);
        }
        return chatResponse(FINAL_JSON);
    }

    @Test
    void suspendThenResumeCompletesWithStreamBridge() throws Exception {
        // 首轮 ReAct 阻塞：为 suspend 留出控制窗口，否则任务在暂停生效前已跑完
        blockFirstReAct.set(true);
        String taskId = submitAgentTask("user-ag-1", "sess-ag-1", "介绍一下商家入驻流程");
        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AutoCloseable sub = streamBus.subscribe(taskId, events::add);

        // 等 NODE_1 的 ReAct 首次调用进入阻塞窗口
        await(() -> reActEntered.getCount() == 0, "NODE_1 ReAct 首次调用进入");
        mvc.perform(post("/api/tasks/" + taskId + "/suspend")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        reActRelease.countDown();

        await(() -> "SUSPENDED".equals(safeStatus(taskId)), "任务暂停");
        await(() -> stepRepo.findByTaskIdOrderByStepNo(taskId).size() >= 4, "动态节点步骤已注册");
        List<TaskStep> steps = stepRepo.findByTaskIdOrderByStepNo(taskId);
        assertThat(steps).extracting(TaskStep::stepType)
                .containsExactly("PLAN", "NODE_1", "NODE_2", "GENERATE");
        assertThat(taskRepo.findByTaskId(taskId).orElseThrow().suspendReason()).isNotNull();

        mvc.perform(post("/api/tasks/" + taskId + "/resume")).andExpect(status().isOk());
        await(() -> "COMPLETED".equals(safeStatus(taskId)), "恢复后完成");

        // 恢复不得重新规划：planner 只在 PLAN 步调用一次（Review Focus #5）
        assertThat(plannerCalls.get()).isEqualTo(1);
        // SSE 流经 bus 收到 delta 与 done
        await(() -> events.stream().anyMatch(e -> "delta".equals(e.type())), "delta 事件");
        await(() -> events.stream().anyMatch(e -> "done".equals(e.type())), "done 事件");
        assertThat(events).anyMatch(e -> "delta".equals(e.type()) && e.payload().has("text"));
        assertThat(stepRepo.firstNonDoneStepNo(taskId)).isEqualTo(-1);
        sub.close();
    }

    @Test
    void cancelReachesCancelledWithoutFinalAnswer() throws Exception {
        blockFirstReAct.set(true);
        String taskId = submitAgentTask("user-ag-2", "sess-ag-2", "介绍一下干线运输时效");
        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        AutoCloseable sub = streamBus.subscribe(taskId, events::add);

        await(() -> reActEntered.getCount() == 0, "NODE_1 ReAct 首次调用进入");
        mvc.perform(post("/api/tasks/" + taskId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        reActRelease.countDown();

        await(() -> "CANCELLED".equals(safeStatus(taskId)), "任务取消终态");
        await(() -> events.stream().anyMatch(e -> "done".equals(e.type())
                && "CANCELLED".equals(e.payload().path("status").asText())), "取消终态事件");
        // 不产生最终答案：无 delta 事件
        assertThat(events).noneMatch(e -> "delta".equals(e.type()));
        sub.close();
    }

    private String submitAgentTask(String userId, String sessionId, String userInput) throws Exception {
        String body = JSON.createObjectNode()
                .put("taskType", "AGENT")
                .put("bizKey", "agent:" + userId + ":" + sessionId + ":" + UUID.randomUUID())
                .set("args", JSON.createObjectNode()
                        .put("userId", userId)
                        .put("sessionId", sessionId)
                        .put("userInput", userInput))
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

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
