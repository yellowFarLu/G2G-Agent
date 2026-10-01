package com.wikiagent.application.task;

import com.wikiagent.domain.task.TaskHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务处理器注册表（规格 1.1）：taskType → TaskHandler。
 * Spring 自动收集容器内全部 TaskHandler Bean；测试可用 register 手动注册 fake。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskHandlerRegistry {

    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    @Autowired(required = false)
    void collect(List<TaskHandler> discovered) {
        discovered.forEach(this::register);
    }

    public void register(TaskHandler handler) {
        handlers.put(handler.taskType(), handler);
    }

    public Optional<TaskHandler> handler(String taskType) {
        return Optional.ofNullable(handlers.get(taskType));
    }

    public Set<String> supportedTypes() {
        return Set.copyOf(handlers.keySet());
    }
}
