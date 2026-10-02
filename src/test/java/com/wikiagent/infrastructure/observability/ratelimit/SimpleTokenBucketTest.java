package com.wikiagent.infrastructure.observability.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link SimpleTokenBucket} 单元测试（可控时钟）。 */
class SimpleTokenBucketTest {

    @Test
    void 桶满时取容量个令牌后超额拒绝并给RetryAfter() {
        AtomicLong clock = new AtomicLong(0);
        SimpleTokenBucket bucket = new SimpleTokenBucket(3, clock::get, 100);

        for (int i = 0; i < 3; i++) {
            assertThat(bucket.tryAcquire("u1").allowed()).isTrue();
        }
        SimpleTokenBucket.Decision denied = bucket.tryAcquire("u1");
        assertThat(denied.allowed()).isFalse();
        // 容量 3/min => 补 1 个令牌需 20s
        assertThat(denied.retryAfterSec()).isBetween(19, 20);
        // 其他键互不影响
        assertThat(bucket.tryAcquire("u2").allowed()).isTrue();
    }

    @Test
    void 按经过时间线性补充且不超过容量() {
        AtomicLong clock = new AtomicLong(0);
        SimpleTokenBucket bucket = new SimpleTokenBucket(60, clock::get, 100);

        for (int i = 0; i < 60; i++) {
            assertThat(bucket.tryAcquire("k").allowed()).isTrue();
        }
        assertThat(bucket.tryAcquire("k").allowed()).isFalse();

        // 过 30 秒补 30 个令牌
        clock.addAndGet(30_000_000_000L);
        for (int i = 0; i < 30; i++) {
            assertThat(bucket.tryAcquire("k").allowed()).isTrue();
        }
        assertThat(bucket.tryAcquire("k").allowed()).isFalse();

        // 空桶后经过很久也不会超过容量
        clock.addAndGet(600_000_000_000L);
        for (int i = 0; i < 60; i++) {
            assertThat(bucket.tryAcquire("k").allowed()).isTrue();
        }
        assertThat(bucket.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void 拒绝后短暂时间内持续拒绝() {
        AtomicLong clock = new AtomicLong(0);
        SimpleTokenBucket bucket = new SimpleTokenBucket(60, clock::get, 100);
        for (int i = 0; i < 60; i++) {
            bucket.tryAcquire("k");
        }
        clock.addAndGet(500_000_000L); // 0.5s，不足 1 个令牌
        assertThat(bucket.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void 非正速率拒绝构造() {
        assertThatThrownBy(() -> new SimpleTokenBucket(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
