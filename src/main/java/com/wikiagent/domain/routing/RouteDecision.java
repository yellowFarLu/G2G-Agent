package com.wikiagent.domain.routing;

/**
 * v1-v2 §7 LLM 路由层 - 路由决策结果。
 * <p>
 * 意图识别后决定使用哪个模型：简单任务 → simple-model，复杂任务 → complex-model。
 */
public record RouteDecision(
        Intent intent,
        String modelName,
        boolean isComplex,
        String reason
) {
    public static RouteDecision simple(Intent intent, String modelName) {
        return new RouteDecision(intent, modelName, false, "simple task routed to " + modelName);
    }

    public static RouteDecision complex(Intent intent, String modelName) {
        return new RouteDecision(intent, modelName, true, "complex task routed to " + modelName);
    }

    public static RouteDecision fallback() {
        // 实施校正 2026-09-22：DashScope 无 qwen-3.8-* 模型，fallback 对齐 simple 路由默认值
        return new RouteDecision(Intent.KNOWLEDGE_QA, "qwen-plus", false, "fallback to knowledge_qa");
    }
}
