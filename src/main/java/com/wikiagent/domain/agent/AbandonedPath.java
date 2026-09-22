package com.wikiagent.domain.agent;

/**
 * v1-v2 §4 放弃路径（值对象）。
 * <p>
 * 记录被放弃的执行路径及原因，写入交接清单的 abandonedPaths 段。
 */
public record AbandonedPath(
        String nodeId,
        String nodeType,
        String reason,
        String timestamp
) {
    public static AbandonedPath of(PlanStep step, String reason) {
        return new AbandonedPath(step.id(), step.goal(), reason,
                java.time.Instant.now().toString());
    }
}
