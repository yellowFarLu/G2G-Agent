package com.wikiagent.domain.trace;

import java.time.Instant;

/**
 * v1-v2 §8 链路追踪 Span（值对象）。
 * <p>
 * 注意：本类与 {@code com.wikiagent.application.agent.pero.TraceSpan}（v6 接口）不同。
 * v6 pero/TraceSpan 是 PERO 主循环的端口接口；
 * 本类是 v1-v2 §8 可观测层的领域值对象，用于 MySQL 持久化。
 */
public record TraceSpan(
        long id,
        String conversationId,
        String userId,
        String sessionId,
        String nodeId,
        String spanType,           // plan / node / tool_call / llm_call / generate
        String inputData,
        String outputData,
        String status,             // OK / ERROR / TRUNCATED
        String errorMsg,
        Instant startTime,
        Instant endTime,
        long durationMs,
        int tokenInput,
        int tokenOutput,
        String intent,
        String modelUsed
) {
    public static TraceSpan start(String conversationId, String userId, String sessionId,
                                  String nodeId, String spanType, String inputData) {
        return new TraceSpan(0, conversationId, userId, sessionId, nodeId, spanType,
                inputData, null, "RUNNING", null, Instant.now(), null, 0, 0, 0, null, null);
    }

    public TraceSpan complete(String outputData, String status, String errorMsg) {
        Instant end = Instant.now();
        long dur = startTime != null ? end.toEpochMilli() - startTime.toEpochMilli() : 0;
        return new TraceSpan(id, conversationId, userId, sessionId, nodeId, spanType,
                inputData, outputData, status, errorMsg, startTime, end, dur,
                tokenInput, tokenOutput, intent, modelUsed);
    }
}
