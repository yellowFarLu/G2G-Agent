package com.wikiagent.application.parse;

import com.wikiagent.domain.parse.spi.Capability;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * 按能力独立计数的熔断器（子项目 B §2.2）：连续失败达阈值 → 打开 {@code openSec} 秒 →
 * 半开试探一次（成功关闭，失败重新计时）。不依赖 resilience4j 注解，便于确定性单测。
 */
public class ProviderCircuitBreaker {

    private enum State {CLOSED, OPEN, HALF_OPEN}

    private static final class Stat {
        State state = State.CLOSED;
        int failures;
        Instant openedAt;
    }

    private final Map<Capability, Stat> stats = new EnumMap<>(Capability.class);
    private final int failureThreshold;
    private final long openSec;
    private final Clock clock;

    public ProviderCircuitBreaker(int failureThreshold, int openSec, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openSec = openSec;
        this.clock = clock;
    }

    private Stat stat(Capability c) {
        return stats.computeIfAbsent(c, k -> new Stat());
    }

    /** 是否允许一次试探（CLOSED 或 OPEN 到期转 HALF_OPEN）。 */
    public synchronized boolean allowRequest(Capability c) {
        Stat s = stat(c);
        if (s.state == State.OPEN && Instant.now(clock).isAfter(s.openedAt.plusSeconds(openSec))) {
            s.state = State.HALF_OPEN;
        }
        return s.state != State.OPEN;
    }

    public synchronized boolean isOpen(Capability c) {
        return !allowRequest(c);
    }

    public synchronized void recordSuccess(Capability c) {
        Stat s = stat(c);
        s.state = State.CLOSED;
        s.failures = 0;
        s.openedAt = null;
    }

    public synchronized void recordFailure(Capability c) {
        Stat s = stat(c);
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
    synchronized int failures(Capability c) {
        return stat(c).failures;
    }
}
