package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.memory.UserProfile;
import com.wikiagent.domain.memory.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * v1-v2 §2 工具：更新用户档案。
 * <p>
 * 封装 {@link UserProfileRepository}，按 key 更新用户档案的单个字段。
 * 支持的 key：displayName / preferredLanguage / businessIdentity / overrides / assignedDomains。
 * 其中 businessIdentity / overrides / assignedDomains 走 updateIdentity（v3 身份权限字段），
 * 其余字段通过 load + 重建 record + save 更新。
 * <p>
 * 实施校正：@ConditionalOnBean 受扫描顺序影响不可靠，JpaUserProfileRepository 恒装配。
 * v1-v2 与 v6 PERO 双路径共用（v6 经 {@code PeroToolExecutor} 分派调用）。
 */
@Component
public class UpdateUserProfileTool {

    private static final Logger log = LoggerFactory.getLogger(UpdateUserProfileTool.class);

    /** 工具名（与 ToolRegistry 白名单一致）。 */
    public static final String NAME = "update_user_profile";

    private final UserProfileRepository repository;

    public UpdateUserProfileTool(UserProfileRepository repository) {
        this.repository = repository;
    }

    /**
     * 更新用户档案的指定字段。
     *
     * @param userId 用户 id
     * @param key    字段名（displayName / preferredLanguage / businessIdentity / overrides / assignedDomains）
     * @param value  字段值
     * @return 更新结果描述
     */
    public String execute(String userId, String key, String value) {
        if (userId == null || userId.isBlank()) {
            return "[update_user_profile] userId 为空";
        }
        if (key == null || key.isBlank()) {
            return "[update_user_profile] key 为空";
        }
        try {
            // 身份权限字段走 updateIdentity（v3 §6.5）
            switch (key) {
                case "businessIdentity", "business_identity" -> {
                    repository.updateIdentity(userId, value, null, null);
                    return "[update_user_profile] 已更新 businessIdentity=" + value;
                }
                case "overrides" -> {
                    repository.updateIdentity(userId, null, value, null);
                    return "[update_user_profile] 已更新 overrides";
                }
                case "assignedDomains", "assigned_domains" -> {
                    repository.updateIdentity(userId, null, null, value);
                    return "[update_user_profile] 已更新 assignedDomains";
                }
                default -> {
                    // 其余字段：load + 重建 record + save
                    UserProfile profile = repository.findByUserId(userId);
                    if (profile == null) {
                        profile = UserProfile.defaultFor(userId);
                    }
                    UserProfile updated = rebuild(profile, key, value);
                    repository.save(updated);
                    return "[update_user_profile] 已更新 " + key + "=" + value;
                }
            }
        } catch (Exception e) {
            log.warn("用户档案更新失败 userId={} key={} err={}", userId, key, e.getMessage());
            return "[update_user_profile] 更新失败: " + e.getMessage();
        }
    }

    /** 按字段名重建不可变 record。不识别的 key 原样返回旧档案。 */
    private UserProfile rebuild(UserProfile p, String key, String value) {
        return switch (key) {
            case "displayName", "display_name" -> new UserProfile(
                    p.userId(), value, p.preferredLanguage(), p.businessIdentity(),
                    p.overrides(), p.assignedDomains());
            case "preferredLanguage", "preferred_language" -> new UserProfile(
                    p.userId(), p.displayName(), value, p.businessIdentity(),
                    p.overrides(), p.assignedDomains());
            default -> p; // 未识别字段，不修改
        };
    }
}
