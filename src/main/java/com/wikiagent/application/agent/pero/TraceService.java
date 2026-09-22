package com.wikiagent.application.agent.pero;

/**
 * v6 §20 PERO 主循环的链路追踪服务端口。
 * <p>
 * §11 可观测平台要求：trace 表记录 Agent 执行路径、节点耗时、子 ReAct span。
 * 本接口作为端口契约（v3-v5 实施时具体化为 {@code MysqlTraceRepository}），
 * v6 主循环只依赖以下最小方法。
 * <p>
 * spanType 取值：{@code plan} / {@code step:{stepId}} / {@code react:{stepId}:{iter}} /
 * {@code reflect:{stepId}} / {@code optimize:{stepId}}。
 */
public interface TraceService {

    /**
     * 启动 span，返回句柄。conversationId 为 {@code userId:sessionId}。
     *
     * @param conversationId userId:sessionId
     * @param userId         用户 id
     * @param spanType       span 类型（如 "plan" / stepId / reactId）
     * @param description    span 描述（如节点 goal）
     * @return span 句柄，传给 {@link #end} 关闭
     */
    TraceSpan start(String conversationId, String userId, String spanType, String description);

    /**
     * 关闭 span，记录结果与状态。
     *
     * @param span    start 返回的句柄
     * @param payload span 完成时的负载（如 Plan.toString() / ReActResult.toString()）
     * @param status  OK / ERROR / TRUNCATED
     * @param error   ERROR 时的异常信息，否则 null
     */
    void end(TraceSpan span, String payload, String status, String error);
}
