package com.wikiagent.infrastructure.memory.mysql;

import com.wikiagent.domain.memory.UserProfile;
import com.wikiagent.domain.memory.UserProfileRepository;
import com.wikiagent.infrastructure.persistence.UserProfileEntity;
import com.wikiagent.infrastructure.persistence.UserProfileJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * v1-v2 §5 用户档案端口 - JPA 实现适配器。
 * <p>
 * 负责 {@link UserProfile}（领域 record）与 {@link UserProfileEntity}（JPA 实体）互转。
 * <p>
 * 实施校正：原设计 {@code @ConditionalOnBean(UserProfileJpaDao.class)}，但
 * {@link UserProfileJpaDao} 由 Spring Data JPA 自动装配（晚于组件扫描），
 * 条件求值时 BeanDefinition 尚未注册导致本适配器永不创建（实测
 * {@code NoSuchBeanDefinitionException: UserProfileRepository}）。
 * Spring Data JPA 在本应用恒装配（开发 H2 / 生产 MySQL），故改为无条件 @Service。
 */
@Service
public class JpaUserProfileRepository implements UserProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaUserProfileRepository.class);

    private final UserProfileJpaDao dao;

    public JpaUserProfileRepository(UserProfileJpaDao dao) {
        this.dao = dao;
    }

    @Override
    public UserProfile findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return dao.findById(userId)
                .map(JpaUserProfileRepository::toDomain)
                .orElse(null);
    }

    @Override
    public void save(UserProfile profile) {
        if (profile == null || profile.userId() == null) {
            return;
        }
        UserProfileEntity entity = dao.findById(profile.userId())
                .orElseGet(UserProfileEntity::new);
        entity.setId(profile.userId());
        entity.setDisplayName(profile.displayName());
        entity.setPreferredLanguage(profile.preferredLanguage());
        entity.setBusinessIdentity(profile.businessIdentity());
        entity.setOverrides(profile.overrides());
        entity.setAssignedDomains(profile.assignedDomains());
        Instant now = Instant.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        dao.save(entity);
    }

    @Override
    public void updateIdentity(String userId, String businessIdentity, String overrides, String assignedDomains) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        UserProfileEntity entity = dao.findById(userId).orElse(null);
        if (entity == null) {
            log.warn("updateIdentity 未找到用户 {}，将创建", userId);
            entity = new UserProfileEntity();
            entity.setId(userId);
            entity.setDisplayName(userId);
            entity.setPreferredLanguage("zh-CN");
            entity.setCreatedAt(Instant.now());
        }
        if (businessIdentity != null) {
            entity.setBusinessIdentity(businessIdentity);
        }
        if (overrides != null) {
            entity.setOverrides(overrides);
        }
        if (assignedDomains != null) {
            entity.setAssignedDomains(assignedDomains);
        }
        entity.setUpdatedAt(Instant.now());
        dao.save(entity);
    }

    private static UserProfile toDomain(UserProfileEntity e) {
        return new UserProfile(
                e.getId(),
                e.getDisplayName() != null ? e.getDisplayName() : e.getId(),
                e.getPreferredLanguage() != null ? e.getPreferredLanguage() : "zh-CN",
                e.getBusinessIdentity() != null ? e.getBusinessIdentity() : "business",
                e.getOverrides(),
                e.getAssignedDomains()
        );
    }
}
