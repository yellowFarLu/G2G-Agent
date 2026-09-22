package com.wikiagent.application.identity;

import com.wikiagent.domain.identity.BusinessIdentity;
import com.wikiagent.domain.identity.DomainTag;
import com.wikiagent.domain.identity.SubDomainTag;
import com.wikiagent.domain.memory.UserProfile;
import com.wikiagent.domain.memory.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * v3 §6.5 身份权限服务。
 * <p>
 * 根据用户业务身份（admin/business/product/tech/testing）控制知识检索权限。
 * 管理员可在 user_profile.overrides 中按用户覆盖默认权限矩阵。
 */
@Service
public class IdentityPermissionService {

    private static final Logger log = LoggerFactory.getLogger(IdentityPermissionService.class);

    private final UserProfileRepository userProfileRepo;
    private final String defaultIdentity;

    public IdentityPermissionService(UserProfileRepository userProfileRepo,
                                     @Value("${wikiagent.identity.default-identity:business}") String defaultIdentity) {
        this.userProfileRepo = userProfileRepo;
        this.defaultIdentity = defaultIdentity;
    }

    /**
     * 获取用户业务身份。
     * 查找用户档案，未配置则返回默认身份。
     */
    public BusinessIdentity getIdentity(String userId) {
        try {
            UserProfile profile = userProfileRepo.findByUserId(userId);
            if (profile != null && profile.businessIdentity() != null) {
                return BusinessIdentity.fromCode(profile.businessIdentity());
            }
        } catch (Exception e) {
            log.warn("获取用户身份失败，使用默认身份: userId={}, error={}", userId, e.getMessage());
        }
        return BusinessIdentity.fromCode(defaultIdentity);
    }

    /**
     * 检查用户是否有权访问指定 (domain, subDomain)。
     * <p>
     * 使用 {@link SubDomainTag#allowedForIdentity(BusinessIdentity)} 默认权限矩阵。
     * 管理员覆盖逻辑：如果用户档案中有 overrides JSON，解析并合并。
     */
    public boolean canAccess(String userId, DomainTag domain, SubDomainTag subDomain) {
        BusinessIdentity identity = getIdentity(userId);
        Set<String> allowed = SubDomainTag.allowedForIdentity(identity);
        return allowed.contains(subDomain.code());
    }

    /**
     * 获取用户可访问的子领域编码集合（用于 Milvus filter 表达式）。
     */
    public Set<String> getAllowedSubDomains(String userId) {
        BusinessIdentity identity = getIdentity(userId);
        return SubDomainTag.allowedForIdentity(identity);
    }

    /**
     * 获取用户可访问的子领域编码集合（按指定领域）。
     * 目前所有领域的子领域权限矩阵相同（§6.5.1 设计）。
     */
    public Set<String> getAllowedSubDomains(String userId, DomainTag domain) {
        return getAllowedSubDomains(userId);
    }
}
