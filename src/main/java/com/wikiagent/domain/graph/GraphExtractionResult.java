package com.wikiagent.domain.graph;

import java.util.List;

/**
 * GraphRAG LLM 抽取结果：从单个 chunk 文本中提取的实体和关系。
 */
public record GraphExtractionResult(List<GraphNode> entities, List<GraphEdge> relations) {

    public static GraphExtractionResult empty() {
        return new GraphExtractionResult(List.of(), List.of());
    }

    public boolean isEmpty() {
        return entities.isEmpty() && relations.isEmpty();
    }
}
