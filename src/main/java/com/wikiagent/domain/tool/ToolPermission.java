package com.wikiagent.domain.tool;

/**
 * 子项目 F1：工具权限映射（toolName → 所需角色 / scope / 是否需人工批准）。
 * <p>
 * 纯领域值对象，不依赖框架：requiredRole / requiredScope 为空表示不校验该维度；
 * requiresApproval=true 时执行前抛人工接管（F3）。
 */
public record ToolPermission(
        String toolName,
        String requiredRole,
        String requiredScope,
        boolean requiresApproval
) {

    /** 无限制权限：任何调用方可用，不需批准。 */
    public static ToolPermission unrestricted(String toolName) {
        return new ToolPermission(toolName, null, null, false);
    }

    public boolean roleRestricted() {
        return requiredRole != null && !requiredRole.isBlank();
    }

    public boolean scopeRestricted() {
        return requiredScope != null && !requiredScope.isBlank();
    }
}
