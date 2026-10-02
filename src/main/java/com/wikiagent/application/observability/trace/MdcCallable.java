package com.wikiagent.application.observability.trace;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 子项目 I（AC-I1）MDC 跨线程透传包装器（{@link Callable} 版本）。
 * 语义与 {@link MdcRunnable} 一致：提交时快照、执行前还原、执行后恢复现场。
 */
public final class MdcCallable<V> implements Callable<V> {

    private final Callable<V> delegate;
    private final Map<String, String> mdcSnapshot;

    private MdcCallable(Callable<V> delegate) {
        this.delegate = delegate;
        this.mdcSnapshot = MDC.getCopyOfContextMap();
    }

    /** 包装一个 Callable：复制当前线程 MDC，运行时在目标线程还原，结束后恢复现场。 */
    public static <V> Callable<V> wrap(Callable<V> task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为 null");
        }
        return new MdcCallable<>(task);
    }

    @Override
    public V call() throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (mdcSnapshot != null) {
            MDC.setContextMap(mdcSnapshot);
        } else {
            MDC.clear();
        }
        try {
            return delegate.call();
        } finally {
            MdcRunnable.restore(previous);
        }
    }
}
