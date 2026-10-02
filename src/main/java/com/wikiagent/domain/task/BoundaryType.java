package com.wikiagent.domain.task;

/**
 * 子项目 F4：系统四类执行边界标记。
 * <p>
 * 用于在代码中显式标注组件的边界类型，使 Agent 治理（权限/预算/审批）的
 * 拦截点一目了然，也便于架构审计确认"哪些路径有治理、哪些没有"。
 * <ul>
 *   <li>{@link #FIXED_HANDLER} — 固定步骤处理器（IngestTaskHandler），步骤号冻结，
 *       不走 ReAct，不涉及 LLM 自主决策</li>
 *   <li>{@link #AGENT} — Agent 自主决策路径（AgentTaskHandler / PeroAgent），
 *       节点内 ReAct 循环，需 F1-F3 全部治理</li>
 *   <li>{@link #DETERMINISTIC_TOOL} — 确定性工具/规则引擎（DeterministicRuleEngine），
 *       零 LLM、零随机，同一输入必得同一输出</li>
 *   <li>{@link #HUMAN} — 人工接管边界（HumanRequiredException 抛出点），
 *       任务暂停等待人工输入/批准/处置</li>
 * </ul>
 */
public enum BoundaryType {

    /** 固定步骤处理器（INGEST 等），步骤号冻结，不走 ReAct。 */
    FIXED_HANDLER,

    /** Agent 自主决策路径（PERO ReAct 循环），需 F1-F3 全部治理。 */
    AGENT,

    /** 确定性工具/规则引擎，零 LLM、零随机。 */
    DETERMINISTIC_TOOL,

    /** 人工接管边界，任务暂停等待人工输入/批准/处置。 */
    HUMAN
}
