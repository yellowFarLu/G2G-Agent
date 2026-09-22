package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wikiagent")
public record WikiAgentProperties(Milvus milvus, Retrieve retrieve, Ingest ingest, Agent agent) {

    /** Agentic RAG 编排配置。 */
    public record Agent(
            boolean enabled,
            int maxRounds,
            int maxQueriesPerRound) {
    }

    public record Milvus(
            String uri,
            String username,
            String password,
            String database,
            String collection,
            int dimension,
            double bm25K1,
            double bm25B) {
    }

    public record Retrieve(
            int subTopk,
            int finalTopk,
            int rrfK,
            int parentCharBudget) {
    }

    public record Ingest(
            int parentChars,
            int parentOverlap,
            int childChars,
            int childOverlap,
            int embeddingBatch) {
    }
}
