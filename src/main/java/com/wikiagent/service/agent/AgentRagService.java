package com.wikiagent.service.agent;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.infrastructure.trace.RagTraceRecorder;
import com.wikiagent.service.chat.ChatStreamer;
import com.wikiagent.service.chat.FallbackAnswerService;
import com.wikiagent.service.chat.PromptComposer;
import com.wikiagent.service.chat.SseSender;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agentic RAG 编排（业界 Adaptive RAG + 查询规划 + CRAG 纠正式检索的编排式实现）：
 * 1. 路由 + 查询规划：LLM 判断是否需要检索，闲聊直答；知识库问题拆分为 1~3 个检索查询
 * 2. 迭代检索：每轮多查询混合检索（BM25+向量 RRF → 父文档替换），跨轮去重累积
 * 3. 充分性评估（CRAG）：证据不足且未达轮数上限时，用改写查询自动重检
 * 4. 最终回答：汇总证据流式生成
 * 每一步均通过 SSE 推送 stage 事件；任一 LLM 决策失败自动降级，不阻断链路。
 */
@Service
public class AgentRagService {

    private static final Logger log = LoggerFactory.getLogger(AgentRagService.class);

    /** 路由 + 查询规划（一次调用同时输出 mode 与 queries）。 */
    static final String PLANNER_SYSTEM = """
            你是企业知识库系统的查询路由与规划模块。分析用户输入，只输出一个 JSON，不要输出任何其他文字：
            {"mode":"direct","queries":[]} 或 {"mode":"search","queries":["查询1","查询2"]} 或 {"mode":"web","queries":[]}
            判断规则：
            - 问候、寒暄、夸奖道谢、询问你的身份能力等与知识库内容无关的输入 → mode=direct（无需检索，直接对话回答）
            - 仅当问题明确需要"实时/最新外部信息"且知识库不可能包含时 → mode=web（跳过知识库检索，直接联网搜索回答）。
              典型场景：天气、新闻、股价、赛事比分、节假日安排等。
              注意：人名、公司制度、业务流程、产品信息、内部文档等可能存在于企业知识库的内容，
              即使看起来像通用问题，也应优先 mode=search 尝试检索知识库，而非 mode=web。
            - 其他需要知识库才能回答的问题 → mode=search，并将问题拆分为 1~3 个独立的检索查询：
              * 复杂/多要点问题按子问题拆分；简单问题只输出 1 个查询
              * 补全代词与省略的指代，使每个查询不依赖上下文也能被理解
              * 保留原文关键术语、产品名、专有名词，不要同义替换
            """;

    /** 检索充分性评估（CRAG 的评估器角色）。 */
    static final String GRADER_SYSTEM = """
            你是企业知识库的检索质量评估器。根据用户问题与已检索到的参考资料，判断资料是否足以回答问题：
            - sufficient=true：资料包含直接回答问题的关键事实
            - sufficient=false：资料为空、不相关、只覆盖部分要点或缺少关键信息
            只输出一个 JSON，不要输出任何其他文字：
            {"sufficient":true,"refinedQuery":""}
            当 sufficient=false 时，在 refinedQuery 中给出一个更适合下一轮检索的改写查询
            （补全关键术语、换一个检索角度）；sufficient=true 时 refinedQuery 留空。
            """;

    /** direct 模式（闲聊/元问题）的系统提示。 */
    static final String DIRECT_SYSTEM = """
            你是企业知识库应用的助手。用户输入不是知识库检索类问题（如问候、寒暄、询问你的能力），
            请自然、友好、简明地用简体中文回应；不要编造知识库内容，如涉及知识库问题可提示用户换一种问法。
            """;

    private final WikiAgentProperties props;
    private final ChatModel chatModel;
    private final RetrievalService retrieval;
    private final QueryRewriteService rewriter;
    private final ChatStreamer streamer;
    private final FallbackAnswerService fallback;
    private final RagTraceRecorder traceRecorder;

    public AgentRagService(WikiAgentProperties props, ChatModel chatModel, RetrievalService retrieval,
                           QueryRewriteService rewriter, ChatStreamer streamer,
                           FallbackAnswerService fallback, RagTraceRecorder traceRecorder) {
        this.props = props;
        this.chatModel = chatModel;
        this.retrieval = retrieval;
        this.rewriter = rewriter;
        this.streamer = streamer;
        this.fallback = fallback;
        this.traceRecorder = traceRecorder;
    }

    /** 路由+规划的决策结果。 */
    record Plan(String mode, List<String> queries) {
    }

    /** 充分性评估结果。 */
    record Grade(boolean sufficient, String refinedQuery) {
    }

    /** 运行完整的 Agentic RAG 流程。异常向上抛出，由 ChatService 统一转 error 事件。 */
    public void run(String userId, String sessionId, String question, SseSender sse) {
        int maxRounds = Math.max(1, props.agent().maxRounds());

        // 1. 路由 + 查询规划（失败降级为单查询检索）
        sse.send("stage", Map.of("stage", "routing"));
        Plan plan = plan(question);
        if ("direct".equals(plan.mode())) {
            log.debug("路由结果: direct，跳过检索");
            traceRecorder.record(userId, sessionId, "agent-rag-routing", "plan",
                    question, "mode=direct", "OK", null);
            sse.send("stage", Map.of("stage", "generating", "mode", "direct"));
            streamer.stream(new Prompt(List.of(
                    new SystemMessage(DIRECT_SYSTEM),
                    new UserMessage(question))), sse);
            return;
        }
        if ("web".equals(plan.mode())) {
            log.debug("路由结果: web，跳过知识库检索直接联网兜底");
            traceRecorder.record(userId, sessionId, "agent-rag-routing", "plan",
                    question, "mode=web", "OK", null);
            fallback.answer(question, sse);
            return;
        }
        traceRecorder.record(userId, sessionId, "agent-rag-routing", "plan",
                question, "mode=search, queries=" + plan.queries(), "OK", null);

        // 2. 迭代检索 + 充分性评估（CRAG 循环）
        RetrievalService.Accumulator acc = retrieval.newAccumulator();
        List<String> queries = plan.queries();
        RetrievalService.RetrievalResult result = null;
        Grade lastGrade = null;
        for (int round = 1; round <= maxRounds; round++) {
            sse.send("stage", Map.of("stage", "retrieving", "round", round, "queries", queries));
            try {
                retrieval.search(acc, queries);
            } catch (Exception e) {
                traceRecorder.record(userId, sessionId, "agent-rag-retrieval-r" + round, "tool_call",
                        String.join(" / ", queries), null, "ERROR", e.getMessage());
                throw e;
            }
            result = retrieval.assemble(acc);
            traceRecorder.record(userId, sessionId, "agent-rag-retrieval-r" + round, "tool_call",
                    String.join(" / ", queries),
                    "sources=" + result.sources().size() + (result.context().isEmpty() ? ", context=EMPTY" : ""),
                    "OK", null);

            if (result.context().isEmpty()) {
                if (round >= maxRounds) {
                    break; // 各轮均无证据
                }
                // 证据为空：换一种表述重检（CRAG 的纠正式回退改写）
                queries = List.of(rewriter.rewrite(question));
                log.debug("第 {} 轮证据为空，改写后重检: {}", round, queries);
                continue;
            }

            // 非空即评估（末轮也评估）：末轮评估的唯一用途是判定"弱命中→走未命中兜底"
            sse.send("stage", Map.of("stage", "grading", "round", round));
            Grade grade = grade(question, result.context());
            lastGrade = grade;
            if (grade.sufficient()) {
                log.debug("第 {} 轮证据充分，结束检索", round);
                break;
            }
            if (round >= maxRounds || grade.refinedQuery() == null || grade.refinedQuery().isBlank()) {
                log.debug("第 {} 轮证据不足且无法继续重检，将走未命中兜底", round);
                break;
            }
            queries = List.of(grade.refinedQuery());
            log.debug("第 {} 轮证据不足，改写查询重检: {}", round, queries);
        }

        // v4 §6.6 检索埋点（最终进入生成的来源，循环结束后只记一次）
        retrieval.recordRetrievalMetrics(result);
        sse.send("sources", result.sources());

        // 3. 未命中判定：证据为空，或末轮评估仍判证据不足（弱命中）→ 两级兜底
        //    说明：Milvus hybridSearch 无相似度阈值、总会返回 topK 近邻，
        //    因此"检索不到"不能只看结果是否为空，必须以 CRAG 评估结论为准。
        boolean weakMiss = lastGrade != null && !lastGrade.sufficient();
        if (result.context().isEmpty() || weakMiss) {
            String reason = result.context().isEmpty() ? "empty_context" : "insufficient_evidence";
            traceRecorder.record(userId, sessionId, "agent-rag-fallback", "generate",
                    question, "fallback_reason=" + reason, "OK", null);
            fallback.answer(question, sse);
            return;
        }

        // 4. 证据充分：组装证据流式生成
        traceRecorder.record(userId, sessionId, "agent-rag-generate", "generate",
                question, "sources=" + result.sources().size(), "OK", null);
        sse.send("stage", Map.of("stage", "generating"));
        streamer.stream(new Prompt(List.of(
                new SystemMessage(PromptComposer.SYSTEM),
                new UserMessage(PromptComposer.user(result.context(), question)))), sse);
    }

    /** 路由+规划：LLM 调用失败或输出异常时降级为 search + 原始问题。 */
    Plan plan(String question) {
        try {
            String out = call(PLANNER_SYSTEM, question);
            Map<String, Object> json = JsonExtractor.parseObject(out);
            if (json.isEmpty()) {
                log.warn("路由规划输出无法解析，降级为默认检索: {}", out);
                return new Plan("search", List.of(question));
            }
            if ("direct".equals(json.get("mode"))) {
                return new Plan("direct", List.of());
            }
            if ("web".equals(json.get("mode"))) {
                return new Plan("web", List.of());
            }
            List<String> queries = normalizeQueries(json.get("queries"), question, props.agent().maxQueriesPerRound());
            return new Plan("search", queries);
        } catch (Exception e) {
            log.warn("路由规划调用失败，降级为默认检索: {}", e.getMessage());
            return new Plan("search", List.of(question));
        }
    }

    /** 充分性评估：失败时视为充分，不阻断回答。 */
    Grade grade(String question, String context) {
        try {
            String user = "用户问题：" + question.strip() + "\n\n已检索到的参考资料：\n" + context.strip()
                    + "\n\n请判断以上资料是否足以回答问题，只输出 JSON。";
            String out = call(GRADER_SYSTEM, user);
            Map<String, Object> json = JsonExtractor.parseObject(out);
            if (json.isEmpty()) {
                log.warn("评估输出无法解析，视为充分: {}", out);
                return new Grade(true, null);
            }
            boolean sufficient = Boolean.TRUE.equals(json.get("sufficient"));
            Object refined = json.get("refinedQuery");
            return new Grade(sufficient, refined == null ? null : String.valueOf(refined));
        } catch (Exception e) {
            log.warn("评估调用失败，视为充分: {}", e.getMessage());
            return new Grade(true, null);
        }
    }

    private String call(String system, String user) {
        var resp = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }

    /** 归一化规划出的查询列表：仅保留非空字符串、去重、限量；全部无效时回退原始问题。 */
    private List<String> normalizeQueries(Object raw, String fallback, int limit) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                String q = String.valueOf(o).strip();
                if (!q.isEmpty() && !out.contains(q)) {
                    out.add(q);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out.isEmpty() ? List.of(fallback) : List.copyOf(out);
    }
}
