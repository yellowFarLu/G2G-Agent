package com.wikiagent.domain.llm;

import java.time.Instant;

/**
 * 模型调用日志领域模型（纯 Java record，DDD domain 层）。
 */
public record ModelCallLog(
        Long id,
        String traceId,
        String userId,
        String sessionId,
        ModelCallLogPurpose purpose,
        String provider,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        Double costEstimate,
        Long latencyMs,
        Status status,
        String fallbackFrom,
        Instant createdAt) {

    public enum Status {
        OK, ERROR;

        public static Status from(String raw) {
            if (raw == null) {
                return ERROR;
            }
            for (Status s : values()) {
                if (s.name().equalsIgnoreCase(raw)) {
                    return s;
                }
            }
            return ERROR;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String traceId;
        private String userId;
        private String sessionId;
        private ModelCallLogPurpose purpose;
        private String provider;
        private String model;
        private Integer tokensIn;
        private Integer tokensOut;
        private Double costEstimate;
        private Long latencyMs;
        private Status status = Status.OK;
        private String fallbackFrom;
        private Instant createdAt = Instant.now();

        public Builder id(Long id) { this.id = id; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public Builder purpose(ModelCallLogPurpose purpose) { this.purpose = purpose; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder tokensIn(Integer tokensIn) { this.tokensIn = tokensIn; return this; }
        public Builder tokensOut(Integer tokensOut) { this.tokensOut = tokensOut; return this; }
        public Builder costEstimate(Double costEstimate) { this.costEstimate = costEstimate; return this; }
        public Builder latencyMs(Long latencyMs) { this.latencyMs = latencyMs; return this; }
        public Builder status(Status status) { this.status = status; return this; }
        public Builder fallbackFrom(String fallbackFrom) { this.fallbackFrom = fallbackFrom; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public ModelCallLog build() {
            return new ModelCallLog(id, traceId, userId, sessionId, purpose, provider, model,
                    tokensIn, tokensOut, costEstimate, latencyMs, status, fallbackFrom, createdAt);
        }
    }
}
