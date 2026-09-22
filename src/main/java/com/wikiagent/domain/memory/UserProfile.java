package com.wikiagent.domain.memory;

/**
 * v1-v2 §5 用户档案领域模型（值对象）。
 * <p>
 * v3 扩展 3 字段：business_identity + overrides + assigned_domains。
 */
public record UserProfile(
        String userId,
        String displayName,
        String preferredLanguage,
        String businessIdentity,    // v3: admin / business / product / technology / test
        String overrides,           // v3: JSON - 手动覆盖的领域权限
        String assignedDomains      // v3: JSON - 管理员分配的领域列表
) {
    public static UserProfile defaultFor(String userId) {
        return new UserProfile(userId, userId, "zh-CN", "business", null, null);
    }
}
