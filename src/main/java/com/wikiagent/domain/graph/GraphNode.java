package com.wikiagent.domain.graph;

/**
 * GraphRAG 领域模型：实体节点。
 */
public record GraphNode(String id, String name, String type, String description,
                        String sourceDocId, String sourceChunkId) {
}
