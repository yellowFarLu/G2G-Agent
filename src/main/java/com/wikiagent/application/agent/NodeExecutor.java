package com.wikiagent.application.agent;

import com.wikiagent.domain.agent.NodeExecution;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.infrastructure.tool.ListAbandonedPathTool;
import com.wikiagent.infrastructure.tool.ReadHandoverTool;
import com.wikiagent.infrastructure.tool.SearchHistoryTool;
import com.wikiagent.infrastructure.tool.SearchKnowledgeBaseTool;
import com.wikiagent.infrastructure.tool.UpdateUserProfileTool;
import com.wikiagent.infrastructure.tool.ToolRegistryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1-v2 §2.4 节点执行器。
 * <p>
 * 执行单个 {@link PlanStep}：按 step.stepType() 路由到对应工具，
 * 返回 {@link NodeExecution} 记录供交接清单与 trace 使用。
 * <p>
 * 与 v6 {@code ReActExecutor} 的区别：本类单步执行（plan 后顺序执行），
 * 不做节点内 ReAct 多轮循环。
 * <p>
 * 仅在 v1-v2 路径激活（{@code wikiagent.pero.enabled=false}）。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "false")
public class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    /** 用于从 update_profile 节点 goal 中提取 "key=value" 或 "key: value" 的正则。 */
    private static final Pattern KV_PATTERN = Pattern.compile("(\\w+)\\s*[:=]\\s*(.+)");

    /** 默认历史事件召回数量。 */
    private static final int DEFAULT_HISTORY_TOP_K = 5;

    /**
     * 执行单个 PlanStep。
     *
     * @param step      待执行节点
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @param tools     工具注册表
     * @return 节点执行结果
     */
    public NodeExecution execute(PlanStep step, String userId, String sessionId, ToolRegistryImpl tools) {
        if (step == null) {
            return NodeExecution.failure("unknown", "tool", "空节点", "step 为 null");
        }
        String nodeType = step.stepType() == null ? "tool" : step.stepType();
        log.debug("执行节点 id={} type={} goal={}", step.id(), nodeType, step.goal());
        try {
            return switch (nodeType) {
                case "search_kb" -> executeSearchKb(step, tools);
                case "search_history" -> executeSearchHistory(step, tools);
                case "update_profile" -> executeUpdateProfile(step, userId, tools);
                case "read_handover" -> executeReadHandover(step, userId, sessionId, tools);
                case "list_abandoned_paths" -> executeListAbandoned(step, userId, sessionId, tools);
                case "generate" -> NodeExecution.success(step.id(), nodeType,
                        "生成节点（由编排器统一生成最终答案）",
                        Map.of("goal", step.goal()));
                default -> executeDefault(step, tools);
            };
        } catch (Exception e) {
            log.warn("节点 {} 执行失败: {}", step.id(), e.getMessage());
            return NodeExecution.failure(step.id(), nodeType, step.goal(), e.getMessage());
        }
    }

    /** search_kb：检索知识库。 */
    private NodeExecution executeSearchKb(PlanStep step, ToolRegistryImpl tools) {
        var opt = tools.lookup(SearchKnowledgeBaseTool.NAME);
        if (opt.isEmpty()) {
            return missingTool(step, SearchKnowledgeBaseTool.NAME);
        }
        SearchKnowledgeBaseTool tool = (SearchKnowledgeBaseTool) opt.get();
        String result = tool.execute(step.goal());
        return NodeExecution.success(step.id(), "search_kb", step.goal(),
                Map.of("query", step.goal(), "result", result));
    }

    /** search_history：检索历史事件库。 */
    private NodeExecution executeSearchHistory(PlanStep step, ToolRegistryImpl tools) {
        var opt = tools.lookup(SearchHistoryTool.NAME);
        if (opt.isEmpty()) {
            return missingTool(step, SearchHistoryTool.NAME);
        }
        SearchHistoryTool tool = (SearchHistoryTool) opt.get();
        String result = tool.execute(step.goal(), DEFAULT_HISTORY_TOP_K);
        return NodeExecution.success(step.id(), "search_history", step.goal(),
                Map.of("query", step.goal(), "result", result));
    }

    /** update_profile：更新用户档案（从 goal 中解析 key=value）。 */
    private NodeExecution executeUpdateProfile(PlanStep step, String userId, ToolRegistryImpl tools) {
        var opt = tools.lookup(UpdateUserProfileTool.NAME);
        if (opt.isEmpty()) {
            return missingTool(step, UpdateUserProfileTool.NAME);
        }
        UpdateUserProfileTool tool = (UpdateUserProfileTool) opt.get();
        String[] kv = parseKeyValue(step.goal());
        String result = tool.execute(userId, kv[0], kv[1]);
        return NodeExecution.success(step.id(), "update_profile", step.goal(),
                Map.of("key", kv[0], "value", kv[1], "result", result));
    }

    /** read_handover：读取交接清单。 */
    private NodeExecution executeReadHandover(PlanStep step, String userId, String sessionId,
                                              ToolRegistryImpl tools) {
        var opt = tools.lookup(ReadHandoverTool.NAME);
        if (opt.isEmpty()) {
            return missingTool(step, ReadHandoverTool.NAME);
        }
        ReadHandoverTool tool = (ReadHandoverTool) opt.get();
        String result = tool.execute(userId, sessionId);
        return NodeExecution.success(step.id(), "read_handover", step.goal(),
                Map.of("result", result));
    }

    /** list_abandoned_paths：列出放弃路径。 */
    private NodeExecution executeListAbandoned(PlanStep step, String userId, String sessionId,
                                               ToolRegistryImpl tools) {
        var opt = tools.lookup(ListAbandonedPathTool.NAME);
        if (opt.isEmpty()) {
            return missingTool(step, ListAbandonedPathTool.NAME);
        }
        ListAbandonedPathTool tool = (ListAbandonedPathTool) opt.get();
        String result = tool.execute(userId, sessionId);
        return NodeExecution.success(step.id(), "list_abandoned_paths", step.goal(),
                Map.of("result", result));
    }

    /** 默认兜底：未知类型退化为知识库检索。 */
    private NodeExecution executeDefault(PlanStep step, ToolRegistryImpl tools) {
        var opt = tools.lookup(SearchKnowledgeBaseTool.NAME);
        if (opt.isEmpty()) {
            return missingTool(step, SearchKnowledgeBaseTool.NAME);
        }
        SearchKnowledgeBaseTool tool = (SearchKnowledgeBaseTool) opt.get();
        String result = tool.execute(step.goal());
        return NodeExecution.success(step.id(), "tool", step.goal(),
                Map.of("query", step.goal(), "result", result));
    }

    /** 工具未注册时的失败结果。 */
    private NodeExecution missingTool(PlanStep step, String toolName) {
        log.warn("节点 {} 需要的工具 {} 未注册（对应端口实现缺失），跳过", step.id(), toolName);
        return NodeExecution.failure(step.id(), step.stepType(), step.goal(),
                "工具未注册: " + toolName);
    }

    /** 从 goal 文本中解析 key 与 value；解析失败时 key=overrides、value=goal 原文。 */
    private static String[] parseKeyValue(String goal) {
        Matcher m = KV_PATTERN.matcher(goal == null ? "" : goal);
        if (m.find()) {
            return new String[]{m.group(1), m.group(2).strip()};
        }
        return new String[]{"overrides", goal == null ? "" : goal};
    }
}
