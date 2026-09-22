package com.wikiagent.service.chat;

import com.wikiagent.application.agent.AgentOrchestrator;
import com.wikiagent.application.multiagent.MultiAgentOrchestrator;
import com.wikiagent.application.multiagent.TenantKey;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.dto.ChatRequest;
import com.wikiagent.infrastructure.gateway.GuardrailAdvisorChain;
import com.wikiagent.service.agent.AgentRagService;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 对话编排入口（运行于 chatExecutor）。
 * <p>
 * <b>v5 §19 安全网关</b>：所有路径先过 {@link GuardrailAdvisorChain#checkInput}，
 * BLOCK 直接返回 blocked 事件不触达 LLM；SANITIZE 后用脱敏内容继续。
 * <p>
 * <b>四层链路切换</b>：
 * <ol>
 *   <li>{@code wikiagent.pero.enabled=true} + {@code wikiagent.multi-agent.enabled=true}
 *       且请求含 domain/subDomain → v6 MultiAgentOrchestrator（9×6×5 垂直隔离）</li>
 *   <li>{@code wikiagent.pero.enabled=false} → v1-v2 {@link AgentOrchestrator}
 *       （Route→Plan→Execute→Reflexion 单 Agent 主循环）</li>
 *   <li>{@code wikiagent.agent.enabled=true} → §2 Agentic RAG（AgentRagService）</li>
 *   <li>其他 → 简单 RAG 固定管道（改写 → 检索 → 生成）</li>
 * </ol>
 * 通过 SseEmitter 推送事件：stage / sources / delta / done / error / blocked。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final WikiAgentProperties props;
    private final AgentRagService agentRag;
    private final QueryRewriteService rewriter;
    private final RetrievalService retrieval;
    private final ChatStreamer streamer;
    private final MultiAgentOrchestrator multiAgent;
    private final ObjectProvider<AgentOrchestrator> v1v2OrchestratorProvider;
    private final GuardrailAdvisorChain guardrailChain;
    private final FallbackAnswerService fallback;
    private final ChatHistoryService historyService;
    private final boolean multiAgentEnabled;
    private final boolean peroEnabled;

    public ChatService(WikiAgentProperties props, AgentRagService agentRag, QueryRewriteService rewriter,
                       RetrievalService retrieval, ChatStreamer streamer, MultiAgentOrchestrator multiAgent,
                       ObjectProvider<AgentOrchestrator> v1v2OrchestratorProvider,
                       GuardrailAdvisorChain guardrailChain,
                       FallbackAnswerService fallback,
                       ChatHistoryService historyService,
                       @Value("${wikiagent.multi-agent.enabled:true}") boolean multiAgentEnabled,
                       @Value("${wikiagent.pero.enabled:true}") boolean peroEnabled) {
        this.props = props;
        this.agentRag = agentRag;
        this.rewriter = rewriter;
        this.retrieval = retrieval;
        this.streamer = streamer;
        this.multiAgent = multiAgent;
        this.v1v2OrchestratorProvider = v1v2OrchestratorProvider;
        this.guardrailChain = guardrailChain;
        this.fallback = fallback;
        this.historyService = historyService;
        this.multiAgentEnabled = multiAgentEnabled;
        this.peroEnabled = peroEnabled;
    }

    @Async("chatExecutor")
    public void chat(ChatRequest request, SseEmitter emitter) {
        SseSender sse = new SseSender(emitter);
        String rawQuestion = request.question() == null ? "" : request.question().strip();
        String userId = request.identity() == null ? "anonymous" : request.identity();
        try {
            if (rawQuestion.isEmpty()) {
                sse.send("error", Map.of("message", "问题不能为空"));
                sse.complete();
                return;
            }

            // 业务会话 ID：优先使用前端显式传入值（页面会话内复用，贯穿记忆/trace/审计/反馈），
            // 缺省时生成唯一 ID。无论来源如何，都通过 SSE session 事件回传给前端展示。
            String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                    ? "s-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                    : request.sessionId().strip();
            sse.send("session", Map.of("sessionId", sessionId));

            // 持久化用户消息
            historyService.save(sessionId, "user", rawQuestion);

            // 用历史记录装饰器替换 sse，拦截 delta 积累助手文本并在 done 时持久化
            sse = new HistorySseSender(emitter, historyService, sessionId);

            // === v5 §19 输入安全网关（注入检测 / PII 脱敏 / 合规）===
            GuardrailAdvisorChain.ChainResult inputResult =
                    guardrailChain.checkInput(rawQuestion, userId, sessionId);
            if (!inputResult.passed()) {
                log.warn("输入被安全网关拦截 userId={} reason={}", userId, inputResult.blockedReason());
                sse.send("blocked", Map.of(
                        "reason", inputResult.blockedReason() == null ? "内容不合规" : inputResult.blockedReason(),
                        "violationType", inputResult.violationType() == null ? "UNKNOWN" : inputResult.violationType()));
                sse.complete();
                return;
            }
            String question = inputResult.content() == null ? rawQuestion : inputResult.content().strip();

            // === 优先级 1：v6 Multi-Agent（PERO 开启 + 9×6 路由字段齐全）===
            if (peroEnabled && multiAgentEnabled && multiAgent.enabled()
                    && request.domain() != null && request.subDomain() != null) {
                String identity = request.identity() == null ? "any" : request.identity();
                TenantKey key = new TenantKey(request.domain(), request.subDomain(), identity);
                multiAgent.run(key, userId, sessionId, question, sse);
                return;
            }

            // === 优先级 2：v1-v2 Plan-Execute-Reflexion 主循环（pero.enabled=false）===
            AgentOrchestrator v1v2 = v1v2OrchestratorProvider.getIfAvailable();
            if (!peroEnabled && v1v2 != null) {
                v1v2.run(userId, sessionId, question, sse);
                return;
            }

            // === 优先级 3：§2 Agentic RAG ===
            if (props.agent() != null && props.agent().enabled()) {
                agentRag.run(userId, sessionId, question, sse);
                return;
            }

            // === 优先级 4：简单 RAG 固定管道 ===
            legacyChat(question, sse);
        } catch (Exception e) {
            log.error("对话处理失败", e);
            sse.send("error", Map.of("message", "对话处理失败: " + messageOf(e)));
            sse.complete();
        }
    }

    /** 简单 RAG 固定管道（agent.enabled=false 时的回退链路）。 */
    private void legacyChat(String question, SseSender sse) {
        // 1. 查询改写
        sse.send("stage", Map.of("stage", "rewriting"));
        String rewritten = rewriter.rewrite(question);

        // 2. 混合检索 + 父文档替换
        sse.send("stage", Map.of("stage", "retrieving", "rewrittenQuery", rewritten));
        RetrievalService.RetrievalResult result = retrieval.retrieve(rewritten);
        sse.send("sources", result.sources());

        if (result.context().isEmpty()) {
            // 知识库完全未命中：两级兜底（联网搜索 → 模型自身知识；均关闭时拒答）
            fallback.answer(question, sse);
            return;
        }

        // 3. 组装 Prompt 并流式生成
        sse.send("stage", Map.of("stage", "generating"));
        streamer.stream(new Prompt(List.of(
                new SystemMessage(PromptComposer.SYSTEM),
                new UserMessage(PromptComposer.user(result.context(), question)))), sse);
    }

    private static String messageOf(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
