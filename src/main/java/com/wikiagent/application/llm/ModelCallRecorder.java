package com.wikiagent.application.llm;

import com.wikiagent.domain.llm.ModelCallLog;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.ModelCallLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * E4 模型调用打点器：组装 {@link ModelCallLog}（traceId 取 MDC）并落库。
 * 任何持久化异常静默吞掉，不影响模型主链路。
 */
@Service
public class ModelCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModelCallRecorder.class);

    private final ModelCallLogRepository repository;
    private final ModelPricingService pricing;

    public ModelCallRecorder(ModelCallLogRepository repository, ModelPricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    /** 记录一次模型调用。 */
    public void record(ModelCallLogPurpose purpose, String provider, String model,
                       Integer tokensIn, Integer tokensOut, Long latencyMs,
                       boolean success, String fallbackFrom, String userId, String sessionId) {
        try {
            Double cost = pricing.estimate(model, tokensIn, tokensOut);
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
        }
    }

    /** MDC 取 traceId（I1 尚未全量铺开，取不到返回 null）。 */
    private static String currentTraceId() {
        try {
            String t = MDC.get("traceId");
            return (t == null || t.isBlank()) ? null : t;
        } catch (Exception e) {
            return null;
        }
    }
}
