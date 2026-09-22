package com.wikiagent.application.agent.pero;

/**
 * v6 §20 PERO 节点内 ReAct 工具执行端口。
 * <p>
 * §2.6 既有 ToolExecutor 设计：执行 Action，返回 Observation。
 * §20.5 {@code toolExecutor.invoke(ra.action(), ctx)} 调用本接口。
 * <p>
 * 工具调用必须用 Spring AI 官方 API（§8.4.2）：
 * {@code @Tool} / {@code ToolCallback} / {@code ToolContext} / {@code ToolAdvisor}。
 * 本接口作为端口契约（v3-v5 实施时具体化）。
 */
public interface ToolExecutor {

    /**
     * 执行 Action，返回 Observation 文本。
     *
     * @param action ReActStep.Action（name + args JSON）
     * @param ctx    感知上下文（用于 ToolContext 构造）
     * @return 工具返回结果文本
     */
    String invoke(ReActAction action, Perception ctx);
}
