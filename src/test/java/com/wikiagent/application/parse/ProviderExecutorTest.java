package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B-1 执行器单测：瞬时错误重试 2 次后成功；非重试错误不重试；超时算瞬时错误；
 * 熔断打开后快速失败不再调用供应商。
 */
class ProviderExecutorTest {

    private ParseProperties props(int maxAttempts, int threshold) {
        ParseProperties p = new ParseProperties();
        p.setMaxAttempts(maxAttempts);
        p.setRetryBackoffBaseMs(0);
        p.setCircuitFailureThreshold(threshold);
        p.setCircuitOpenSec(60);
        return p;
    }

    @Test
    void retriesRetryableFailuresThenSucceeds() {
        ProviderExecutor executor = new ProviderExecutor(props(3, 10));
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute(Capability.OCR, 30, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ParseProviderException("503", true);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void nonRetryableErrorFailsFastWithoutRetry() {
        ProviderExecutor executor = new ProviderExecutor(props(3, 10));
        AtomicInteger calls = new AtomicInteger();

        Throwable t = catchThrowable(() -> executor.execute(Capability.OCR, 30, () -> {
            calls.incrementAndGet();
            throw new ParseProviderException("401 鉴权失败", false);
        }));

        assertThat(t).isInstanceOf(ParseProviderException.class)
                .hasMessageContaining("401");
        assertThat(((ParseProviderException) t).retryable()).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void timeoutIsRetryableAndEventuallyThrows() {
        ProviderExecutor executor = new ProviderExecutor(props(2, 10));
        AtomicInteger calls = new AtomicInteger();

        Throwable t = catchThrowable(() -> executor.execute(Capability.ASR, 1, () -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow";
        }));

        assertThat(t).isInstanceOf(ParseProviderException.class);
        assertThat(((ParseProviderException) t).retryable()).isTrue();
        assertThat(t.getMessage()).contains("最大尝试次数");
        assertThat(calls).hasValue(2);
    }

    @Test
    void circuitOpensAndShortCircuitsFurtherCalls() {
        ProviderExecutor executor = new ProviderExecutor(props(1, 2));
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            catchThrowable(() -> executor.execute(Capability.TABLE, 5, () -> {
                calls.incrementAndGet();
                throw new ParseProviderException("网络抖动", true);
            }));
        }
        // 熔断打开：第三次直接拒绝，不进入供应商
        Throwable t = catchThrowable(() -> executor.execute(Capability.TABLE, 5, () -> {
            calls.incrementAndGet();
            return "x";
        }));

        assertThat(t).isInstanceOf(ParseProviderException.class)
                .hasMessageContaining("熔断");
        assertThat(((ParseProviderException) t).retryable()).isFalse();
        assertThat(calls).hasValue(2);
    }

    @Test
    void unexpectedExceptionIsTreatedAsRetryable() {
        ProviderExecutor executor = new ProviderExecutor(props(2, 10));
        AtomicInteger calls = new AtomicInteger();

        Throwable t = catchThrowable(() -> executor.execute(Capability.LAYOUT, 5, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }));

        assertThat(t).isInstanceOf(ParseProviderException.class);
        assertThat(calls).hasValue(2);
    }
}
