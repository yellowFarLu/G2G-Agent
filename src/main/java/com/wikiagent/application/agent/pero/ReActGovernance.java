package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.tool.ToolCaller;

import java.util.Map;

/**
 * 子项目 F：ReAct 治理上下文（权限 / 审批续跑），由任务框架 NODE 步构造并传入。
 * <p>
 * 字段均可空：null 治理等价于"不治理"（v6 聊天路径默认行为）。
 * <ul>
 *   <li>{@code caller}      — F1 工具权限判定的调用方身份</li>
 *   <li>{@code agentName}   — F1 每 Agent 工具白名单键（如 pero-agent）</li>
 *   <li>{@code sessionId}   — F1 TOOL_DENIED 审计落库的会话标识</li>
 *   <li>{@code approvalDecisions} — F3 TOOL_APPROVAL 续跑时的审批决策（toolName → true 批准 / false 驳回）</li>
 * </ul>
 */
public record ReActGovernance(
        ToolCaller caller,
        String agentName,
        String sessionId,
        Map<String, Boolean> approvalDecisions) {

    public static ReActGovernance of(ToolCaller caller, String agentName, String sessionId) {
        return new ReActGovernance(caller, agentName, sessionId, Map.of());
    }
}
