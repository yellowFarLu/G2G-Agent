package com.wikiagent.application.agent.pero;

/**
 * v6 §20 PERO 主循环的追踪 span 端口（值对象）。
 * <p>
 * §11 / §13 可观测平台要求 trace 表记录 Agent 执行路径、节点耗时、子 ReAct span。
 * 本接口作为 {@link TraceService#start} 的返回值，传给 {@link TraceService#end} 关闭。
 * <p>
 * 实施示例：MySQL {@code agent_trace} 表，每行记录 conversationId/userId/spanType
 * （plan / step / react / reflect / optimize）/ startTime / endTime / status / payload。
 */
public interface TraceSpan {

    /** span 唯一 id（用于父子关系，如 react span 挂在 step span 下）。 */
    String spanId();

    /** 父 span id，root span 为 null。 */
    String parentSpanId();
}
