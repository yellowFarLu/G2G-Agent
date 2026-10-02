package com.wikiagent.application.agent.pero;

import com.wikiagent.application.agent.ToolPermissionRegistry;
import com.wikiagent.config.ToolPermissionProperties;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTaskKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F3 TOOL_APPROVAL + 上下文压缩单测（ReActExecutor 层）：
 * ① 高危工具未决 → 抛 HumanRequiredException(TOOL_APPROVAL)，formSchema 携带 toolName；
 * ② 批准（approve=true）→ 工具放行执行，节点正常结束；
 * ③ 驳回（approve=false）→ 工具不执行，observation=TOOL_REJECTED_BY_USER，ReAct 继续；
 * ④ 迭代轨迹超 10 轮 → prompt 重建压缩（早期轮折叠为摘要），完整轨迹不受损。
 */
class ReActApprovalTest {

    private static final String TOOL = "search_knowledge_base";
    private static final PlanStep STEP = new PlanStep("step1", "检索", "tool");

    private ChatModel chatModel;
    private AtomicBoolean toolInvoked;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        toolInvoked = new AtomicBoolean(false);
    }

    /** 注册表：search_knowledge_base 标记为高危（requiresApproval=true），无角色/scope 限制。 */
    private ToolPermissionRegistry registryRequiringApproval() {
        ToolPermissionProperties props = new ToolPermissionProperties();
        ToolPermissionProperties.PermissionSpec spec = new ToolPermissionProperties.PermissionSpec();
        spec.setRequiresApproval(true);
        Map<String, ToolPermissionProperties.PermissionSpec> map = new HashMap<>();
        map.put(TOOL, spec);
        props.setPermissions(map);
        return new ToolPermissionRegistry(props, emptyProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private ReActExecutor executor(ToolPermissionRegistry registry) {
        ToolExecutor toolExecutor = (action, ctx) -> {
            toolInvoked.set(true);
            return "[obs] " + action.name();
        };
        return new ReActExecutor(chatModel, step -> List.of(TOOL), toolExecutor,
                mock(TraceService.class), registry, null);
    }

    /** ReAct stub：actionOnce=true 首轮出 ACTION、其后恒 FINAL；false 恒 FINAL；alwaysAction 恒 ACTION。 */
    private void stubReAct(boolean actionOnce, boolean alwaysAction, List<String> capturedPrompts) {
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            if (capturedPrompts != null) {
                capturedPrompts.add(p.getInstructions().get(p.getInstructions().size() - 1).getText());
            }
            if (alwaysAction || (actionOnce && calls.incrementAndGet() == 1)) {
                return resp("{\"thought\":\"检索\",\"action\":{\"name\":\"" + TOOL + "\","
                        + "\"args\":\"{}\"},\"finalAnswer\":null}");
            }
            return resp("{\"thought\":\"结束\",\"action\":null,\"finalAnswer\":\"答案\"}");
        });
    }

    private static ReActGovernance governance(Map<String, Boolean> decisions) {
        return new ReActGovernance(null, "pero-agent", "sess-1", null, decisions);
    }

    private static ChatResponse resp(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void unapprovedHighRiskToolThrowsToolApproval() {
        stubReAct(true, false, null);

        assertThatThrownBy(() -> executor(registryRequiringApproval()).execute(STEP,
                new SimplePerception("u1", "s1", "问"), null, 8, () -> { }, governance(Map.of())))
                .isInstanceOfSatisfying(HumanRequiredException.class, h -> {
                    assertThat(h.getKind()).isEqualTo(HumanTaskKind.TOOL_APPROVAL);
                    assertThat(h.getTitle()).contains("工具批准");
                    assertThat(h.getFormSchema().get("toolName").asText()).isEqualTo(TOOL);
                    // #18 formSchema 必须携带 args 指纹；args="{}" 指纹=sha256("{}")
                    String fingerprint = h.getFormSchema().get("argsFingerprint").asText();
                    assertThat(fingerprint)
                            .isEqualTo(ReActExecutor.approvalKey(TOOL, "{}")
                                    .substring(TOOL.length() + 1));
                });
        assertThat(toolInvoked).isFalse();
    }

    @Test
    void approvedDecisionExecutesTool() {
        stubReAct(true, false, null);

        // #18 决策键为 toolName+args 指纹复合键（args="{}"）
        ReActResult result = executor(registryRequiringApproval()).execute(STEP,
                new SimplePerception("u1", "s1", "问"), null, 8, () -> { },
                governance(Map.of(ReActExecutor.approvalKey(TOOL, "{}"), true)));

        assertThat(result.done()).isTrue();
        assertThat(toolInvoked).isTrue();
        assertThat(result.trace()).anyMatch(t -> TOOL.equals(t.actionName())
                && t.observation() != null && t.observation().startsWith("[obs]"));
    }

    @Test
    void rejectedDecisionSkipsToolWithMarker() {
        stubReAct(true, false, null);

        ReActResult result = executor(registryRequiringApproval()).execute(STEP,
                new SimplePerception("u1", "s1", "问"), null, 8, () -> { },
                governance(Map.of(ReActExecutor.approvalKey(TOOL, "{}"), false)));

        assertThat(result.done()).isTrue();
        assertThat(toolInvoked).isFalse();
        assertThat(result.trace()).anyMatch(t -> "TOOL_REJECTED_BY_USER".equals(t.observation()));
    }

    /** #18 批准只对批准时的 args 生效：批准 A 后同工具以 args=B 调用必须重新走人工批准。 */
    @Test
    void approvalIsScopedToArgsDifferentArgsRequiresNewApproval() {
        String argsA = "{\"q\":\"a\"}";
        String argsB = "{\"q\":\"b\"}";
        stubActionArgs(List.of(argsA, argsB));

        assertThatThrownBy(() -> executor(registryRequiringApproval()).execute(STEP,
                new SimplePerception("u1", "s1", "问"), null, 8, () -> { },
                governance(Map.of(ReActExecutor.approvalKey(TOOL, argsA), true))))
                .isInstanceOfSatisfying(HumanRequiredException.class, h -> {
                    assertThat(h.getKind()).isEqualTo(HumanTaskKind.TOOL_APPROVAL);
                    // 新人工任务的指纹必须是 args=B 的指纹（而非已批准的 A）
                    assertThat(h.getFormSchema().get("argsFingerprint").asText())
                            .isEqualTo(ReActExecutor.argsFingerprint(argsB));
                });
        // A 已批准放行执行过一次；B 未批准不得执行
        assertThat(toolInvoked).isTrue();
    }

    /** #18 同工具同 args 第二次调用在批准有效期内直接放行（规范化后键序不同也视为同参）。 */
    @Test
    void sameArgsSecondCallIsAllowedAndKeyOrderNormalized() {
        String argsA = "{\"q\":\"a\"}";
        String argsAReordered = "{ \"q\" : \"a\" }";
        assertThat(ReActExecutor.approvalKey(TOOL, argsA))
                .as("空白差异不影响指纹")
                .isEqualTo(ReActExecutor.approvalKey(TOOL, argsAReordered));
        stubActionArgs(List.of(argsA, argsAReordered));

        ReActResult result = executor(registryRequiringApproval()).execute(STEP,
                new SimplePerception("u1", "s1", "问"), null, 8, () -> { },
                governance(Map.of(ReActExecutor.approvalKey(TOOL, argsA), true)));

        assertThat(result.done()).isTrue();
        long toolRuns = result.trace().stream()
                .filter(t -> t.observation() != null && t.observation().startsWith("[obs]"))
                .count();
        assertThat(toolRuns).as("同参两次调用均应放行").isEqualTo(2);
    }

    /** #18 无参（null/空白）与非法 args 统一指纹 sha256("{}")。 */
    @Test
    void emptyOrUnparseableArgsFallsBackToEmptyObjectFingerprint() {
        assertThat(ReActExecutor.approvalKey(TOOL, null))
                .isEqualTo(ReActExecutor.approvalKey(TOOL, ""))
                .isEqualTo(ReActExecutor.approvalKey(TOOL, "not-a-json"))
                .isEqualTo(ReActExecutor.approvalKey(TOOL, "{}"));
    }

    /** LLM stub：依次输出给定 args 的 ACTION，序列耗尽后恒 FINAL。 */
    private void stubActionArgs(List<String> actionArgs) {
        ObjectMapper om = new ObjectMapper();
        AtomicInteger idx = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int i = idx.getAndIncrement();
            if (i < actionArgs.size()) {
                var action = om.createObjectNode().put("name", TOOL).put("args", actionArgs.get(i));
                ObjectNode root = om.createObjectNode().put("thought", "检索");
                root.set("action", action);
                root.putNull("finalAnswer");
                return resp(root.toString());
            }
            return resp("{\"thought\":\"结束\",\"action\":null,\"finalAnswer\":\"答案\"}");
        });
    }

    @Test
    void nonApprovalToolNeverTriggersGate() {
        stubReAct(true, false, null);
        // 空配置注册表：requiresApproval=false → 审批门恒放行
        ToolPermissionRegistry registry = new ToolPermissionRegistry(
                new ToolPermissionProperties(), emptyProvider());

        ReActResult result = executor(registry).execute(STEP,
                new SimplePerception("u1", "s1", "问"), null, 8, () -> { }, governance(Map.of()));

        assertThat(result.done()).isTrue();
        assertThat(toolInvoked).isTrue();
    }

    @Test
    void contextCompactsAfterTenIterations() {
        List<String> prompts = new CopyOnWriteArrayList<>();
        stubReAct(false, true, prompts); // 恒 ACTION：跑满 maxIter
        ToolPermissionRegistry registry = new ToolPermissionRegistry(
                new ToolPermissionProperties(), emptyProvider());

        ReActResult result = executor(registry).execute(STEP,
                new SimplePerception("u1", "s1", "问"), null, 12, () -> { }, governance(Map.of()));

        // 达到 maxIter 截断；完整轨迹 12 轮不受压缩影响
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.trace()).hasSize(12);
        // 第 12 轮（轨迹 11 轮 > 阈值 10）起 prompt 重建：早期轮折叠为 COMPRESSED 摘要
        assertThat(prompts).anyMatch(p -> p.contains("已压缩") && p.contains("COMPRESSED"));
        // 首轮 prompt 不含压缩标记（阈值未触发）
        assertThat(prompts.get(0)).doesNotContain("已压缩");
    }
}
