package com.wikiagent.interfaces.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F3 TOOL_APPROVAL 集成测试（H2 真实迁移 + mq=local + LLM 全 stub）：
 * ① 高危工具（update_user_profile，内置默认 requiresApproval=true）调用前抛
 *    HumanRequiredException → WAITING_HUMAN + TOOL_APPROVAL 人工任务，formSchema 含 toolName。
 * ② 批准（approve=true）→ 任务恢复后工具放行 → COMPLETED。
 * ③ 驳回（approve=false）→ 任务恢复后跳过该工具 → COMPLETED（observation=TOOL_REJECTED_BY_USER）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class AgentApprovalIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLAN_JSON = """
            [{"id":"step1","goal":"更新用户资料","stepType":"update_profile"},
             {"id":"step2","goal":"汇总生成答案","stepType":"generate"}]""";
    private static final String ACTION_APPROVAL_JSON =
            "{\"thought\":\"更新\",\"action\":{\"name\":\"update_user_profile\","
                    + "\"args\":\"{}\"},\"finalAnswer\":null}";
    private static final String FINAL_JSON =
            "{\"thought\":\"已获得答案\",\"action\":null,\"finalAnswer\":\"更新完成\"}";

    @MockBean
    private ChatModel chatModel;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Autowired
    private HumanTaskRepositoryPort humanRepo;

    private final AtomicInteger reActCalls = new AtomicInteger(0);

    @BeforeEach
    void setUpStub() {
        reActCalls.set(0);
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
        // ReAct：奇数调用出 ACTION（update_user_profile），偶数调用出 FINAL
        int n = reActCalls.incrementAndGet();
        if (n % 2 == 1) {
            return chatResponse(ACTION_APPROVAL_JSON);
        }
        return chatResponse(FINAL_JSON);
    }

    /** ② 批准续跑：高危工具被人工批准后执行，任务完成。 */
    @Test
    void approveResumesAndExecutesTool() throws Exception {
        String taskId = submitAgentTask("approval-ok-" + uid());

        await(() -> "WAITING_HUMAN".equals(safeStatus(taskId)), "高危工具触发 WAITING_HUMAN");
        List<HumanTask> hts = humanRepo.findByTaskId(taskId);
        assertThat(hts).anyMatch(h -> h.kind() == HumanTaskKind.TOOL_APPROVAL
                && h.status() == HumanTaskStatus.OPEN);

        HumanTask ht = hts.stream().filter(h -> h.kind() == HumanTaskKind.TOOL_APPROVAL)
                .findFirst().orElseThrow();
        assertThat(ht.formSchema().get("toolName").asText()).isEqualTo("update_user_profile");
        // #18 端到端：抛出→建单持久化的 formSchema 必须带 args 指纹（args="{}"，sha256 64 位 HEX）
        assertThat(ht.formSchema().get("argsFingerprint").asText())
                .matches("[0-9a-f]{64}")
                .isEqualTo(sha256Hex("{}"));

        long htId = ht.id();
        mvc.perform(post("/api/human-tasks/" + htId + "/resolve").header("X-User-Id", "ops-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.createObjectNode()
                                .put("kind", "TOOL_APPROVAL")
                                .set("formValue", JSON.createObjectNode().put("approve", true))
                                .toString()))
                .andExpect(status().isOk());

        await(() -> "COMPLETED".equals(safeStatus(taskId)), "批准后续跑 COMPLETED");
    }

    /** ③ 驳回跳过：高危工具被驳回后不执行，ReAct 继续并最终完成。 */
    @Test
    void rejectSkipsToolAndCompletes() throws Exception {
        String taskId = submitAgentTask("approval-reject-" + uid());

        await(() -> "WAITING_HUMAN".equals(safeStatus(taskId)), "高危工具触发 WAITING_HUMAN");
        List<HumanTask> hts = humanRepo.findByTaskId(taskId);
        HumanTask ht = hts.stream().filter(h -> h.kind() == HumanTaskKind.TOOL_APPROVAL)
                .findFirst().orElseThrow();
        long htId = ht.id();

        mvc.perform(post("/api/human-tasks/" + htId + "/resolve").header("X-User-Id", "ops-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.createObjectNode()
                                .put("kind", "TOOL_APPROVAL")
                                .set("formValue", JSON.createObjectNode().put("approve", false))
                                .toString()))
                .andExpect(status().isOk());

        await(() -> "COMPLETED".equals(safeStatus(taskId)), "驳回后跳过仍 COMPLETED");
    }

    private String submitAgentTask(String userId) throws Exception {
        var args = JSON.createObjectNode()
                .put("userId", userId)
                .put("sessionId", "sess-approval")
                .put("userInput", "更新我的资料")
                // update_user_profile 需 scope profile:write（application.yml F1 配置），
                // 否则 F1 权限门先于 F3 批准门拦截（SCOPE_MISMATCH），审批逻辑到不了
                .put("userScope", "profile:write");
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

    /** #18 测试本地计算 sha256 HEX（与 ReActExecutor 指纹算法一致）。 */
    private static String sha256Hex(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
