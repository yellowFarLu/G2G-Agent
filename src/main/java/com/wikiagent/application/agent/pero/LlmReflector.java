package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.agent.Reflection;
import com.wikiagent.service.agent.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v6 §20.5 Reflector 的默认 LLM 实现。
 * <p>
 * Reflexion 论文（arXiv:2303.11366）：失败后用自然语言生成"反思"写入 episodic memory。
 * 本类调 {@code wikiagent.pero.reflect.model}（默认 qwen-3.8-max）输出结构化 Reflection。
 * <p>
 * v3-v5 实施时可用更复杂的提示工程或规则引擎替换；本类为 v6 自洽默认实现。
 */
@Component
public class LlmReflector implements Reflector {

    private static final Logger log = LoggerFactory.getLogger(LlmReflector.class);

    static final String REFLECT_SYSTEM = """
            你是企业知识库 Agent 的反思器。基于节点目标、ReAct 执行结果与感知上下文，输出结构化反思。
            只输出一个 JSON，不要输出任何其他文字：
            {"text":"...","needsRework":false,"reworkHint":"","planAdjustments":[]}
            字段说明：
            - text: 反思文本（写入 episodic memory 跨会话复用）
            - needsRework: 节点结果是否需要重做（true 时携带 reworkHint 进入下一轮 ReAct）
            - reworkHint: 重做时携带的 hint（如"上次检索结果不相关，应换关键词重试"）
            - planAdjustments: 对剩余 Plan 的增删改建议（JSON 数组，每项同 PlanStep 结构）
            """;

    static final String REFLECT_FAILURE_SYSTEM = """
            你是企业知识库 Agent 的失败反思器。基于节点目标、异常信息与感知上下文，判断是否重试。
            只输出一个 JSON，不要输出任何其他文字：
            {"text":"...","shouldRetry":false,"reason":"..."}
            字段说明：
            - text: 失败反思文本（写入 episodic memory 跨会话复用）
            - shouldRetry: 是否重新入队等下一轮重试（true 时本节点会被 plan.steps().add(0, step)）
            - reason: 不重试原因（写入 handover.abandonPath）
            重试判断：网络/超时类瞬时错误 shouldRetry=true；逻辑错误（如工具参数错误） shouldRetry=false
            """;

    private final ChatModel reflectModel;
    private final String modelName;

    public LlmReflector(ChatModel chatModel,
                        @Value("${wikiagent.pero.reflect.model:qwen-max}") String modelName) {
        // 注：v3-v5 实施时此处应为 DashScopeMultiModelFactory 按 modelName 路由的具体 ChatModel
        // v6 自洽默认使用注入的 ChatModel（即 spring.ai.dashscope.chat.options.model 配置）
        this.reflectModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public Reflection reflect(PlanStep step, ReActResult result, Perception ctx) {
        String user = "节点 id: " + step.id()
                + "\n节点目标: " + step.goal()
                + "\n节点类型: " + step.stepType()
                + "\n用户意图: " + ctx.intent()
                + "\n执行结果: done=" + result.done()
                + ", finalAnswer=" + result.finalAnswer()
                + ", 轨迹长度=" + (result.trace() == null ? 0 : result.trace().size())
                + "\n请输出反思 JSON。";
        try {
            String out = call(REFLECT_SYSTEM, user);
            Map<String, Object> json = JsonExtractor.parseObject(out);
            if (json.isEmpty()) {
                log.warn("反思输出无法解析，视为无需重做: {}", out);
                return Reflection.of(step.id(), "解析失败", false, null, List.of());
            }
            boolean needsRework = Boolean.TRUE.equals(json.get("needsRework"));
            String reworkHint = str(json.get("reworkHint"));
            List<PlanStep> adjustments = parseAdjustments(json.get("planAdjustments"));
            return Reflection.of(step.id(), str(json.get("text")), needsRework,
                    reworkHint.isBlank() ? null : reworkHint, adjustments);
        } catch (Exception e) {
            log.warn("反思调用失败，视为无需重做: {}", e.getMessage());
            return Reflection.of(step.id(), "调用失败: " + e.getMessage(), false, null, List.of());
        }
    }

    @Override
    public Reflection reflectOnFailure(PlanStep step, Throwable e, Perception ctx) {
        String user = "节点 id: " + step.id()
                + "\n节点目标: " + step.goal()
                + "\n用户意图: " + ctx.intent()
                + "\n异常信息: " + e.getMessage()
                + "\n请输出失败反思 JSON。";
        try {
            String out = call(REFLECT_FAILURE_SYSTEM, user);
            Map<String, Object> json = JsonExtractor.parseObject(out);
            if (json.isEmpty()) {
                log.warn("失败反思输出无法解析，默认不重试: {}", out);
                return Reflection.onFailure(step.id(), "解析失败", false, "解析失败");
            }
            boolean shouldRetry = Boolean.TRUE.equals(json.get("shouldRetry"));
            String reason = str(json.get("reason"));
            return Reflection.onFailure(step.id(), str(json.get("text")), shouldRetry, reason);
        } catch (Exception ex) {
            log.warn("失败反思调用失败，默认不重试: {}", ex.getMessage());
            return Reflection.onFailure(step.id(), "调用失败", false, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PlanStep> parseAdjustments(Object raw) {
        List<PlanStep> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        int idx = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) {
                continue;
            }
            String id = str(map.get("id"));
            if (id.isBlank()) {
                id = "adj" + idx;
            }
            String goal = str(map.get("goal"));
            String stepType = str(map.get("stepType"));
            if (goal.isBlank()) {
                continue;
            }
            out.add(new PlanStep(id, goal, stepType.isBlank() ? "tool" : stepType));
            idx++;
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }

    private String call(String system, String user) {
        var resp = reflectModel.call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }
}
