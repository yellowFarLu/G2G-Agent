package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子项目 F1 工具权限配置（{@code wikiagent.tool-permissions}）。
 * <p>
 * 两层来源：DB 表 tool_permission（V15，运营可改）+ 本配置覆盖（部署期固化）。
 * agentAllowlists 定义每个 Agent 的工具白名单（agent 名 → 工具名列表）。
 */
@ConfigurationProperties(prefix = "wikiagent.tool-permissions")
public class ToolPermissionProperties {

    /** 工具权限覆盖：toolName → 权限规格。 */
    private Map<String, PermissionSpec> permissions = new HashMap<>();

    /** 每 Agent 工具白名单：agentName → 允许的工具名列表；未配置的 Agent 用默认白名单。 */
    private Map<String, List<String>> agentAllowlists = new HashMap<>();

    public Map<String, PermissionSpec> getPermissions() {
        return permissions;
    }

    public void setPermissions(Map<String, PermissionSpec> permissions) {
        this.permissions = permissions == null ? new HashMap<>() : permissions;
    }

    public Map<String, List<String>> getAgentAllowlists() {
        return agentAllowlists;
    }

    public void setAgentAllowlists(Map<String, List<String>> agentAllowlists) {
        this.agentAllowlists = agentAllowlists == null ? new HashMap<>() : agentAllowlists;
    }

    /** 单个工具的权限规格（配置覆盖用）。 */
    public static class PermissionSpec {

        private String requiredRole;

        private String requiredScope;

        private boolean requiresApproval;

        public String getRequiredRole() {
            return requiredRole;
        }

        public void setRequiredRole(String requiredRole) {
            this.requiredRole = requiredRole;
        }

        public String getRequiredScope() {
            return requiredScope;
        }

        public void setRequiredScope(String requiredScope) {
            this.requiredScope = requiredScope;
        }

        public boolean isRequiresApproval() {
            return requiresApproval;
        }

        public void setRequiresApproval(boolean requiresApproval) {
            this.requiresApproval = requiresApproval;
        }
    }
}
