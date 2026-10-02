package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.application.parse.ProviderExecutor;
import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC-I4 ⑤ parse/rerank 不可用：能力级熔断打开后立即短路（不再调用供应商），
 * 观测供给器反映 OPEN；成功后恢复 CLOSED。
 */
class ParseCircuitDegradationTest {

    private ProviderExecutor executor() {
        ParseProperties props = new ParseProperties();
        props.setMaxAttempts(1);
        props.setCircuitFailureThreshold(1);
        props.setCircuitOpenSec(60);
        return new ProviderExecutor(props);
    }

    @Test
    void 能力失败后熔断打开并短路后续调用供给器报OPEN() {
        ProviderExecutor executor = executor();

        // 首次调用：供应商抛错（如 OCR 服务不可达）
        assertThatThrownBy(() -> executor.execute(Capability.OCR, 5, () -> {
                    throw new RuntimeException("OCR 服务连接超时");
                }))
                .isInstanceOf(ParseProviderException.class);
        assertThat(executor.breaker().isOpen(Capability.OCR)).isTrue();

        // 熔断打开后立即短路：不再进入供应商 lambda
        assertThatThrownBy(() -> executor.execute(Capability.OCR, 5, () -> {
                    throw new AssertionError("熔断打开时不应实际调用供应商");
                }))
                .isInstanceOf(ParseProviderException.class)
                .hasMessageContaining("熔断中");

        // 其他能力不受影响
        assertThat(executor.breaker().isOpen(Capability.LAYOUT)).isFalse();

        // 观测供给器：任一能力 OPEN 则 parse 组件 OPEN
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderExecutor> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(executor);
        ParseCircuitStateSupplier supplier = new ParseCircuitStateSupplier(op);
        assertThat(supplier.component()).isEqualTo(CircuitComponents.PARSE);
        assertThat(supplier.state()).isEqualTo(CircuitState.OPEN);

        // 半开成功后恢复 CLOSED
        executor.breaker().recordSuccess(Capability.OCR);
        assertThat(executor.breaker().isOpen(Capability.OCR)).isFalse();
        assertThat(supplier.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void 非重试类错误不累计熔断() {
        ProviderExecutor executor = executor();
        assertThatThrownBy(() -> executor.execute(Capability.ASR, 5, () -> {
                    throw new ParseProviderException("鉴权失败 401", false);
                }))
                .isInstanceOf(ParseProviderException.class)
                .extracting(e -> ((ParseProviderException) e).retryable())
                .isEqualTo(false);
        assertThat(executor.breaker().isOpen(Capability.ASR)).isFalse();
    }
}
