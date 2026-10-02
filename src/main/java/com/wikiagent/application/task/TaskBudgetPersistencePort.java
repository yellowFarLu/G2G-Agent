package com.wikiagent.application.task;

import com.wikiagent.domain.task.TaskBudget;

/**
 * 子项目 F2：任务预算持久化端口。
 * <p>
 * ReAct 每轮迭代记账后由 sink 回调写入 TaskInstance.payload 的 "budget" 字段；
 * worker 断点恢复时从 payload 读回，用量不重置。
 * 由 {@link TaskWorker} 提供默认实现（@Component 装配进 AgentTaskHandler）。
 */
public interface TaskBudgetPersistencePort {

    /** 把最新预算快照写入指定任务的 payload.budget（读改写，保留其他 payload 字段）。 */
    void saveBudget(String taskId, TaskBudget budget);
}
