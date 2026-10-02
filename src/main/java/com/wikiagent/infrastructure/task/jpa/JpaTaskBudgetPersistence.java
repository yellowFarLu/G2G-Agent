package com.wikiagent.infrastructure.task.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.task.TaskBudgetPersistencePort;
import com.wikiagent.domain.task.TaskBudget;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 子项目 F2：预算持久化 JPA 实现——读改写 task_instance.payload 的 "budget" 字段。
 * <p>
 * 读库内最新任务行再保存（不以步骤启动时的旧快照覆盖控制面痕迹）；
 * payload 为 ObjectNode 时原位替换 budget 字段，其余字段不动。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class JpaTaskBudgetPersistence implements TaskBudgetPersistencePort {

    private static final Logger log = LoggerFactory.getLogger(JpaTaskBudgetPersistence.class);

    private final TaskRepositoryPort taskRepo;

    public JpaTaskBudgetPersistence(TaskRepositoryPort taskRepo) {
        this.taskRepo = taskRepo;
    }

    @Override
    public void saveBudget(String taskId, TaskBudget budget) {
        try {
            taskRepo.findByTaskId(taskId).ifPresent(task -> {
                JsonNode payload = task.payload();
                if (payload instanceof ObjectNode node) {
                    node.set("budget", budget.toJson());
                    taskRepo.save(task);
                }
            });
        } catch (Exception e) {
            // 预算落库失败不阻断执行链路（下一轮 sink 会重试写）
            log.warn("预算写回 payload 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }
}
