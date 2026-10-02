package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * v4 §6.6.1 知识元数据 JPA DAO。
 */
@Repository
public interface KnowledgeMetadataJpaDao extends JpaRepository<KnowledgeMetadataEntity, Long> {

    Optional<KnowledgeMetadataEntity> findByChunkId(String chunkId);

    List<KnowledgeMetadataEntity> findByChunkIdIn(List<String> chunkIds);

    List<KnowledgeMetadataEntity> findByDocId(String docId);

    List<KnowledgeMetadataEntity> findByDomainTagAndSubDomainTag(String domainTag, String subDomainTag);

    @Query("SELECT k FROM KnowledgeMetadataEntity k WHERE k.isActive = true ORDER BY k.createdAt DESC")
    List<KnowledgeMetadataEntity> findAllActive();

    @Query("SELECT k FROM KnowledgeMetadataEntity k WHERE k.createdBy = :creator")
    List<KnowledgeMetadataEntity> findByCreatedBy(String creator);
}
