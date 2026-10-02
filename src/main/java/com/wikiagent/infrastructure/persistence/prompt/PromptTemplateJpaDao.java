package com.wikiagent.infrastructure.persistence.prompt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 提示词模板 JPA DAO。
 */
public interface PromptTemplateJpaDao extends JpaRepository<PromptTemplateEntity, Long> {

    Optional<PromptTemplateEntity> findByCodeAndStatus(String code, String status);

    Optional<PromptTemplateEntity> findByCodeAndVersion(String code, Integer version);
}
