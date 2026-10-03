package com.wikiagent.entity.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * GraphRAG 实体节点（V17）：从知识库 chunk 中 LLM 抽取的实体（人物/组织/产品/地点/概念/事件/规则/其他）。
 * 同名同类型冲突时 upsert 合并 description。
 */
@Entity
@Table(name = "graph_entity")
public class GraphEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    /** 实体类型：人物/组织/产品/地点/概念/事件/规则/其他。 */
    @Column(nullable = false, length = 64)
    private String type;

    /** LLM 生成的实体描述摘要。 */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "source_doc_id", nullable = false, length = 64)
    private String sourceDocId;

    /** 关联子块（nullable，兼容旧解析路径）。 */
    @Column(name = "source_chunk_id", length = 64)
    private String sourceChunkId;

    /** 向量 JSON 数组（可选，Milvus 侧为主索引）。 */
    @Column(name = "embedding_json", columnDefinition = "TEXT")
    private String embeddingJson;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceDocId() { return sourceDocId; }
    public void setSourceDocId(String sourceDocId) { this.sourceDocId = sourceDocId; }
    public String getSourceChunkId() { return sourceChunkId; }
    public void setSourceChunkId(String sourceChunkId) { this.sourceChunkId = sourceChunkId; }
    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
