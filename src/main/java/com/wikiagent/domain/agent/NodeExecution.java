package com.wikiagent.domain.agent;

import java.util.Map;

/**
 * v1-v2 §2 节点执行结果（值对象）。
 * <p>
 * 记录单个 PlanStep 的执行结果，用于交接清单和 trace。
 */
public record NodeExecution(
        String nodeId,
        String nodeType,             // search_kb / search_history / update_profile / tool / generate
        String description,           // 节点执行描述
        Map<String, Object> outputs,  // 执行产出
        boolean success,
        String errorMessage
) {
    public static NodeExecution success(String nodeId, String nodeType, String description,
                                        Map<String, Object> outputs) {
        return new NodeExecution(nodeId, nodeType, description, outputs, true, null);
    }

    public static NodeExecution failure(String nodeId, String nodeType, String description,
                                        String errorMessage) {
        return new NodeExecution(nodeId, nodeType, description, Map.of(), false, errorMessage);
    }
}
