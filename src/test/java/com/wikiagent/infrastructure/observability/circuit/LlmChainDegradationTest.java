package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.llm.LlmCircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-I4 ④ LLM 不可用：全部 provider 失败时降级链不抛异常，返回 degraded=true 空响应；
 * 熔断打开后观测供给器（反射只读内部 breaker）报 OPEN；恢复后 CLOSED。
 */
class LlmChainDegradationTest {

    /** 固定抛错的假 provider。 */
    private static ChatModelProvider failingProvider(String name) {
        return new ChatModelProvider() {
            @Override
            public ChatModelResponse call(ChatModelRequest request) {
                throw new RuntimeException(name + " 不可达");
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    @Test
    void 全部provider失败时返回degraded空响应不抛异常且熔断打开() {
        ChatModelProviderChain chain = new ChatModelProviderChain(
                List.of(failingProvider("fake-a"), failingProvider("fake-b")),
                1, 0L, 1, 60);

        ChatModelProviderChain.ChainResult result =
                chain.call(new ChatModelRequest("sys", "你好"));

        // 链路不 500：空响应兜底 + 降级标记 + 降级来源
        assertThat(result.degraded()).isTrue();
        assertThat(result.fallbackFrom()).isEqualTo("fake-a");
        assertThat(result.response()).isNotNull();
        assertThat(result.response().content()).isEmpty();
        assertThat(result.response().model()).isEqualTo("none");

        // 观测层反射只读：组件 OPEN
        assertThat(LlmChainCircuitStateSupplier.stateOf(chain)).isEqualTo(CircuitState.OPEN);

        // 熔断恢复后（recordSuccess 模拟半开试探成功）回 CLOSED
        // 通过 allowRequest 到达半开后再成功：openSec=60 未到期，直接用同一 breaker 不可访问，
        // 故构造一个新链（新 breaker）验证 CLOSED 基线反射可读
        ChatModelProviderChain healthy = new ChatModelProviderChain(
                List.of(failingProvider("fresh")), 1, 0L, 1, 60);
        assertThat(LlmChainCircuitStateSupplier.stateOf(healthy)).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void provider不可用时被跳过最终也走degraded兜底() {
        ChatModelProvider unavailable = new ChatModelProvider() {
            @Override
            public ChatModelResponse call(ChatModelRequest request) {
                throw new AssertionError("available=false 时不应被调用（末端除外）");
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String name() {
                return "missing-key";
            }
        };
        // 两个均不可用：末端即使 available=false 也兜底执行 → 抛错被链捕获
        ChatModelProviderChain chain = new ChatModelProviderChain(
                List.of(unavailable, unavailable), 1, 0L, 1, 60);
        ChatModelProviderChain.ChainResult result =
                chain.call(new ChatModelRequest("sys", "hi"));
        assertThat(result.degraded()).isTrue();
        assertThat(result.fallbackFrom()).isEqualTo("missing-key");
    }

    @Test
    void 熔断器半开试探成功后关闭() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(1, -3600, null);
        breaker.recordFailure("p1");
        assertThat(breaker.isOpen("p1")).isFalse(); // 负 openSec：OPEN 立即到期 → 半开
        breaker.allowRequest("p1");
        breaker.recordSuccess("p1");
        assertThat(breaker.failures("p1")).isZero();
        assertThat(breaker.isOpen("p1")).isFalse();
    }

    @Test
    void 组件常量包含llm() {
        assertThat(CircuitComponents.ALL).contains("llm");
    }
}
