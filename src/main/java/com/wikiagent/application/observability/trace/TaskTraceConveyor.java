package com.wikiagent.application.observability.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子项目 I（AC-I1）任务 traceId 跨线程传送带。
 * <p>
 * 本地调度（{@code wikiagent.task.mq=local}）下，{@code POST /api/tasks} 请求线程把任务
 * 提交后，实际执行发生在调度器线程池（投递 Runnable 由调度组件内部创建，可观测层无法
 * 在提交点直接包裹）。因此在投递端口代理（{@code TaskDispatcherTraceProxyPostProcessor}）
 * 拦截 {@code dispatch} 时，以 taskId 为键把请求 MDC 中的 traceId 存入本传送带；
 * {@code TaskWorker.onMessage} 入口消费，使 task_event / model_call_log 与触发请求同源。
 * <p>
 * 生命周期语义：
 * <ul>
 *   <li>{@link #consume(String)} 一次性取出（任务开始执行时）；retry 重投会在 worker
 *       线程（MDC 已有 traceId）再次 {@link #bind}，下一跑继续同源。</li>
 *   <li>恢复扫描 / 看门狗等无请求上下文的投递不写入（拦截时 MDC 无 traceId），
 *       worker 取不到即生成新 traceId。</li>
 *   <li>条目带时间戳，{@link #BIND_TTL_MS} 后视为过期清理；map 超 {@link #MAX_ENTRIES}
 *       时清扫全部过期条目，杜绝内存泄漏。</li>
 * </ul>
 * <b>边界声明</b>：RocketMQ 跨进程投递无法经内存传送带传递 traceId（应由消息属性/header
 * 透传，待投递组件后续支持），该模式下 worker 生成新 traceId 并在日志体现。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class TaskTraceConveyor {

    private static final Logger log = LoggerFactory.getLogger(TaskTraceConveyor.class);

    /** 绑定 TTL：退避最大档 2min + 余量，过期条目必是投递后从未被消费的孤儿。 */
    static final long BIND_TTL_MS = 10 * 60_000L;

    /** 条目数硬上限：超过则触发一次过期清扫，极端情况下保护内存。 */
    static final int MAX_ENTRIES = 10_000;

    private record Entry(String traceId, long atMillis) {
    }

    private final ConcurrentHashMap<String, Entry> bindings = new ConcurrentHashMap<>();

    /** 以 taskId 绑定 traceId（投递调用线程调用）；traceId 为空忽略。 */
    public void bind(String taskId, String traceId) {
        if (taskId == null || traceId == null || traceId.isBlank()) {
            return;
        }
        bindings.put(taskId, new Entry(traceId, System.currentTimeMillis()));
        purgeIfNeeded();
    }

    /** 一次性消费任务绑定的 traceId；无绑定/已过期返回 null（调用方据此生成新 traceId）。 */
    public String consume(String taskId) {
        if (taskId == null) {
            return null;
        }
        Entry e = bindings.remove(taskId);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.atMillis() > BIND_TTL_MS) {
            log.debug("任务 traceId 绑定已过期，worker 将生成新 traceId: taskId={}", taskId);
            return null;
        }
        return e.traceId();
    }

    /** 当前绑定数（测试/运维观测用）。 */
    public int size() {
        return bindings.size();
    }

    private void purgeIfNeeded() {
        if (bindings.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        int before = bindings.size();
        bindings.entrySet().removeIf(en -> now - en.getValue().atMillis() > BIND_TTL_MS);
        log.warn("任务 traceId 传送带超上限触发清扫: {} -> {}", before, bindings.size());
    }

    /** 测试辅助：清空全部绑定。 */
    void clear() {
        bindings.clear();
    }

    /** 测试辅助：窥探但不消费。 */
    String peek(String taskId) {
        Entry e = bindings.get(taskId);
        return e == null ? null : e.traceId();
    }

    /** 测试辅助：绑定条目的不可快照样例。 */
    Map<String, Entry> entries() {
        return Map.copyOf(bindings);
    }
}
