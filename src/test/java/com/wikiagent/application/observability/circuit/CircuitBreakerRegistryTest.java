package com.wikiagent.application.observability.circuit;

import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link CircuitBreakerRegistry} 单测：聚合、跳变时间戳、异常/缺失安全回退。 */
class CircuitBreakerRegistryTest {

    private static CircuitStateSupplier supplier(String component, CircuitState state) {
        CircuitStateSupplier s = mock(CircuitStateSupplier.class);
        when(s.component()).thenReturn(component);
        when(s.state()).thenReturn(state);
        return s;
    }

    @Test
    void 空注册表五类组件默认CLOSED() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
        for (String c : CircuitComponents.ALL) {
            assertThat(registry.state(c)).isEqualTo(CircuitState.CLOSED);
            assertThat(registry.isOpen(c)).isFalse();
            assertThat(registry.lastOpenedAt(c)).isNull();
        }
    }

    @Test
    void 构造时收集supplier且snapshot覆盖固定五类() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(List.of(
                supplier(CircuitComponents.LLM, CircuitState.OPEN),
                supplier(CircuitComponents.MILVUS, CircuitState.OPEN)));

        Map<String, CircuitState> snapshot = registry.snapshot();
        assertThat(snapshot).containsOnlyKeys(CircuitComponents.ALL);
        assertThat(snapshot.get(CircuitComponents.LLM)).isEqualTo(CircuitState.OPEN);
        assertThat(snapshot.get(CircuitComponents.MILVUS)).isEqualTo(CircuitState.OPEN);
        assertThat(registry.lastOpenedAt(CircuitComponents.LLM)).isNotNull();
        assertThat(registry.lastOpenedAt(CircuitComponents.MILVUS)).isNotNull();
    }

    @Test
    void CLOSED转OPEN记录时间持续OPEN不重复记录() throws InterruptedException {
        AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
        CircuitStateSupplier s = mock(CircuitStateSupplier.class);
        when(s.component()).thenReturn(CircuitComponents.REDIS);
        when(s.state()).thenAnswer(inv -> state.get());

        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(List.of(s));
        registry.snapshot();
        assertThat(registry.lastOpenedAt(CircuitComponents.REDIS)).isNull();

        state.set(CircuitState.OPEN);
        registry.snapshot();
        var firstOpenedAt = registry.lastOpenedAt(CircuitComponents.REDIS);
        assertThat(firstOpenedAt).isNotNull();

        // 持续 OPEN 不刷新时间戳
        Thread.sleep(10);
        registry.snapshot();
        assertThat(registry.lastOpenedAt(CircuitComponents.REDIS)).isEqualTo(firstOpenedAt);

        // 恢复 CLOSED 后再次 OPEN 重新记录
        state.set(CircuitState.CLOSED);
        registry.snapshot();
        state.set(CircuitState.OPEN);
        Thread.sleep(10);
        registry.snapshot();
        assertThat(registry.lastOpenedAt(CircuitComponents.REDIS)).isAfter(firstOpenedAt);
    }

    @Test
    void supplier抛异常或返回null时安全回退CLOSED() {
        CircuitStateSupplier throwing = mock(CircuitStateSupplier.class);
        when(throwing.component()).thenReturn(CircuitComponents.PARSE);
        when(throwing.state()).thenThrow(new IllegalStateException("boom"));

        CircuitStateSupplier nullState = mock(CircuitStateSupplier.class);
        when(nullState.component()).thenReturn(CircuitComponents.ROCKETMQ);
        when(nullState.state()).thenReturn(null);

        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(
                List.of(throwing, nullState));
        assertThat(registry.state(CircuitComponents.PARSE)).isEqualTo(CircuitState.CLOSED);
        assertThat(registry.state(CircuitComponents.ROCKETMQ)).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void 同组件后注册者覆盖() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(
                List.of(supplier(CircuitComponents.LLM, CircuitState.OPEN)));
        assertThat(registry.isOpen(CircuitComponents.LLM)).isTrue();

        registry.register(supplier(CircuitComponents.LLM, CircuitState.CLOSED));
        assertThat(registry.state(CircuitComponents.LLM)).isEqualTo(CircuitState.CLOSED);
    }
}
