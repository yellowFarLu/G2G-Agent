package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

/**
 * ChatModelProvider 降级链：按候选顺序尝试，单 provider 失败（重试 {@code maxAttempts} 次后仍失败
 * 或熔断打开）降级到下一个，并在结果上携带 fallbackFrom（首个失败/被熔断跳过的 provider 名）。
 * <p>
 * 超时由 provider 实现内部控制；重试/熔断在本层统一封装（每 provider 独立
 * {@link LlmCircuitBreaker}，仿 Milvus 冷却模式）。所有候选都不可用时返回空响应
 * （degraded=true），保证链路不抛异常。
 */
public class ChatModelProviderChain {

    private static final Logger log = LoggerFactory.getLogger(ChatModelProviderChain.class);

    /** 链调用结果：实际响应 + 降级来源 + 是否发生了降级。 */
    public record ChainResult(ChatModelResponse response, String fallbackFrom, boolean degraded) {
    }

    private final List<ChatModelProvider> candidates;
    private final LlmCircuitBreaker breaker;
    private final int maxAttempts;
    private final long retryBackoffMs;

    public ChatModelProviderChain(List<ChatModelProvider> candidates,
                                  int maxAttempts, long retryBackoffMs,
                                  int circuitFailureThreshold, long circuitOpenSec) {
        this(candidates, maxAttempts, retryBackoffMs,
                new LlmCircuitBreaker(circuitFailureThreshold, circuitOpenSec, Clock.systemUTC()));
    }

    public ChatModelProviderChain(List<ChatModelProvider> candidates,
                                  int maxAttempts, long retryBackoffMs,
                                  LlmCircuitBreaker breaker) {
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
        this.breaker = breaker;
    }

    public ChainResult call(ChatModelRequest request) {
        String fallbackFrom = null;
        for (ChatModelProvider provider : candidates) {
            boolean last = candidates.indexOf(provider) == candidates.size() - 1;
            if (!provider.available() && !last) {
                // 不可用（如缺 key）直接跳过；末端 provider 即使不可用也作为兜底执行
                fallbackFrom = fallbackFrom == null ? provider.name() : fallbackFrom;
                continue;
            }
            if (!breaker.allowRequest(provider.name())) {
                log.warn("provider 熔断中，跳过: {}", provider.name());
                fallbackFrom = fallbackFrom == null ? provider.name() : fallbackFrom;
                continue;
            }
            try {
                ChatModelResponse resp = callWithRetry(provider, request);
                breaker.recordSuccess(provider.name());
                return new ChainResult(resp, fallbackFrom, fallbackFrom != null);
            } catch (Exception e) {
                breaker.recordFailure(provider.name());
                fallbackFrom = fallbackFrom == null ? provider.name() : fallbackFrom;
                log.warn("provider 调用失败，降级: {}: {}", provider.name(), e.getMessage());
            }
        }
        // 全链不可用：末端空响应兜底（不抛异常，由上层走拒答/兜底话术）
        return new ChainResult(new ChatModelResponse("", "none", 0, 0, 0L), fallbackFrom, true);
    }

    private ChatModelResponse callWithRetry(ChatModelProvider provider, ChatModelRequest request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return provider.call(request);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts && retryBackoffMs > 0) {
                    try {
                        Thread.sleep(retryBackoffMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException("provider call failed") : last;
    }
}
