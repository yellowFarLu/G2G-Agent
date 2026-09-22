package com.wikiagent.domain.agent;

/**
 * v6 §20 PERO 主循环中的单步任务计划。
 * <p>
 * Plan-and-Solve（arXiv:2305.04091）显式拆"先规划、再求解"两阶段：
 * Plan 阶段输出 {@code List<PlanStep>}，按序进入 Execute(ReAct) 阶段。
 * <p>
 * 字段：
 * <ul>
 *   <li>{@code id}         — 节点唯一标识，trace/handover/todo 引用</li>
 *   <li>{@code goal}       — 自然语言描述的本节点目标</li>
 *   <li>{@code stepType}   — 节点类型，对应 §20.6 配置 {@code tool-whitelist-by-step-type}</li>
 *   <li>{@code dependsOn}  — 前置节点 id 列表（保留字段，当前实现按顺序执行）</li>
 *   <li>{@code hint}       — 反思重做时携带的 hint（Reflexion 论文 arXiv:2303.11366 episodic memory 复用）</li>
 * </ul>
 * record 不可变；{@code withHint} 在反思 needsRework=true 时由 {@code PeroAgent} 复制产生新实例，
 * 不破坏原步骤引用，便于 handover 追踪同一节点的多次执行轨迹。
 */
public record PlanStep(String id, String goal, String stepType,
                       java.util.List<String> dependsOn, String hint) {

    /** 创建无依赖、无 hint 的节点。 */
    public PlanStep(String id, String goal, String stepType) {
        this(id, goal, stepType, java.util.List.of(), null);
    }

    /** 反思后携带 hint 重做节点（复制语义，保持原 id 与 stepType）。 */
    public PlanStep withHint(String newHint) {
        return new PlanStep(this.id, this.goal, this.stepType, this.dependsOn, newHint);
    }
}
