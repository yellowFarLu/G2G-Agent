package com.wikiagent.infrastructure.memory.mysql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * handover_data_ref JPA DAO（V9，规格 2.5）。
 */
public interface HandoverDataRefJpaDao extends JpaRepository<HandoverDataRefEntity, Long> {

    Optional<HandoverDataRefEntity> findByChecklistIdAndRefKey(Long checklistId, String refKey);

    List<HandoverDataRefEntity> findByChecklistId(Long checklistId);
}
