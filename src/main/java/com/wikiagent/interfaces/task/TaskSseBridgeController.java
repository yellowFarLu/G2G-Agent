package com.wikiagent.interfaces.task;

import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import com.wikiagent.service.chat.SseSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务流 SSE 桥（Task 12）：GET /api/tasks/{taskId}/stream 订阅 {@link TaskStreamBus}，
 * 事件（delta/done/error/progress）原样转发为 SSE；done/error 或客户端断开后
 * 完成 emitter 并注销订阅。前端协议与聊天 SSE 一致（delta/done/error 事件名）。
 */
@RestController
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class TaskSseBridgeController {

    private final TaskStreamBus streamBus;

    public TaskSseBridgeController(TaskStreamBus streamBus) {
        this.streamBus = streamBus;
    }

    @GetMapping("/api/tasks/{taskId}/stream")
    public SseEmitter stream(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由 done/error 事件显式收尾
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<AutoCloseable> subRef = new AtomicReference<>();
        SseSender sse = new SseSender(emitter);

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                AutoCloseable sub = subRef.get();
                if (sub != null) {
                    try {
                        sub.close();
                    } catch (Exception ignored) {
                        // 幂等注销，失败无影响
                    }
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);

        subRef.set(streamBus.subscribe(taskId, ev -> {
            boolean ok = sse.send(ev.type(), ev.payload());
            boolean terminal = "done".equals(ev.type()) || "error".equals(ev.type());
            if (terminal || !ok) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // emitter 已失效，注销即可
                }
                cleanup.run(); // complete 会触发 onCompletion，此处幂等兜底
            }
        }));
        return emitter;
    }
}
