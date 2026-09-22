package com.wikiagent.infrastructure.trace;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * v1-v2 §8 全链路可观测 - Micrometer 指标配置。
 * <p>
 * 定义 Agent 执行关键指标：
 * <ul>
 *   <li>{@code agent.llm.calls} (Timer) — LLM 调用耗时与次数</li>
 *   <li>{@code agent.tool.calls} (Timer) — 工具调用耗时与次数</li>
 *   <li>{@code agent.guardrail.blocks} (Counter) — 安全护栏拦截次数</li>
 * </ul>
 * 通过 Actuator + Prometheus 端点暴露（management.endpoints.web.exposure.include）。
 */
@Configuration
public class MicrometerConfig {

    /** LLM 调用计时器：记录每次 LLM 调用耗时。 */
    @Bean
    Timer agentLlmCallsTimer(MeterRegistry registry) {
        return Timer.builder("agent.llm.calls")
                .description("LLM 调用耗时与次数")
                .register(registry);
    }

    /** 工具调用计时器：记录每次工具调用耗时。 */
    @Bean
    Timer agentToolCallsTimer(MeterRegistry registry) {
        return Timer.builder("agent.tool.calls")
                .description("工具调用耗时与次数")
                .register(registry);
    }

    /** 安全护栏拦截计数器：记录被护栏拦截的请求数。 */
    @Bean
    Counter agentGuardrailBlocksCounter(MeterRegistry registry) {
        return Counter.builder("agent.guardrail.blocks")
                .description("安全护栏拦截次数")
                .register(registry);
    }
}
