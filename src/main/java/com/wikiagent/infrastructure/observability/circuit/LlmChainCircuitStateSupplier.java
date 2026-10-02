package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.llm.LlmCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.List;

/**
 * llm 组件熔断供给：读取 {@link ChatModelProviderChain} 内部候选列表与每 provider 独立的
 * {@link LlmCircuitBreaker}，任一 provider 熔断打开即视为组件 OPEN。
 * <p>
 * <b>诚实声明</b>：降级链未公开熔断器 getter（AC-I 约束不允许修改业务类），
 * 这里以反射只读两个私有字段（{@code candidates}、{@code breaker}），字段结构变化时
 * 反射失败安全回退 CLOSED 并打 WARN，不影响主链路；待降级链提供公开访问器后改为直读。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class LlmChainCircuitStateSupplier implements CircuitStateSupplier {

    private static final Logger log = LoggerFactory.getLogger(LlmChainCircuitStateSupplier.class);

    private static volatile Field candidatesField;
    private static volatile Field breakerField;
    private static volatile boolean reflectionFailed;

    private final ObjectProvider<ChatModelProviderChain> chainProvider;

    public LlmChainCircuitStateSupplier(ObjectProvider<ChatModelProviderChain> chainProvider) {
        this.chainProvider = chainProvider;
    }

    @Override
    public String component() {
        return CircuitComponents.LLM;
    }

    @Override
    public CircuitState state() {
        ChatModelProviderChain chain = chainProvider.getIfAvailable();
        return chain == null ? CircuitState.CLOSED : stateOf(chain);
    }

    /** 反射判定（静态工具，单测可直接传入手工构造的降级链）。 */
    @SuppressWarnings("unchecked")
    static CircuitState stateOf(ChatModelProviderChain chain) {
        if (chain == null || reflectionFailed) {
            return CircuitState.CLOSED;
        }
        try {
            Field cf = candidatesField;
            Field bf = breakerField;
            if (cf == null || bf == null) {
                cf = ChatModelProviderChain.class.getDeclaredField("candidates");
                bf = ChatModelProviderChain.class.getDeclaredField("breaker");
                cf.setAccessible(true);
                bf.setAccessible(true);
                candidatesField = cf;
                breakerField = bf;
            }
            List<ChatModelProvider> candidates = (List<ChatModelProvider>) cf.get(chain);
            LlmCircuitBreaker breaker = (LlmCircuitBreaker) bf.get(chain);
            if (candidates == null || breaker == null) {
                return CircuitState.CLOSED;
            }
            for (ChatModelProvider p : candidates) {
                if (p != null && breaker.isOpen(p.name())) {
                    return CircuitState.OPEN;
                }
            }
            return CircuitState.CLOSED;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            log.warn("LLM 熔断状态反射读取失败（回退 CLOSED，待业务类开放 getter）: {}", e.getMessage());
            return CircuitState.CLOSED;
        }
    }
}
