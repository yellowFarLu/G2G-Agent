package com.wikiagent.application.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 退避映射测试：attempt→延迟等级→时长（规格：10s/30s/2m，最多 3 次重试）。
 */
class BackoffTest {

    @Test
    void delayLevelForAttemptMapsThreeTiers() {
        assertThat(Backoff.delayLevelForAttempt(1)).isEqualTo(3);
        assertThat(Backoff.delayLevelForAttempt(2)).isEqualTo(4);
        assertThat(Backoff.delayLevelForAttempt(3)).isEqualTo(6);
        assertThat(Backoff.delayLevelForAttempt(4)).isEqualTo(6);
        assertThat(Backoff.delayLevelForAttempt(0)).isEqualTo(6);
    }

    @Test
    void durationForAttemptMapsThreeTiers() {
        assertThat(Backoff.durationForAttempt(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.durationForAttempt(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Backoff.durationForAttempt(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(Backoff.durationForAttempt(4)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void durationForLevelMapsRocketMqTiers() {
        assertThat(Backoff.durationForLevel(3)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.durationForLevel(4)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Backoff.durationForLevel(6)).isEqualTo(Duration.ofMinutes(2));
        assertThat(Backoff.durationForLevel(5)).isEqualTo(Duration.ofMinutes(2));
    }
}
