package com.wikiagent.application.agent.pero;

import com.wikiagent.application.agent.ToolPermissionRegistry;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.agent.ReActStep;
import com.wikiagent.domain.agent.ThoughtActionObservation;
import com.wikiagent.domain.tool.ToolCaller;
import com.wikiagent.infrastructure.trace.AuditLogRepository;
import com.wikiagent.service.agent.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v6 §20.5 节点内 ReAct 子循环执行器（替代 §2.4 单步 NodeExecutor）。
 * <p>
 * ReAct 论文（arXiv:2210.03629）的 Thought/Action/Observation 交替循环：
 * 推理→动作→观测→再推理，直到 LLM 输出 FINAL 或达到 maxIter。
 * <p>
 * 与 §2 既有 {@code NodeExecutor} 的区别：
 * <ul>
 *   <li>§2 NodeExecutor 单步执行（plan 后顺序执行），v6 ReActExecutor 节点内多轮 ReAct</li>
 *   <li>工具白名单按 {@link PlanStep#stepType()} 路由（§20.6 配置）</li>
 *   <li>失败不抛异常，返回 {@link ReActResult#truncated} 由 Reflector 判断是否重做</li>
 * </ul>
 * maxIter 经验默认 8（§20.6 {@code wikiagent.pero.react.max-iterations}），可由 PlanStep 覆盖。
 */
@Service
public class ReActExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReActExecutor.class);

    static final String REACT_SYSTEM = """
            你是企业知识库 Agent 的 ReAct 执行器。基于当前节点目标与历史轨迹，输出下一步：
            只输出一个 JSON，不要输出任何其他文字：
            {"thought":"...","action":{"name":"...","args":"..."},"finalAnswer":null}
            或（节点结束）：
            {"thought":"...","action":null,"finalAnswer":"...最终答案..."}
            action.name 必须在工具白名单中；完成节点目标时 action=null 且 finalAnswer 非空。
            """;

    private final ChatModel model;
    private final ToolRegistry toolRegistry;
    private final TraceService trace;
    private final ToolExecutor toolExecutor;
    private final int defaultMaxIter = 8;

    /** F1：工具权限注册表 + 审计（可选，缺省时不做权限拦截，保持既有行为）。 */
    private final ToolPermissionRegistry permissionRegistry;
    private final AuditLogRepository auditLog;

    public ReActExecutor(ChatModel model, ToolRegistry toolRegistry,
                         ToolExecutor toolExecutor, TraceService trace) {
        this(model, toolRegistry, toolExecutor, trace, null, null);
    }

    @Autowired
    public ReActExecutor(ChatModel model, ToolRegistry toolRegistry,
                         ToolExecutor toolExecutor, TraceService trace,
                         ToolPermissionRegistry permissionRegistry,
                         AuditLogRepository auditLog) {
        this.model = model;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.trace = trace;
        this.permissionRegistry = permissionRegistry;
        this.auditLog = auditLog;
    }

    /**
     * 节点内 ReAct 子循环：最多 maxIter 次 Thought/Action/Observation 交替。
     * <p>
     * trace 每轮启动子 span（react:{stepId}:{iter}），便于 §13.8 验收 #23 校验
     * "节点 span 下挂至少 2 条子 ReAct span"。
     */
    public ReActResult execute(PlanStep step, Perception ctx, Handover handover, int maxIter) {
        return execute(step, ctx, handover, maxIter, () -> { });
    }

    /**
     * 带迭代 gate 的重载（Task 12）：每次迭代顶部执行 {@code iterationGate}，
     * gate 抛出的控制信号（暂停/取消）原样穿出本方法，不得被迭代内通用 catch 吞掉。
     * 原四参方法委托空 gate，行为不变。
     */
    public ReActResult execute(PlanStep step, Perception ctx, Handover handover,
                               int maxIter, Runnable iterationGate) {
        return execute(step, ctx, handover, maxIter, iterationGate, null);
    }

    /**
     * F1/F2/F3 治理版重载：携带 ReActGovernance（caller/agentName/预算/审批续跑），
     * 原五参方法委托 null 治理，行为完全不变（不拦截、不计费、不批准）。
     */
    public ReActResult execute(PlanStep step, Perception ctx, Handover handover,
                               int maxIter, Runnable iterationGate,
                               ReActGovernance governance) {
        int limit = maxIter > 0 ? maxIter : defaultMaxIter;
        List<ThoughtActionObservation> traceList = new ArrayList<>();
        List<String> allowedTools = toolRegistry.allowedTools(step);
        String conversationId = ctx.userId() + ":" + ctx.sessionId();
        String prompt = buildReActPrompt(step, ctx, allowedTools, traceList);

        for (int i = 0; i < limit; i++) {
            iterationGate.run();
            TraceSpan sub = trace.start(conversationId, ctx.userId(),
                    "react:" + step.id() + ":" + (i + 1), step.goal());
            ReActStep ra;
            try {
                ra = callReAct(prompt);
            } catch (Exception e) {
                trace.end(sub, null, "ERROR", e.getMessage());
                log.warn("ReAct step {} iter {} 调用失败: {}", step.id(), i + 1, e.getMessage());
                return ReActResult.truncated(traceList);
            }

            String observation;
            if (ra.action() == null || "FINAL".equalsIgnoreCase(nameOf(ra))) {
                observation = ra.finalAnswer() == null ? "" : ra.finalAnswer();
                traceList.add(ThoughtActionObservation.finalAnswer(ra.thought(), observation));
                trace.end(sub, ra.finalAnswer(), "OK", null);
                return ReActResult.done(ra.finalAnswer(), traceList);
            }

            // Action → Observation
            ReActAction action = new ReActAction(nameOf(ra), argsOf(ra));
            // F1：工具权限拦截（白名单/角色/scope 三维）；拒绝则不执行工具并写 TOOL_DENIED 审计
            String denyReason = permissionRegistry == null || governance == null
                    ? null
                    : permissionRegistry.denyReason(action.name(), governance.caller(), governance.agentName());
            if (denyReason != null) {
                observation = "TOOL_DENIED: " + denyReason;
                auditToolDenied(action.name(), governance, denyReason);
                log.warn("ReAct step {} iter {} 工具 {} 权限拒绝: {}", step.id(), i + 1, action.name(), denyReason);
            } else {
                try {
                    observation = toolExecutor.invoke(action, ctx);
                } catch (Exception e) {
                    observation = "ERROR: " + e.getMessage();
                    log.warn("ReAct step {} iter {} 工具 {} 调用失败: {}",
                            step.id(), i + 1, action.name(), e.getMessage());
                }
            }
            traceList.add(ra.toTAO(observation));
            trace.end(sub, observation, "OK", null);
            prompt = appendObservation(prompt, ra.thought(), action, observation);
        }

        log.warn("ReAct step {} 达到 maxIter={} 仍未结束，返回 truncated", step.id(), limit);
        return ReActResult.truncated(traceList);
    }

    private String buildReActPrompt(PlanStep step, Perception ctx,
                                    List<String> allowedTools,
                                    List<ThoughtActionObservation> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前节点目标：").append(step.goal()).append("\n");
        sb.append("节点类型：").append(step.stepType()).append("\n");
        sb.append("用户意图：").append(ctx.intent()).append("\n");
        if (step.hint() != null) {
            sb.append("历史反思 hint：").append(step.hint()).append("\n");
        }
        sb.append("允许调用的工具：").append(allowedTools).append("\n\n");
        if (history.isEmpty()) {
            sb.append("历史轨迹：无（首轮）\n");
        } else {
            sb.append("历史轨迹：\n");
            for (int i = 0; i < history.size(); i++) {
                ThoughtActionObservation tao = history.get(i);
                sb.append("  [").append(i + 1).append("] thought=").append(tao.thought())
                        .append(" | action=").append(tao.actionName())
                        .append(" | observation=").append(tao.observation()).append("\n");
            }
        }
        sb.append("\n请输出下一步 ReAct JSON。");
        return sb.toString();
    }

    private String appendObservation(String prompt, String thought,
                                     ReActAction action, String observation) {
        return prompt + "\n[新增] thought=" + thought
                + " | action=" + action.name()
                + " | observation=" + observation
                + "\n请输出下一步 ReAct JSON。";
    }

    private ReActStep callReAct(String prompt) {
        String out = chat(REACT_SYSTEM, prompt);
        Map<String, Object> json = JsonExtractor.parseObject(out);
        if (json.isEmpty()) {
            throw new IllegalStateException("ReAct 输出无法解析: " + out);
        }
        String thought = str(json.get("thought"));
        Object actionObj = json.get("action");
        ReActStep.Action action = null;
        if (actionObj instanceof Map<?, ?> am && am.get("name") != null) {
            action = new ReActStep.Action(str(am.get("name")), str(am.get("args")));
        }
        String finalAnswer = str(json.get("finalAnswer"));
        if (finalAnswer.isBlank()) {
            finalAnswer = null;
        }
        return new ReActStep(thought, action, finalAnswer);
    }

    /** F1：TOOL_DENIED 审计落库（eventType=TOOL_DENIED，含 toolName/userId/reason）。 */
    private void auditToolDenied(String toolName, ReActGovernance governance, String reason) {
        if (auditLog == null) {
            return;
        }
        try {
            String userId = governance.caller() == null ? null : governance.caller().userId();
            auditLog.log(userId, governance.sessionId(), "TOOL_DENIED", "tool_permission",
                    "WARN", "tool=" + toolName, "BLOCKED",
                    "toolName=" + toolName + " userId=" + userId + " reason=" + reason);
        } catch (Exception e) {
            log.warn("TOOL_DENIED 审计写入失败 tool={}: {}", toolName, e.getMessage());
        }
    }

    private static String nameOf(ReActStep ra) {
        return ra.action() == null ? null : ra.action().name();
    }

    private static String argsOf(ReActStep ra) {
        return ra.action() == null ? null : ra.action().args();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }

    private String chat(String system, String user) {
        var resp = model.call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }
}
