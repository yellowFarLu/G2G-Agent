package com.wikiagent.application.task;

import java.util.function.Consumer;

/**
 * 任务流总线：worker 侧发布，SSE 桥订阅（规格 5.1 stream 频道）。
 * Redis 可用时用 Redis pub/sub 实现，否则用进程内实现。
 */
public interface TaskStreamBus {

    void publish(StreamEvent event);

    /** 订阅某任务的事件流；返回的 close 注销订阅（幂等）。 */
    AutoCloseable subscribe(String taskId, Consumer<StreamEvent> listener);
}
