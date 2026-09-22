package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v4 §6.6.3 知识冲突解决 JPA DAO。
 */
public interface ConflictResolutionJpaDao extends JpaRepository<ConflictResolutionEntity, Long> {

    List<ConflictResolutionEntity> findByStatus(String status);

    List<ConflictResolutionEntity> findByDomainTagAndSubDomainTag(String domainTag, String subDomainTag);

    List<ConflictResolutionEntity> findByChunkIdAOrChunkIdB(String chunkIdA, String chunkIdB);
}
