package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 供应商调用统一策略（规格 §2.2）：熔断闸门 → 独立线程超时 → 瞬时错误指数退避重试。
 * 非重试类错误（鉴权/参数）立即上抛且不累计熔断；超时/网络类错误重试并累计熔断。
 */
@Component
public class ProviderExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProviderExecutor.class);

    private final ParseProperties props;
    private final ProviderCircuitBreaker breaker;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "doc-ai-provider");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderExecutor(ParseProperties props) {
        this(props, Clock.systemDefaultZone());
    }

    ProviderExecutor(ParseProperties props, Clock clock) {
        this.props = props;
        this.breaker = new ProviderCircuitBreaker(
                props.getCircuitFailureThreshold(), props.getCircuitOpenSec(), clock);
    }

    public <T> T execute(Capability capability, int timeoutSec, Supplier<T> call) {
        if (breaker.isOpen(capability)) {
            throw new ParseProviderException(
                    "文档 AI 能力熔断中: " + capability + "（" + props.getCircuitOpenSec() + "s 后半开试探）", false);
        }
        int max = Math.max(1, props.getMaxAttempts());
        Exception last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                T result = runWithTimeout(capability, timeoutSec, call);
                breaker.recordSuccess(capability);
                return result;
            } catch (ParseProviderException e) {
                last = e;
                if (!e.retryable()) {
                    throw e;
                }
                breaker.recordFailure(capability);
            } catch (Exception e) {
                last = e;
                breaker.recordFailure(capability);
            }
            if (attempt < max) {
                if (breaker.isOpen(capability)) {
                    throw new ParseProviderException(
                            "文档 AI 能力熔断打开，终止重试: " + capability, false, last);
                }
                long backoff = props.getRetryBackoffBaseMs() * (1L << (attempt - 1));
                log.warn("文档 AI 调用失败，{}ms 后重试 ({}/{}) capability={} err={}",
                        backoff, attempt, max - 1, capability,
                        last == null ? "" : last.getMessage());
                sleep(backoff);
            }
        }
        throw new ParseProviderException(
                "文档 AI 调用失败，已达最大尝试次数 " + max + ": " + capability
                        + (last == null ? "" : " (" + last.getMessage() + ")"),
                true, last);
    }

    private <T> T runWithTimeout(Capability capability, int timeoutSec, Supplier<T> call) {
        Future<T> future = pool.submit((Callable<T>) call::get);
        try {
            return future.get(Math.max(1, timeoutSec), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ParseProviderException(
                    "文档 AI 调用超时(" + timeoutSec + "s): " + capability, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ParseProviderException("文档 AI 调用被中断: " + capability, false, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ParseProviderException ppe) {
                throw ppe;
            }
            throw new ParseProviderException(
                    "文档 AI 调用异常: " + capability + " - " + cause.getMessage(), true, cause);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 暴露熔断器状态供可观测层（子项目 I）读取。 */
    public ProviderCircuitBreaker breaker() {
        return breaker;
    }
}
