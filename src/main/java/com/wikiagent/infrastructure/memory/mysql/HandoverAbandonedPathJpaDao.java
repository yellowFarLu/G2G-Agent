package com.wikiagent.infrastructure.memory.mysql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * handover_abandoned_path JPA DAO（V9，规格 2.5）。
 */
public interface HandoverAbandonedPathJpaDao extends JpaRepository<HandoverAbandonedPathEntity, Long> {

    List<HandoverAbandonedPathEntity> findByChecklistId(Long checklistId);
}
