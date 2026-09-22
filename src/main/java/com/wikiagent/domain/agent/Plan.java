package com.wikiagent.domain.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * v6 §20 PERO 主循环中的任务计划列表。
 * <p>
 * Plan-and-Solve 论文（arXiv:2305.04091）要求 LLM 先把任务切成子任务列表，
 * 再按序执行；本类持有 {@link PlanStep} 的可变列表，供主循环 dequeue 与
 * {@code Optimizer.optimize()} 增删改剩余未执行步骤。
 * <p>
 * 可变设计原因：LangGraph 官方 Plan-and-Execute template 的 Re-Plan Step
 * 需要动态调整剩余计划（§20.2 #6），不可变 List 会引入不必要的复制成本。
 * 已执行节点不入此 List，由 {@code Handover} 单独记录，保证 optimize 幂等。
 */
public final class Plan {

    private final List<PlanStep> steps;

    public Plan(List<PlanStep> steps) {
        // 防御性拷贝，外部传入后内部独占
        this.steps = new ArrayList<>(steps);
    }

    /** 可变 List 引用，主循环直接 {@code remove(0)} dequeue。 */
    public List<PlanStep> steps() {
        return steps;
    }

    /** Optimizer 替换整个剩余计划时使用（保留原 List 引用，便于 handover 追踪）。 */
    public void replaceAll(List<PlanStep> newSteps) {
        steps.clear();
        steps.addAll(newSteps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }

    @Override
    public String toString() {
        return "Plan{steps=" + steps + "}";
    }
}
