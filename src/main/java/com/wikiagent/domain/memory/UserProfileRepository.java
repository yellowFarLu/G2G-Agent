package com.wikiagent.domain.memory;

/**
 * v1-v2 §5 用户档案端口（DDD 端口接口）。
 * <p>
 * 基于 MySQL（JPA），以 userId 为主键。
 */
public interface UserProfileRepository {

    /** 按 userId 查找用户档案。 */
    UserProfile findByUserId(String userId);

    /** 保存或更新用户档案。 */
    void save(UserProfile profile);

    /** 更新用户档案的特定字段（v3：身份、覆盖、分配领域）。 */
    void updateIdentity(String userId, String businessIdentity, String overrides, String assignedDomains);
}
