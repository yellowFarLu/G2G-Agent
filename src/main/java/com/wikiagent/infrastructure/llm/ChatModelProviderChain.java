package com.wikiagent.infrastructure.llm;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
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
    /** E4：可选打点器（null 时跳过打点）。 */
    private volatile ModelCallRecorder recorder;

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

    /** 注入打点器（Bean 装配后调用一次）。 */
    public void setRecorder(ModelCallRecorder recorder) {
        this.recorder = recorder;
    }

    public ChainResult call(ChatModelRequest request) {
        return call(request, ModelCallLogPurpose.CHAT, null, null);
    }

    /**
     * 带打点的调用：每个 provider 的成功/失败尝试各落一条 model_call_log。
     */
    public ChainResult call(ChatModelRequest request, ModelCallLogPurpose purpose,
                            String userId, String sessionId) {
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
                recordCall(purpose, provider.name(), resp.model(),
                        resp.promptTokens(), resp.completionTokens(), resp.latencyMs(),
                        true, fallbackFrom, userId, sessionId);
                return new ChainResult(resp, fallbackFrom, fallbackFrom != null);
            } catch (Exception e) {
                breaker.recordFailure(provider.name());
                fallbackFrom = fallbackFrom == null ? provider.name() : fallbackFrom;
                recordCall(purpose, provider.name(), modelOf(provider), null, null, null,
                        false, null, userId, sessionId);
                log.warn("provider 调用失败，降级: {}: {}", provider.name(), e.getMessage());
            }
        }
        // 全链不可用：末端空响应兜底（不抛异常，由上层走拒答/兜底话术）
        return new ChainResult(new ChatModelResponse("", "none", 0, 0, 0L), fallbackFrom, true);
    }

    private void recordCall(ModelCallLogPurpose purpose, String provider, String model,
                            Integer tokensIn, Integer tokensOut, Long latencyMs,
                            boolean success, String fallbackFrom, String userId, String sessionId) {
        ModelCallRecorder r = recorder;
        if (r != null) {
            r.record(purpose, provider, model, tokensIn, tokensOut, latencyMs,
                    success, fallbackFrom, userId, sessionId);
        }
    }

    private static String modelOf(ChatModelProvider provider) {
        if (provider instanceof DashScopeChatModelProvider d) {
            return d.model();
        }
        if (provider instanceof NoOpChatModelProvider n) {
            return n.name();
        }
        return provider.name();
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
