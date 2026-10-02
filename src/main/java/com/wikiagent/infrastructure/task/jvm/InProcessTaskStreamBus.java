package com.wikiagent.infrastructure.task.jvm;

import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 任务流总线进程内实现（wikiagent.redis.enabled=false，开发默认）：
 * ConcurrentHashMap 订阅表，publish 直接回调同 taskId 订阅者。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InProcessTaskStreamBus implements TaskStreamBus {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<StreamEvent>>> subscribers =
            new ConcurrentHashMap<>();

    @Override
    public void publish(StreamEvent event) {
        List<Consumer<StreamEvent>> listeners = subscribers.get(event.taskId());
        if (listeners != null) {
            for (Consumer<StreamEvent> listener : listeners) {
                listener.accept(event);
            }
        }
    }

    @Override
    public AutoCloseable subscribe(String taskId, Consumer<StreamEvent> listener) {
        CopyOnWriteArrayList<Consumer<StreamEvent>> listeners =
                subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> {
            listeners.remove(listener);
            subscribers.remove(taskId, listeners);
        };
    }
}
