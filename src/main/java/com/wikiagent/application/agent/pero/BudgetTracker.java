package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.task.TaskBudget;

import java.util.function.Consumer;

/**
 * 子项目 F2：ReAct 循环内的预算记账器。
 * <p>
 * 持有当前 {@link TaskBudget} 快照（不可变值对象逐轮替换）；每次扣减后调用
 * {@code sink} 回调把最新预算持久化（任务框架中由 AgentTaskHandler 写回 payload，
 * worker 断点恢复时从 payload 读回，用量不重置）。
 */
public final class BudgetTracker {

    private volatile TaskBudget budget;
    private final Consumer<TaskBudget> sink;

    public BudgetTracker(TaskBudget initial, Consumer<TaskBudget> sink) {
        this.budget = initial;
        this.sink = sink;
    }

    public TaskBudget current() {
        return budget;
    }

    /** 记账一轮迭代：+tokens、+cost、+1 iteration，并触发持久化回调。 */
    public synchronized void charge(long tokens, double cost) {
        budget = budget.withUsage(tokens, cost);
        if (sink != null) {
            sink.accept(budget);
        }
    }

    /** 人工提额后替换上限（保留已用量）。 */
    public synchronized void raiseLimits(Long tokenLimit, Double costLimit, Integer iterationLimit) {
        budget = budget.withLimits(tokenLimit, costLimit, iterationLimit);
        if (sink != null) {
            sink.accept(budget);
        }
    }
}
