package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * v4 §6.6.1 知识元数据 JPA 实体（V5__knowledge_metadata.sql）。
 */
@Entity
@Table(name = "knowledge_metadata")
public class KnowledgeMetadataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chunk_id", nullable = false)
    private String chunkId;

    @Column(name = "doc_id", nullable = false)
    private String docId;

    @Column(name = "domain_tag", nullable = false)
    private String domainTag;

    @Column(name = "sub_domain_tag", nullable = false)
    private String subDomainTag;

    @Column(name = "required_identity", nullable = false)
    private String requiredIdentity;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_identity")
    private String createdIdentity;

    @Column(name = "source_filename")
    private String sourceFilename;

    @Column(name = "version")
    private Integer version = 1;

    @Column(name = "is_active")
    private Boolean isActive = true;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getDomainTag() { return domainTag; }
    public void setDomainTag(String domainTag) { this.domainTag = domainTag; }
    public String getSubDomainTag() { return subDomainTag; }
    public void setSubDomainTag(String subDomainTag) { this.subDomainTag = subDomainTag; }
    public String getRequiredIdentity() { return requiredIdentity; }
    public void setRequiredIdentity(String requiredIdentity) { this.requiredIdentity = requiredIdentity; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedIdentity() { return createdIdentity; }
    public void setCreatedIdentity(String createdIdentity) { this.createdIdentity = createdIdentity; }
    public String getSourceFilename() { return sourceFilename; }
    public void setSourceFilename(String sourceFilename) { this.sourceFilename = sourceFilename; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
