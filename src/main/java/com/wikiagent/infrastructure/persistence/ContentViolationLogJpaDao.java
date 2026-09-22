package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v5 §19 内容违规记录 JPA DAO。
 */
public interface ContentViolationLogJpaDao extends JpaRepository<ContentViolationLogEntity, Long> {

    List<ContentViolationLogEntity> findBySessionId(String sessionId);

    List<ContentViolationLogEntity> findByViolationType(String violationType);

    List<ContentViolationLogEntity> findBySeverity(String severity);
}
