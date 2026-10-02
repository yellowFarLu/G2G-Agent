package com.wikiagent.infrastructure.memory.mysql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * handover_node JPA DAO（V9，规格 2.5）。
 */
public interface HandoverNodeJpaDao extends JpaRepository<HandoverNodeEntity, Long> {

    List<HandoverNodeEntity> findByChecklistIdOrderBySeqAsc(Long checklistId);
}
