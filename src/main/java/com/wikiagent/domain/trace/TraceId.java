package com.wikiagent.domain.trace;

/**
 * v1-v2 §8 链路追踪 ID（值对象）。
 */
public record TraceId(
        String conversationId,    // userId:sessionId
        String userId,
        String sessionId
) {
    public static TraceId of(String userId, String sessionId) {
        return new TraceId(userId + ":" + sessionId, userId, sessionId);
    }
}
