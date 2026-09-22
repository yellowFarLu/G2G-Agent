package com.wikiagent.domain.tool;

/**
 * v1-v2 §2 工具策略（权限控制）。
 */
public record ToolPolicy(
        String toolName,
        boolean allowedForBusiness,    // business 身份可用
        boolean allowedForAdmin,        // admin 身份可用
        boolean requiresApproval         // 是否需要人工审批
) {
    public static ToolPolicy openAccess(String toolName) {
        return new ToolPolicy(toolName, true, true, false);
    }

    public static ToolPolicy adminOnly(String toolName) {
        return new ToolPolicy(toolName, false, true, false);
    }
}
