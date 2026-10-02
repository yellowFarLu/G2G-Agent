package com.wikiagent.infrastructure.observability.metrics;

import com.wikiagent.application.observability.circuit.CircuitBreakerRegistry;
import com.wikiagent.domain.observability.circuit.CircuitComponents;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 子项目 I（AC-I2/I4）第三方熔断状态指标：{@code wikiagent_circuit_state}
 * （Gauge，tag component=milvus|parse|llm|redis|rocketmq，state=closed|open；0/1）。
 * 数据来自 {@link CircuitBreakerRegistry}；@Scheduled 定期刷新跳变时间戳。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class CircuitMetricsBinder {

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final CircuitBreakerRegistry circuitRegistry;

    public CircuitMetricsBinder(ObjectProvider<MeterRegistry> registryProvider,
                                CircuitBreakerRegistry circuitRegistry) {
        this.registryProvider = registryProvider;
        this.circuitRegistry = circuitRegistry;
    }

    @PostConstruct
    void register() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        for (String component : CircuitComponents.ALL) {
            Gauge.builder("wikiagent.circuit.state", circuitRegistry,
                            r -> r.isOpen(component) ? 1.0 : 0.0)
                    .tag("component", component)
                    .tag("state", "open")
                    .description("第三方组件熔断/降级状态（1=OPEN 降级中，0=CLOSED 正常）")
                    .register(registry);
            Gauge.builder("wikiagent.circuit.state", circuitRegistry,
                            r -> r.isOpen(component) ? 0.0 : 1.0)
                    .tag("component", component)
                    .tag("state", "closed")
                    .description("第三方组件正常状态（1=CLOSED，0=OPEN 降级中）")
                    .register(registry);
        }
    }

    /** 定期采样以记录 CLOSED→OPEN 跳变时间（指标抓取之外的时间戳证据）。 */
    @Scheduled(fixedDelayString = "${wikiagent.observability.circuit-scan-interval-ms:15000}")
    public void scan() {
        circuitRegistry.snapshot();
    }
}
