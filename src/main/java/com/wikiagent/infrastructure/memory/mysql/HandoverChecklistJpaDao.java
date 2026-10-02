package com.wikiagent.infrastructure.memory.mysql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * handover_checklist JPA DAO（V9，规格 2.5）。
 */
public interface HandoverChecklistJpaDao extends JpaRepository<HandoverChecklistEntity, Long> {

    Optional<HandoverChecklistEntity> findByUserIdAndSessionId(String userId, String sessionId);
}
