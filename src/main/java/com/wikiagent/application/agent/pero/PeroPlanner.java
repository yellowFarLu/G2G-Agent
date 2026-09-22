package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v6 §20.4 Plan 阶段生成器：感知上下文 → 一次性产出 {@link Plan}（Plan-and-Solve arXiv:2305.04091）。
 * <p>
 * 与 §2 既有 {@code PlanAndExecutePlanner} 的区别：
 * <ul>
 *   <li>本类输出 v6 的 {@link PlanStep}（含 stepType / hint），用于 §20.6 工具白名单按节点类型路由</li>
 *   <li>失败降级为单步计划（原问题作为单一节点），不阻断链路</li>
 * </ul>
 * 提示词要求 LLM 输出结构化 JSON 数组，每项含 id/goal/stepType。
 */
@Service
public class PeroPlanner {

    private static final Logger log = LoggerFactory.getLogger(PeroPlanner.class);

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[\\s*[\\s\\S]*\\s*\\]");

    static final String PLANNER_SYSTEM = """
            你是企业知识库 Agent 的任务规划模块。基于用户输入与感知上下文，把任务切成 1-5 个有序子任务节点。
            只输出一个 JSON 数组，不要输出任何其他文字。每个元素形如：
            {"id":"step1","goal":"检索商家入驻流程","stepType":"search_kb"}
            stepType 取值：search_kb | search_history | update_profile | tool | generate
            - search_kb：检索知识库
            - search_history：检索历史事件库
            - update_profile：更新用户档案
            - tool：其他工具调用
            - generate：最终答案生成
            规则：
            - 简单闲聊问题 → 单一 generate 节点
            - 知识问答 → 1-2 个 search_kb + 1 个 generate
            - 多步业务问题 → 按依赖顺序拆分，最后必须有 1 个 generate
            """;

    private final ChatModel chatModel;

    public PeroPlanner(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** Plan 阶段：LLM 调用失败或输出异常时降级为单步 generate 计划。 */
    public Plan plan(Perception ctx) {
        try {
            String out = call(PLANNER_SYSTEM, ctx);
            List<PlanStep> steps = parse(out);
            if (steps.isEmpty()) {
                log.warn("PERO 规划输出无法解析，降级为单步 generate: {}", out);
                return new Plan(List.of(new PlanStep("step1", ctx.userInput(), "generate")));
            }
            return new Plan(steps);
        } catch (Exception e) {
            log.warn("PERO 规划调用失败，降级为单步 generate: {}", e.getMessage());
            return new Plan(List.of(new PlanStep("step1", ctx.userInput(), "generate")));
        }
    }

    private String call(String system, Perception ctx) {
        String user = "用户输入：" + ctx.userInput()
                + "\n用户意图：" + ctx.intent()
                + "\n请输出子任务节点 JSON 数组。";
        var resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }

    /** 解析 LLM 输出为 PlanStep 列表：兼容 ```json 代码块与裸 JSON。 */
    @SuppressWarnings("unchecked")
    static List<PlanStep> parse(String out) {
        if (out == null || out.isBlank()) {
            return List.of();
        }
        Matcher m = JSON_ARRAY.matcher(out);
        if (!m.find()) {
            return List.of();
        }
        String json = m.group();
        List<Object> list = com.wikiagent.service.agent.JsonExtractor.parseArray(json);
        List<PlanStep> steps = new ArrayList<>();
        int idx = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) {
                continue;
            }
            String id = str(map.get("id"), "step" + idx);
            String goal = str(map.get("goal"), "");
            String stepType = str(map.get("stepType"), "tool");
            if (goal.isBlank()) {
                continue;
            }
            steps.add(new PlanStep(id, goal, stepType));
            idx++;
        }
        return steps;
    }

    private static String str(Object o, String def) {
        return o == null || String.valueOf(o).isBlank() ? def : String.valueOf(o).strip();
    }
}
