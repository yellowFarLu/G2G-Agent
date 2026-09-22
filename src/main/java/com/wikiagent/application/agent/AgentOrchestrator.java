package com.wikiagent.application.agent;

import com.wikiagent.domain.agent.NodeExecution;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.memory.HandoverRepository;
import com.wikiagent.domain.memory.ShortTermMemoryPort;
import com.wikiagent.domain.routing.LlmRouterPort;
import com.wikiagent.domain.routing.RouteDecision;
import com.wikiagent.infrastructure.gateway.GuardrailAdvisorChain;
import com.wikiagent.infrastructure.tool.ToolRegistryImpl;
import com.wikiagent.service.chat.PromptComposer;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1-v2 §2 主 Agent 编排器（Plan-and-Execute + Reflexion 主循环）。
 * <p>
 * 结合 Plan-and-Solve（arXiv:2305.04091）+ Reflexion（arXiv:2303.11366）：
 * <ol>
 *   <li><b>Route</b>：通过 {@link LlmRouterPort} 做意图识别 + 模型路由</li>
 *   <li><b>Plan</b>：{@link PlanAndExecutePlanner} 一次性产出 {@code List<PlanStep>}</li>
 *   <li><b>Execute</b>：顺序执行每个节点，{@link NodeExecutor} 按类型路由工具</li>
 *   <li><b>Reflexion</b>：节点失败时 {@link ReflexionService} 反思，携带 hint 重试一次</li>
 *   <li><b>Generate</b>：汇总已执行节点产出，{@link ChatModel} 生成最终答案</li>
 *   <li><b>Stream</b>：通过 {@link SseSender} 推送 stage / delta / done 事件</li>
 * </ol>
 * <p>
 * 与 v6 {@code PeroAgent} 的区别：本类是单 Agent 顺序执行 + 单步 NodeExecutor + 一次性 Reflexion；
 * v6 把 NodeExecutor 替换为节点内 ReAct 多轮循环，把 Reflexion 拓展为 Reflect→Optimize 闭环。
 * <p>
 * 开关：{@code wikiagent.pero.enabled=false} 时由本类承接 v1-v2 路径。
 * <p>
 * 端口容错：{@link LlmRouterPort} / {@link ShortTermMemoryPort} / {@link HandoverRepository}
 * 的实现 Bean 可能尚未注入（仅在 docs 中规划），故用 {@link ObjectProvider} 容错注入，
 * 缺失时降级为 fallback 路由 / 跳过记忆持久化，不阻断主循环。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "false")
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** 失败节点最大重试次数。 */
    private static final int MAX_RETRY = 1;

    private final ObjectProvider<LlmRouterPort> routerProvider;
    private final PlanAndExecutePlanner planner;
    private final NodeExecutor nodeExecutor;
    private final ToolRegistryImpl tools;
    private final ObjectProvider<ShortTermMemoryPort> shortTermMemoryProvider;
    private final ObjectProvider<HandoverRepository> handoverProvider;
    private final ReflexionService reflexionService;
    private final ChatModel chatModel;
    private final ObjectProvider<GuardrailAdvisorChain> guardrailProvider;

    public AgentOrchestrator(ObjectProvider<LlmRouterPort> routerProvider,
                             PlanAndExecutePlanner planner,
                             NodeExecutor nodeExecutor,
                             ToolRegistryImpl tools,
                             ObjectProvider<ShortTermMemoryPort> shortTermMemoryProvider,
                             ObjectProvider<HandoverRepository> handoverProvider,
                             ReflexionService reflexionService,
                             @Lazy ChatModel chatModel,
                             ObjectProvider<GuardrailAdvisorChain> guardrailProvider) {
        this.routerProvider = routerProvider;
        this.planner = planner;
        this.nodeExecutor = nodeExecutor;
        this.tools = tools;
        this.shortTermMemoryProvider = shortTermMemoryProvider;
        this.handoverProvider = handoverProvider;
        this.reflexionService = reflexionService;
        this.chatModel = chatModel;
        this.guardrailProvider = guardrailProvider;
    }

    /**
     * 运行 v1-v2 主 Agent 循环。
     * <p>
     * 异常向上抛出，由 {@code ChatService} 统一转 SSE error 事件。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @param userInput 用户输入
     * @param sse       SSE 推送器
     */
    public void run(String userId, String sessionId, String userInput, SseSender sse) {
        // === 1. ROUTE（意图识别 + 模型路由）===
        sse.send("stage", Map.of("stage", "routing"));
        RouteDecision route = route(userInput);
        log.debug("路由结果: intent={} model={} complex={}",
                route.intent(), route.modelName(), route.isComplex());

        // === 2. PLAN（一次性生成任务计划）===
        sse.send("stage", Map.of("stage", "planning"));
        List<PlanStep> steps = planner.plan(userInput, route);
        log.debug("规划生成 {} 个步骤", steps.size());

        // 初始化交接清单（端口缺失则跳过）
        HandoverRepository handover = handoverProvider.getIfAvailable();
        if (handover != null) {
            handover.init(userId, sessionId, userInput);
        }

        // === 3-4. EXECUTE → REFLEXION（顺序执行 + 失败反思重试）===
        List<NodeExecution> executed = new ArrayList<>();
        List<String> abandoned = new ArrayList<>();
        for (PlanStep step : steps) {
            sse.send("stage", Map.of("stage", "executing", "nodeId", step.id(), "nodeType", step.stepType()));
            NodeExecution exec = executeWithRetry(step, userId, sessionId, route);

            if (exec.success()) {
                executed.add(exec);
                if (handover != null) {
                    handover.addExecutedNode(userId, sessionId, step.id(), step.goal());
                    if (exec.outputs() != null && exec.outputs().get("result") != null) {
                        handover.addDataReference(userId, sessionId, step.id(),
                                String.valueOf(exec.outputs().get("result")));
                    }
                }
            } else {
                String reason = exec.errorMessage() == null ? "未知错误" : exec.errorMessage();
                abandoned.add(step.id() + ": " + reason);
                if (handover != null) {
                    handover.addAbandonedPath(userId, sessionId, step.id(), reason);
                }
            }
        }

        // === 5. GENERATE（汇总已执行节点产出，生成最终答案）===
        sse.send("stage", Map.of("stage", "generating"));
        String answer = generateAnswer(userInput, executed);

        // v5 §19 输出安全网关（PII 脱敏 / 系统提示泄露 / 毒性检测）。
        // 本路径答案为整段生成，可在发送前统一检测；流式路径的逐 token 检测不在此覆盖。
        GuardrailAdvisorChain outputGuard = guardrailProvider.getIfAvailable();
        if (outputGuard != null) {
            GuardrailAdvisorChain.ChainResult outputResult =
                    outputGuard.checkOutput(answer, userId, sessionId);
            if (!outputResult.passed()) {
                log.warn("输出被安全网关拦截 userId={} type={}", userId, outputResult.violationType());
                sse.send("blocked", Map.of(
                        "reason", outputResult.blockedReason() == null ? "输出内容不合规" : outputResult.blockedReason(),
                        "violationType", outputResult.violationType() == null ? "UNKNOWN" : outputResult.violationType()));
                sse.complete();
                return;
            }
            answer = outputResult.content() == null ? answer : outputResult.content();
        }
        sse.send("delta", Map.of("text", answer));

        // === 6. 持久化到短期记忆（端口缺失则跳过）===
        ShortTermMemoryPort shortTermMemory = shortTermMemoryProvider.getIfAvailable();
        if (shortTermMemory != null) {
            try {
                shortTermMemory.save(userId, sessionId, "user", userInput);
                shortTermMemory.save(userId, sessionId, "assistant", answer);
            } catch (Exception e) {
                log.warn("短期记忆持久化失败: {}", e.getMessage());
            }
        }

        sse.send("done", Map.of());
    }

    /** 路由：端口缺失时降级为 fallback 路由决策。 */
    private RouteDecision route(String userInput) {
        LlmRouterPort router = routerProvider.getIfAvailable();
        if (router == null) {
            log.debug("LlmRouterPort 未实现，使用 fallback 路由");
            return RouteDecision.fallback();
        }
        try {
            RouteDecision decision = router.route(userInput);
            return decision == null ? RouteDecision.fallback() : decision;
        } catch (Exception e) {
            log.warn("路由调用失败，使用 fallback: {}", e.getMessage());
            return RouteDecision.fallback();
        }
    }

    /** 执行节点，失败时反思并携带 hint 重试一次。 */
    private NodeExecution executeWithRetry(PlanStep step, String userId, String sessionId,
                                           RouteDecision route) {
        NodeExecution exec = nodeExecutor.execute(step, userId, sessionId, tools);
        if (exec.success()) {
            return exec;
        }
        // 失败：反思 + 重试
        log.info("节点 {} 首次执行失败，进入反思重试: {}", step.id(), exec.errorMessage());
        try {
            String reflection = reflexionService.reflect(step, exec.errorMessage(), userId, sessionId);
            PlanStep retried = step.withHint(reflection);
            log.debug("节点 {} 携带 hint 重试: {}", step.id(), reflection);
            NodeExecution retry = nodeExecutor.execute(retried, userId, sessionId, tools);
            if (retry.success()) {
                return retry;
            }
            return retry; // 重试仍失败，返回重试结果
        } catch (Exception e) {
            log.warn("节点 {} 反思重试异常: {}", step.id(), e.getMessage());
            return exec; // 返回首次失败结果
        }
    }

    /** 汇总已执行节点产出，调用 ChatModel 生成最终答案。 */
    private String generateAnswer(String userInput, List<NodeExecution> executed) {
        String context = buildContext(executed);
        if (context.isBlank()) {
            return PromptComposer.NO_CONTEXT;
        }
        try {
            var resp = chatModel.call(new Prompt(List.of(
                    new SystemMessage(PromptComposer.SYSTEM),
                    new UserMessage(PromptComposer.user(context, userInput)))));
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
                return PromptComposer.NO_CONTEXT;
            }
            String answer = resp.getResult().getOutput().getText();
            return answer == null || answer.isBlank() ? PromptComposer.NO_CONTEXT : answer;
        } catch (Exception e) {
            log.warn("最终答案生成失败: {}", e.getMessage());
            return "答案生成失败: " + e.getMessage();
        }
    }

    /** 从已执行节点的产出中拼装上下文文本（检索类节点的 result 字段）。 */
    private String buildContext(List<NodeExecution> executed) {
        StringBuilder sb = new StringBuilder();
        for (NodeExecution exec : executed) {
            if (exec.outputs() == null) {
                continue;
            }
            Object result = exec.outputs().get("result");
            if (result instanceof String s && !s.isBlank()) {
                sb.append("[节点 ").append(exec.nodeId()).append("] ")
                        .append(exec.nodeType()).append('\n')
                        .append(s).append("\n\n");
            }
        }
        return sb.toString().strip();
    }
}
