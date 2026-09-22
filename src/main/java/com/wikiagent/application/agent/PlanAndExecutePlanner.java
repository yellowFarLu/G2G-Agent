package com.wikiagent.application.agent;

import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.routing.RouteDecision;
import com.wikiagent.service.agent.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1-v2 §2.3 Plan-and-Execute 规划器。
 * <p>
 * 基于 Plan-and-Solve 论文（arXiv:2305.04091）：先用 LLM 一次性产出任务计划 List&lt;PlanStep&gt;，
 * 再按序进入 Execute 阶段。与 v6 {@code PeroPlanner} 的区别：
 * <ul>
 *   <li>本类入参为 {@link RouteDecision}（v1-v2 §7 LLM 路由结果），而非 v6 的 Perception</li>
 *   <li>LLM 输出字段为 {@code type}（映射到 {@link PlanStep#stepType()}）</li>
 *   <li>失败降级为单步 generate 计划，不阻断链路</li>
 * </ul>
 * 仅在 v1-v2 路径激活（{@code wikiagent.pero.enabled=false}）。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "false")
public class PlanAndExecutePlanner {

    private static final Logger log = LoggerFactory.getLogger(PlanAndExecutePlanner.class);

    static final String PLANNER_SYSTEM = """
            你是企业知识库 Agent 的任务规划模块。基于用户输入与路由结果，把任务切成 1-5 个有序子任务节点。
            只输出一个 JSON 数组，不要输出任何其他文字。每个元素形如：
            {"id":"step1","goal":"检索商家入驻流程","type":"search_kb"}
            type 取值：search_kb | search_history | update_profile | read_handover | list_abandoned_paths | generate
            - search_kb：检索知识库
            - search_history：检索历史事件库
            - update_profile：更新用户档案（goal 中以 "key=value" 形式给出字段名与值）
            - read_handover：读取交接清单
            - list_abandoned_paths：列出放弃路径
            - generate：最终答案生成
            规则：
            - 简单闲聊问题 → 单一 generate 节点
            - 知识问答 → 1-2 个 search_kb + 1 个 generate
            - 多步业务问题 → 按依赖顺序拆分，最后必须有 1 个 generate
            """;

    private final ChatModel chatModel;

    public PlanAndExecutePlanner(@Lazy ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Plan 阶段：基于用户输入与路由决策生成任务计划列表。
     * <p>
     * LLM 调用失败或输出异常时降级为单步 generate 计划。
     *
     * @param userInput 用户输入
     * @param route     路由决策（含意图与是否复杂）
     * @return 有序 PlanStep 列表（至少 1 个）
     */
    public List<PlanStep> plan(String userInput, RouteDecision route) {
        try {
            String out = call(PLANNER_SYSTEM, userInput, route);
            List<PlanStep> steps = parse(out);
            if (steps.isEmpty()) {
                log.warn("规划输出无法解析，降级为单步 generate: {}", out);
                return defaultPlan(userInput);
            }
            return steps;
        } catch (Exception e) {
            log.warn("规划调用失败，降级为单步 generate: {}", e.getMessage());
            return defaultPlan(userInput);
        }
    }

    private String call(String system, String userInput, RouteDecision route) {
        String intent = route == null || route.intent() == null ? "unknown" : route.intent().code();
        String complexity = route != null && route.isComplex() ? "complex" : "simple";
        String user = "用户输入：" + userInput
                + "\n用户意图：" + intent
                + "\n任务复杂度：" + complexity
                + "\n请输出子任务节点 JSON 数组。";
        var resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }

    /** 解析 LLM 输出为 PlanStep 列表：兼容 ```json 代码块与裸 JSON。 */
    static List<PlanStep> parse(String out) {
        if (out == null || out.isBlank()) {
            return List.of();
        }
        List<Object> list = JsonExtractor.parseArray(out);
        List<PlanStep> steps = new ArrayList<>();
        int idx = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) {
                continue;
            }
            String id = str(map.get("id"), "step" + idx);
            String goal = str(map.get("goal"), "");
            // LLM 输出 type 字段，映射到 PlanStep.stepType
            String stepType = str(map.get("type"), "tool");
            if (stepType.isBlank() || "null".equalsIgnoreCase(stepType)) {
                stepType = str(map.get("stepType"), "tool");
            }
            if (goal.isBlank()) {
                continue;
            }
            steps.add(new PlanStep(id, goal, stepType));
            idx++;
        }
        return steps;
    }

    /** 默认单步 generate 计划（降级兜底）。 */
    private static List<PlanStep> defaultPlan(String userInput) {
        return List.of(new PlanStep("step1", userInput, "generate"));
    }

    private static String str(Object o, String def) {
        return o == null || String.valueOf(o).isBlank() ? def : String.valueOf(o).strip();
    }
}
