package com.wikiagent.application.agent.pero;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.infrastructure.tool.ListAbandonedPathTool;
import com.wikiagent.infrastructure.tool.ReadHandoverTool;
import com.wikiagent.infrastructure.tool.SearchHistoryTool;
import com.wikiagent.infrastructure.tool.SearchKnowledgeBaseTool;
import com.wikiagent.infrastructure.tool.UpdateUserProfileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * v6 PERO 路径真实 ToolExecutor（替换 §20 的 StubToolExecutor 占位）。
 * <p>
 * 将 {@link ReActAction}（name + args JSON）分派到五个真实工具组件：
 * <ul>
 *   <li>{@code search_knowledge_base}：{"query":"..."}（args 非 JSON 时整体作为 query 回退）</li>
 *   <li>{@code search_history}：{"query":"...","topK":5}（topK/top_k 可选）</li>
 *   <li>{@code update_user_profile}：{"key":"字段名","value":"字段值"}（userId 取自 Perception）</li>
 *   <li>{@code read_handover} / {@code list_abandoned_paths}：无参（userId/sessionId 取自 Perception）</li>
 * </ul>
 * 参数校验失败、JSON 解析失败、未知工具均以明确文本作为 Observation 返回，
 * 让 ReAct 循环据此前进或自愈，不抛异常、不返回桩文本。
 * <p>
 * 装配：仅在 {@code wikiagent.pero.enabled=true}（缺省值即 true）时激活；
 * {@code pero.enabled=false} 的 v1-v2 路径由 {@link StubToolExecutor} 占位
 * （该路径工具调用走 NodeExecutor 直接分派，不经本接口）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "true", matchIfMissing = true)
public class PeroToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PeroToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 已注册工具名（用于未知工具提示）。 */
    private static final List<String> KNOWN = List.of(
            SearchKnowledgeBaseTool.NAME, SearchHistoryTool.NAME, UpdateUserProfileTool.NAME,
            ReadHandoverTool.NAME, ListAbandonedPathTool.NAME);

    private final SearchKnowledgeBaseTool searchKb;
    private final SearchHistoryTool searchHistory;
    private final UpdateUserProfileTool updateProfile;
    private final ReadHandoverTool readHandover;
    private final ListAbandonedPathTool listAbandoned;

    public PeroToolExecutor(SearchKnowledgeBaseTool searchKb,
                            SearchHistoryTool searchHistory,
                            UpdateUserProfileTool updateProfile,
                            ReadHandoverTool readHandover,
                            ListAbandonedPathTool listAbandoned) {
        this.searchKb = searchKb;
        this.searchHistory = searchHistory;
        this.updateProfile = updateProfile;
        this.readHandover = readHandover;
        this.listAbandoned = listAbandoned;
    }

    @Override
    public String invoke(ReActAction action, Perception ctx) {
        if (action == null || action.name() == null || action.name().isBlank()) {
            return "[tool_executor] 工具名为空，未执行";
        }
        String name = action.name().trim();
        Map<String, Object> args = parseArgs(action.args());
        try {
            String observation = switch (name) {
                case SearchKnowledgeBaseTool.NAME ->
                        searchKb.execute(requireText(args, "query", action.args()));
                case SearchHistoryTool.NAME ->
                        searchHistory.execute(requireText(args, "query", action.args()),
                                intArg(args, "topK", "top_k"));
                case UpdateUserProfileTool.NAME ->
                        updateProfile.execute(ctx.userId(),
                                requireKey(args, "key"), requireKey(args, "value"));
                case ReadHandoverTool.NAME ->
                        readHandover.execute(ctx.userId(), ctx.sessionId());
                case ListAbandonedPathTool.NAME ->
                        listAbandoned.execute(ctx.userId(), ctx.sessionId());
                default -> "[tool_executor] 未知工具: " + name + "（已注册: " + KNOWN + "）";
            };
            log.debug("PERO 工具调用 action={} userId={} 结果长度={}",
                    name, ctx.userId(), observation == null ? 0 : observation.length());
            return observation;
        } catch (IllegalArgumentException e) {
            log.info("PERO 工具参数校验失败 action={} userId={} err={}", name, ctx.userId(), e.getMessage());
            return "[tool_executor] 参数错误: " + e.getMessage();
        }
    }

    /**
     * 解析 args JSON 对象。
     *
     * @return 解析成功的键值对；空白返回空 Map；非 JSON 返回 null（供单参数工具回退原始文本）
     */
    private static Map<String, Object> parseArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = MAPPER.readValue(raw, new TypeReference<>() { });
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取必需文本参数。args 为 null（非 JSON）时把整个原始串作为参数值回退——
     * LLM 对单参数工具常直接输出裸文本。
     */
    private static String requireText(Map<String, Object> args, String key, String raw) {
        if (args != null) {
            Object v = args.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        if (args == null && raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        throw new IllegalArgumentException(key + " 缺失（期望 JSON 参数，如 {\"" + key + "\":\"...\"}）");
    }

    /** 取必需键（无回退：结构化工具必须提供 JSON 参数）。 */
    private static String requireKey(Map<String, Object> args, String key) {
        if (args != null) {
            Object v = args.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        throw new IllegalArgumentException(
                key + " 缺失（update_user_profile 需要 JSON 参数 {\"key\":\"字段名\",\"value\":\"字段值\"}）");
    }

    /** 取可选整数参数（多键兼容），缺失或解析失败返回 0 由工具自身用默认值。 */
    private static int intArg(Map<String, Object> args, String... keys) {
        if (args == null) {
            return 0;
        }
        for (String k : keys) {
            Object v = args.get(k);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return Integer.parseInt(String.valueOf(v).trim());
                } catch (NumberFormatException ignore) {
                    // 尝试下一个键
                }
            }
        }
        return 0;
    }
}
