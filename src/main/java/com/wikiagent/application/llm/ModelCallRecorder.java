package com.wikiagent.application.llm;

import com.wikiagent.domain.llm.ModelCallLog;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.ModelCallLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * E4 模型调用打点器：组装 {@link ModelCallLog}（traceId 取 MDC）并落库。
 * 任何持久化异常静默吞掉，不影响模型主链路。
 * <p>
 * 子项目 I（AC-I2）：成功落库同一处追加 wikiagent.* 计量——
 * 调用总量（含 status=failed）、token 量（in/out）、费用（仅成功且 cost 非空）。
 */
@Service
public class ModelCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModelCallRecorder.class);

    private final ModelCallLogRepository repository;
    private final ModelPricingService pricing;
    /** AC-I2 计量钩子（可缺省：手工 new 的旧装配/无 registry 时仅落库）。 */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public ModelCallRecorder(ModelCallLogRepository repository, ModelPricingService pricing) {
        this(repository, pricing, null);
    }

    public ModelCallRecorder(ModelCallLogRepository repository, ModelPricingService pricing,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.repository = repository;
        this.pricing = pricing;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /** 记录一次模型调用。 */
    public void record(ModelCallLogPurpose purpose, String provider, String model,
                       Integer tokensIn, Integer tokensOut, Long latencyMs,
                       boolean success, String fallbackFrom, String userId, String sessionId) {
        Double cost = null;
        try {
            cost = pricing.estimate(model, tokensIn, tokensOut);
            repository.save(ModelCallLog.builder()
                    .traceId(currentTraceId())
                    .userId(userId)
                    .sessionId(sessionId)
                    .purpose(purpose)
                    .provider(provider)
                    .model(model)
                    .tokensIn(tokensIn)
                    .tokensOut(tokensOut)
                    .costEstimate(cost)
                    .latencyMs(latencyMs)
                    .status(success ? ModelCallLog.Status.OK : ModelCallLog.Status.ERROR)
                    .fallbackFrom(fallbackFrom)
                    .build());
        } catch (Exception e) {
            log.warn("model_call_log 落库失败（不影响主链路）: {}", e.getMessage());
            // 估价异常时 cost 不可信，不计费用
            cost = null;
        }
        recordMetrics(purpose, provider, model, tokensIn, tokensOut, success, fallbackFrom, cost);
    }

    /**
     * AC-I2 模型指标：
     * <ul>
     *   <li>{@code wikiagent_model_calls_total}{purpose,provider,model,status,fallback} 每次必计（含失败）；</li>
     *   <li>{@code wikiagent_model_tokens_total}{direction=in|out,model} 仅有 token 数据时计；</li>
     *   <li>{@code wikiagent_model_cost_total}{model} 仅成功且成本可估算时计（失败不估损）。</li>
     * </ul>
     */
    private void recordMetrics(ModelCallLogPurpose purpose, String provider, String model,
                               Integer tokensIn, Integer tokensOut, boolean success,
                               String fallbackFrom, Double cost) {
        MeterRegistry registry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        try {
            registry.counter("wikiagent.model.calls.total",
                    "purpose", purpose == null ? "UNKNOWN" : purpose.name(),
                    "provider", nullToUnknown(provider),
                    "model", nullToUnknown(model),
                    "status", success ? "ok" : "failed",
                    "fallback", fallbackFrom != null ? "true" : "false").increment();
            if (tokensIn != null && tokensIn > 0) {
                registry.counter("wikiagent.model.tokens.total",
                        "direction", "in", "model", nullToUnknown(model)).increment(tokensIn);
            }
            if (tokensOut != null && tokensOut > 0) {
                registry.counter("wikiagent.model.tokens.total",
                        "direction", "out", "model", nullToUnknown(model)).increment(tokensOut);
            }
            if (success && cost != null) {
                registry.counter("wikiagent.model.cost.total",
                        "model", nullToUnknown(model)).increment(cost);
            }
        } catch (Exception e) {
            log.warn("模型指标记录失败（不影响主链路）: {}", e.getMessage());
        }
    }

    private static String nullToUnknown(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    /** MDC 取 traceId（I1 未铺到的线程返回 null）。 */
    private static String currentTraceId() {
        try {
            String t = MDC.get("traceId");
            return (t == null || t.isBlank()) ? null : t;
        } catch (Exception e) {
            return null;
        }
    }
}
