package com.wikiagent.domain.tool;

import java.util.List;

/**
 * 子项目 F1：工具调用方身份（ReAct 执行上下文中的 user 身份快照）。
 * <p>
 * scope 为逗号分隔的授权范围串（如 "kb:read,profile:write"），按 token 匹配。
 */
public record ToolCaller(String userId, String role, List<String> scopes) {

    public static ToolCaller of(String userId, String role, List<String> scopes) {
        return new ToolCaller(userId, role, scopes == null ? List.of() : List.copyOf(scopes));
    }

    public boolean hasScope(String scope) {
        return scope == null || scope.isBlank() || scopes.contains(scope);
    }

    public boolean hasRole(String requiredRole) {
        return requiredRole == null || requiredRole.isBlank()
                || (role != null && role.equalsIgnoreCase(requiredRole));
    }
}
