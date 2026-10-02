package com.wikiagent.application.parse;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-1 熔断器状态机单测：关闭→达阈值打开→到期半开→成功关闭/失败重开。
 */
class ProviderCircuitBreakerTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advanceSeconds(long sec) {
            now = now.plusSeconds(sec);
        }
    }

    @Test
    void opensAfterConsecutiveFailuresReachThreshold() {
        MutableClock clock = new MutableClock();
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(3, 60, clock);

        cb.recordFailure(com.wikiagent.domain.parse.spi.Capability.OCR);
        cb.recordFailure(com.wikiagent.domain.parse.spi.Capability.OCR);
        assertThat(cb.isOpen(com.wikiagent.domain.parse.spi.Capability.OCR)).isFalse();

        cb.recordFailure(com.wikiagent.domain.parse.spi.Capability.OCR);
        assertThat(cb.isOpen(com.wikiagent.domain.parse.spi.Capability.OCR)).isTrue();
    }

    @Test
    void successResetsFailureCount() {
        MutableClock clock = new MutableClock();
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(3, 60, clock);

        cb.recordFailure(com.wikiagent.domain.parse.spi.Capability.OCR);
        cb.recordFailure(com.wikiagent.domain.parse.spi.Capability.OCR);
        cb.recordSuccess(com.wikiagent.domain.parse.spi.Capability.OCR);
        cb.recordFailure(com.wikiagent.domain.parse.spi.Capability.OCR);

        assertThat(cb.isOpen(com.wikiagent.domain.parse.spi.Capability.OCR)).isFalse();
    }

    @Test
    void transitionsToHalfOpenAfterOpenDurationAndClosesOnSuccess() {
        MutableClock clock = new MutableClock();
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(2, 60, clock);
        var cap = com.wikiagent.domain.parse.spi.Capability.ASR;

        cb.recordFailure(cap);
        cb.recordFailure(cap);
        assertThat(cb.allowRequest(cap)).isFalse();

        clock.advanceSeconds(61);
        assertThat(cb.allowRequest(cap)).isTrue(); // 半开试探
        cb.recordSuccess(cap);
        clock.advanceSeconds(1);
        assertThat(cb.allowRequest(cap)).isTrue(); // 已关闭
    }

    @Test
    void failureInHalfOpenRetripsOpen() {
        MutableClock clock = new MutableClock();
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(1, 60, clock);
        var cap = com.wikiagent.domain.parse.spi.Capability.LAYOUT;

        cb.recordFailure(cap);
        assertThat(cb.isOpen(cap)).isTrue();
        clock.advanceSeconds(61);
        assertThat(cb.allowRequest(cap)).isTrue(); // 半开

        cb.recordFailure(cap);
        assertThat(cb.isOpen(cap)).isTrue(); // 重新打开
        clock.advanceSeconds(30);
        assertThat(cb.isOpen(cap)).isTrue(); // 新计时未到
    }

    @Test
    void breakerStateIsIndependentPerCapability() {
        MutableClock clock = new MutableClock();
        ProviderCircuitBreaker cb = new ProviderCircuitBreaker(1, 60, clock);

        cb.recordFailure(com.wikiagent.domain.parse.spi.Capability.OCR);
        assertThat(cb.isOpen(com.wikiagent.domain.parse.spi.Capability.OCR)).isTrue();
        assertThat(cb.isOpen(com.wikiagent.domain.parse.spi.Capability.TABLE)).isFalse();
    }
}
