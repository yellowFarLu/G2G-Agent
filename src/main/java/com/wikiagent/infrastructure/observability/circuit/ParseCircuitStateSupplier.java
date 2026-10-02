package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.application.parse.ProviderCircuitBreaker;
import com.wikiagent.application.parse.ProviderExecutor;
import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import com.wikiagent.domain.parse.spi.Capability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * parse 组件熔断供给：复用 {@link ProviderExecutor#breaker()} 公开 getter，
 * 任一能力（OCR/ASR/LAYOUT/TABLE）熔断打开即视为组件 OPEN。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class ParseCircuitStateSupplier implements CircuitStateSupplier {

    private final ObjectProvider<ProviderExecutor> executorProvider;

    public ParseCircuitStateSupplier(ObjectProvider<ProviderExecutor> executorProvider) {
        this.executorProvider = executorProvider;
    }

    @Override
    public String component() {
        return CircuitComponents.PARSE;
    }

    @Override
    public CircuitState state() {
        ProviderExecutor executor = executorProvider.getIfAvailable();
        if (executor == null) {
            return CircuitState.CLOSED;
        }
        ProviderCircuitBreaker breaker = executor.breaker();
        for (Capability c : Capability.values()) {
            if (breaker.isOpen(c)) {
                return CircuitState.OPEN;
            }
        }
        return CircuitState.CLOSED;
    }
}
