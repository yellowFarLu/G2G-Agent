package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;

/**
 * PERO 主循环钩子（Task 12）：任务框架借 {@link PeroAgent#executeLoop} 挂接步骤级控制，
 * 不改变 PERO 自身语义。
 * <ul>
 *   <li>{@link #beforeNode}        — 节点循环顶部调用（暂停/取消在节点开始前生效）</li>
 *   <li>{@link #afterReactIteration}— 每次 ReAct 迭代顶部调用（暂停/取消 ≤1 个 ReAct 步长生效）</li>
 *   <li>{@link #remainingPlanForResume} — 恢复语义预留：返回剩余计划（A 阶段无调用方，
 *       实际恢复切片由 AgentTaskHandler 基于 PLAN checkpoint 完成）</li>
 * </ul>
 * 钩子抛出的 {@link com.wikiagent.domain.task.ControlSignalException} 必须穿透
 * executeLoop 的节点通用 catch（节点不 failNode、不重试）。
 */
public interface PeroLoopHook {

    /** 节点开始前。index 为本循环内的节点序号（0 起）。 */
    default void beforeNode(PlanStep step, int index) {
    }

    /** 每次 ReAct 迭代顶部。iter 为 1 起的迭代序号。 */
    default void afterReactIteration(PlanStep step, int iter) {
    }

    /** 恢复语义预留：返回剩余计划，null 表示无（默认）。 */
    default Plan remainingPlanForResume() {
        return null;
    }

    /** 空钩子：run() 同步链路使用，行为与改造前一致。 */
    PeroLoopHook NOOP = new PeroLoopHook() {
    };
}
