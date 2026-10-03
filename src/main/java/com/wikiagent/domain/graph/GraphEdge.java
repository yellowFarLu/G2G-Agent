package com.wikiagent.domain.graph;

/**
 * GraphRAG 领域模型：实体间关系边。
 */
public record GraphEdge(String id, String sourceEntityId, String targetEntityId,
                        String relationType, String description, double weight,
                        String sourceDocId, String sourceChunkId) {
}
