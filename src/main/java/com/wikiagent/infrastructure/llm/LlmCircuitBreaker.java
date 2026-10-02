package com.wikiagent.infrastructure.llm;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 provider 名独立计数的熔断器（仿 Milvus/parse ProviderCircuitBreaker 模式）：
 * 连续失败达阈值 → 打开 {@code openSec} 秒 → 半开试探一次（成功关闭，失败重新计时）。
 * 不依赖 resilience4j 注解，便于确定性单测。
 */
public class LlmCircuitBreaker {

    private enum State {CLOSED, OPEN, HALF_OPEN}

    private static final class Stat {
        State state = State.CLOSED;
        int failures;
        Instant openedAt;
    }

    private final Map<String, Stat> stats = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long openSec;
    private final Clock clock;

    public LlmCircuitBreaker(int failureThreshold, long openSec, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openSec = openSec;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    private Stat stat(String provider) {
        return stats.computeIfAbsent(provider, k -> new Stat());
    }

    /** 是否允许一次试探（CLOSED 或 OPEN 到期转 HALF_OPEN）。 */
    public synchronized boolean allowRequest(String provider) {
        Stat s = stat(provider);
        if (s.state == State.OPEN && Instant.now(clock).isAfter(s.openedAt.plusSeconds(openSec))) {
            s.state = State.HALF_OPEN;
        }
        return s.state != State.OPEN;
    }

    public synchronized void recordSuccess(String provider) {
        Stat s = stat(provider);
        s.state = State.CLOSED;
        s.failures = 0;
        s.openedAt = null;
    }

    public synchronized void recordFailure(String provider) {
        Stat s = stat(provider);
        if (s.state == State.HALF_OPEN) {
            trip(s);
            return;
        }
        s.failures++;
        if (s.failures >= failureThreshold) {
            trip(s);
        }
    }

    private void trip(Stat s) {
        s.state = State.OPEN;
        s.openedAt = Instant.now(clock);
    }

    /** 测试/运维：当前连续失败数。 */
    public synchronized int failures(String provider) {
        return stat(provider).failures;
    }

    /** 测试/运维：是否处于打开状态。 */
    public synchronized boolean isOpen(String provider) {
        return !allowRequest(provider);
    }
}
