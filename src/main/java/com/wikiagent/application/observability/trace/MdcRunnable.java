package com.wikiagent.application.observability.trace;

import org.slf4j.MDC;

import java.util.Map;

/**
 * 子项目 I（AC-I1）MDC 跨线程透传包装器。
 * <p>
 * 线程池不会继承提交线程的 SLF4J MDC。{@link #wrap(Runnable)} 在<b>提交线程</b>调用时
 * 快照当前 MDC（核心键 {@code traceId}），任务在<b>工作线程</b>执行前把快照整体 put，
 * 执行结束（含抛异常）后还原工作线程原 MDC：原有键恢复原值、快照中新增的键 remove，
 * 避免线程池复用造成 traceId 串链。
 * <p>
 * 典型用法：{@code executor.submit(MdcRunnable.wrap(() -> doWork()))}。
 */
public final class MdcRunnable implements Runnable {

    private final Runnable delegate;
    /** 提交时刻的 MDC 快照（不可变视图复制）。 */
    private final Map<String, String> mdcSnapshot;

    private MdcRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.mdcSnapshot = MDC.getCopyOfContextMap();
    }

    /** 包装一个 Runnable：复制当前线程 MDC，运行时在目标线程还原，结束后恢复现场。 */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为 null");
        }
        return new MdcRunnable(task);
    }

    @Override
    public void run() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (mdcSnapshot != null) {
            MDC.setContextMap(mdcSnapshot);
        } else {
            MDC.clear();
        }
        try {
            delegate.run();
        } finally {
            restore(previous);
        }
    }

    /** 恢复工作线程执行前的 MDC（含 null 语义）。 */
    static void restore(Map<String, String> previous) {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
