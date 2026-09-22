package com.wikiagent.domain.routing;

/**
 * v1-v2 §7 LLM 路由层 - 路由器端口（DDD 端口接口）。
 * <p>
 * 先用 intent-model 做意图识别，再按意图路由到 simple-model 或 complex-model。
 * v3 扩展为 5 类意图分类 + 失败降级 knowledge_qa。
 */
public interface LlmRouterPort {

    /**
     * 意图识别 + 模型路由。
     *
     * @param userInput 用户输入
     * @return 路由决策（包含意图、模型名、是否复杂）
     */
    RouteDecision route(String userInput);
}
