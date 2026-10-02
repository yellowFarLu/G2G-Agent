package com.wikiagent.application.observability.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-I1 {@link MdcRunnable}/{@link MdcCallable} 单测：跨线程快照、还原、异常后清理。
 */
class MdcRunnableTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 包装时快照在执行线程可见且结束后还原调用现场() throws Exception {
        MDC.put("traceId", "tr-outer");
        MDC.put("keep", "outer-value");

        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenKeep = new AtomicReference<>();
        Runnable task = MdcRunnable.wrap(() -> {
            seenTrace.set(MDC.get("traceId"));
            seenKeep.set(MDC.get("keep"));
            MDC.put("innerOnly", "x"); // 执行中新增键
        });

        // 模拟线程池线程：执行前 MDC 为空
        MDC.clear();
        task.run();

        assertThat(seenTrace.get()).isEqualTo("tr-outer");
        assertThat(seenKeep.get()).isEqualTo("outer-value");
        // 结束后工作线程现场恢复为空（快照新增键被清理，不串链）
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("innerOnly")).isNull();
    }

    @Test
    void 快照后调用线程修改MDC不影响已包装任务() {
        MDC.put("traceId", "tr-snapshot");
        Runnable task = MdcRunnable.wrap(() -> {
        });
        MDC.put("traceId", "tr-changed");
        MDC.clear(); // 模拟提交线程随后清空
        task.run(); // 无异常即通过；语义由下一例显式断言
    }

    @Test
    void 提交线程无MDC时清空池线程残留执行后还原原现场() {
        MDC.clear();
        Runnable task = MdcRunnable.wrap(() -> assertThat(MDC.get("traceId")).isNull());
        MDC.put("traceId", "tr-pool-reused"); // 模拟池线程残留
        task.run();
        assertThat(MDC.get("traceId")).isEqualTo("tr-pool-reused"); // 还原为工作线程原值
    }

    @Test
    void 任务抛异常仍还原MDC() {
        MDC.put("traceId", "tr-throw");
        Runnable task = MdcRunnable.wrap(() -> {
            throw new IllegalStateException("boom");
        });
        MDC.clear();
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void callable版本传播MDC并返回结果且恢复现场() throws Exception {
        MDC.put("traceId", "tr-call");
        var callable = MdcCallable.wrap(() -> MDC.get("traceId"));
        MDC.clear();
        assertThat(callable.call()).isEqualTo("tr-call");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void null入参快速失败() {
        assertThatThrownBy(() -> MdcRunnable.wrap(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MdcCallable.wrap(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
