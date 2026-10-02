package com.wikiagent.application.agent;

import com.wikiagent.application.agent.pero.ReActExecutor;
import com.wikiagent.application.agent.pero.ReActGovernance;
import com.wikiagent.application.agent.pero.SimplePerception;
import com.wikiagent.application.agent.pero.ToolExecutor;
import com.wikiagent.application.agent.pero.ToolRegistry;
import com.wikiagent.application.agent.pero.TraceService;
import com.wikiagent.config.ToolPermissionProperties;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.tool.ToolCaller;
import com.wikiagent.domain.tool.ToolPermission;
import com.wikiagent.infrastructure.tool.ToolPermissionJpaDao;
import com.wikiagent.infrastructure.trace.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1 ToolPermissionRegistry 单测：
 * ① 三类越权（不在 allowlist / 角色不足 / scope 不匹配）均拒绝、工具不执行、写 TOOL_DENIED 审计；
 * ② 放行路径正常执行工具；③ 权限解析优先级：配置 > DB > 内置默认。
 */
class ToolPermissionRegistryTest {

    private ChatModel chatModel;
    private AuditLogRepository auditLog;
    private ToolExecutor toolExecutor;
    private AtomicInteger toolCalls;

    private static final PlanStep STEP = new PlanStep("step1", "检索", "tool");
    private static final ToolRegistry ALL = step -> ToolPermissionRegistry.ALL_TOOLS;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        auditLog = mock(AuditLogRepository.class);
        toolCalls = new AtomicInteger();
        toolExecutor = (action, ctx) -> {
            toolCalls.incrementAndGet();
            return "[obs] " + action.name();
        };
    }

    /** ReAct stub：首轮动作调用指定工具，次轮 FINAL 收尾。 */
    private void stubReAct(String toolName) {
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                return resp("{\"thought\":\"调用工具\",\"action\":{\"name\":\"" + toolName
                        + "\",\"args\":\"{}\"},\"finalAnswer\":null}");
            }
            return resp("{\"thought\":\"结束\",\"action\":null,\"finalAnswer\":\"答案\"}");
        });
    }

    private ReActExecutor executor(ToolPermissionRegistry registry) {
        return new ReActExecutor(chatModel, ALL, toolExecutor, mock(TraceService.class),
                registry, auditLog);
    }

    private ReActGovernance governance(ToolCaller caller, String agentName) {
        return ReActGovernance.of(caller, agentName, "sess-1");
    }

    private static ToolPermissionRegistry registryWith(Map<String, ToolPermissionProperties.PermissionSpec> perms,
                                                       Map<String, List<String>> allowlists) {
        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setPermissions(perms);
        props.setAgentAllowlists(allowlists);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolPermissionJpaDao> noDb = mock(ObjectProvider.class);
        when(noDb.getIfAvailable()).thenReturn(null);
        return new ToolPermissionRegistry(props, noDb);
    }

    private static ToolPermissionProperties.PermissionSpec spec(String role, String scope, boolean approval) {
        ToolPermissionProperties.PermissionSpec s = new ToolPermissionProperties.PermissionSpec();
        s.setRequiredRole(role);
        s.setRequiredScope(scope);
        s.setRequiresApproval(approval);
        return s;
    }

    private static ChatResponse resp(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void denyWhenToolNotInAgentAllowlist() {
        stubReAct("search_history");
        ToolPermissionRegistry registry = registryWith(Map.of(),
                Map.of("pero-agent", List.of("search_knowledge_base")));

        ReActResult result = executor(registry).execute(STEP,
                new SimplePerception("u1", "sess-1", "问"), null, 4, () -> { },
                governance(ToolCaller.of("u1", "admin", List.of()), "pero-agent"));

        assertThat(result.done()).isTrue();
        assertThat(toolCalls.get()).isZero();
        assertThat(result.trace().get(0).observation()).startsWith("TOOL_DENIED")
                .contains("NOT_IN_ALLOWLIST");
        verify(auditLog).log(eq("u1"), eq("sess-1"), eq("TOOL_DENIED"), eq("tool_permission"),
                eq("WARN"), anyString(), eq("BLOCKED"),
                org.mockito.ArgumentMatchers.contains("toolName=search_history"));
    }

    @Test
    void denyWhenRoleInsufficient() {
        stubReAct("search_knowledge_base");
        ToolPermissionRegistry registry = registryWith(
                Map.of("search_knowledge_base", spec("admin", null, false)), Map.of());

        ReActResult result = executor(registry).execute(STEP,
                new SimplePerception("u2", "sess-1", "问"), null, 4, () -> { },
                governance(ToolCaller.of("u2", "business", List.of("kb:read")), "pero-agent"));

        assertThat(toolCalls.get()).isZero();
        assertThat(result.trace().get(0).observation()).startsWith("TOOL_DENIED")
                .contains("ROLE_INSUFFICIENT");
        verify(auditLog).log(eq("u2"), anyString(), eq("TOOL_DENIED"), anyString(),
                anyString(), anyString(), eq("BLOCKED"),
                org.mockito.ArgumentMatchers.contains("ROLE_INSUFFICIENT"));
    }

    @Test
    void denyWhenScopeMismatch() {
        stubReAct("search_knowledge_base");
        ToolPermissionRegistry registry = registryWith(
                Map.of("search_knowledge_base", spec(null, "kb:admin", false)), Map.of());

        ReActResult result = executor(registry).execute(STEP,
                new SimplePerception("u3", "sess-1", "问"), null, 4, () -> { },
                governance(ToolCaller.of("u3", "admin", List.of("kb:read")), "pero-agent"));

        assertThat(toolCalls.get()).isZero();
        assertThat(result.trace().get(0).observation()).startsWith("TOOL_DENIED")
                .contains("SCOPE_MISMATCH");
        verify(auditLog).log(eq("u3"), anyString(), eq("TOOL_DENIED"), anyString(),
                anyString(), anyString(), eq("BLOCKED"),
                org.mockito.ArgumentMatchers.contains("SCOPE_MISMATCH"));
    }

    @Test
    void allowWhenAllDimensionsPass() {
        stubReAct("search_knowledge_base");
        ToolPermissionRegistry registry = registryWith(
                Map.of("search_knowledge_base", spec("admin", "kb:read", false)),
                Map.of("pero-agent", List.of("search_knowledge_base")));

        ReActResult result = executor(registry).execute(STEP,
                new SimplePerception("u4", "sess-1", "问"), null, 4, () -> { },
                governance(ToolCaller.of("u4", "admin", List.of("kb:read")), "pero-agent"));

        assertThat(result.done()).isTrue();
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(result.trace().get(0).observation()).isEqualTo("[obs] search_knowledge_base");
        verify(auditLog, never()).log(any(), any(), eq("TOOL_DENIED"), any(), any(), any(), any(), any());
    }

    @Test
    void builtinDefaultMarksUpdateUserProfileRequiresApproval() {
        ToolPermissionRegistry registry = registryWith(Map.of(), Map.of());
        ToolPermission perm = registry.permissionOf("update_user_profile");
        assertThat(perm.requiresApproval()).isTrue();
        // 未知工具：无限制
        assertThat(registry.permissionOf("unknown_tool").roleRestricted()).isFalse();
        // 未配置 Agent 用默认白名单
        assertThat(registry.allowlistOf("domain-supervisor"))
                .isEqualTo(ToolPermissionRegistry.DEFAULT_ALLOWLIST);
    }
}
