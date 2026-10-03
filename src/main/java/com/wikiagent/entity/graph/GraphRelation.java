package com.wikiagent.entity.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * GraphRAG 关系边（V17）：从知识库 chunk 中 LLM 抽取的实体间关系。
 * source + target + type 唯一约束，冲突时累加 weight 合并 description。
 */
@Entity
@Table(name = "graph_relation")
public class GraphRelation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "source_entity_id", nullable = false, length = 64)
    private String sourceEntityId;

    @Column(name = "target_entity_id", nullable = false, length = 64)
    private String targetEntityId;

    /** 关系类型：属于/包含/依赖/影响/合作/对抗/引用/继承/其他。 */
    @Column(name = "relation_type", nullable = false, length = 64)
    private String relationType;

    /** LLM 生成的关系描述。 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 出现频次/置信度累加。 */
    @Column(nullable = false)
    private double weight = 1.0;

    @Column(name = "source_doc_id", nullable = false, length = 64)
    private String sourceDocId;

    @Column(name = "source_chunk_id", length = 64)
    private String sourceChunkId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSourceEntityId() { return sourceEntityId; }
    public void setSourceEntityId(String sourceEntityId) { this.sourceEntityId = sourceEntityId; }
    public String getTargetEntityId() { return targetEntityId; }
    public void setTargetEntityId(String targetEntityId) { this.targetEntityId = targetEntityId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
    public String getSourceDocId() { return sourceDocId; }
    public void setSourceDocId(String sourceDocId) { this.sourceDocId = sourceDocId; }
    public String getSourceChunkId() { return sourceChunkId; }
    public void setSourceChunkId(String sourceChunkId) { this.sourceChunkId = sourceChunkId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
