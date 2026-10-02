package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

/**
 * milvus 组件熔断供给：只读 {@link RetrievalService} 的 {@code milvusDisabledUntil}
 * （Milvus 检索失败后置为 now+60s，冷却期内走本地中文 bigram 检索）。
 * <p>
 * <b>诚实声明</b>：AC-I 约束不允许编辑检索业务类，该字段当前为私有且无 getter，
 * 采用反射只读；字段结构变化时安全回退 CLOSED 并 WARN，待检索服务开放公开访问器后改为直读。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class MilvusCircuitStateSupplier implements CircuitStateSupplier {

    private static final Logger log = LoggerFactory.getLogger(MilvusCircuitStateSupplier.class);

    private static volatile Field disabledUntilField;
    private static volatile boolean reflectionFailed;

    private final ObjectProvider<RetrievalService> retrievalProvider;

    public MilvusCircuitStateSupplier(ObjectProvider<RetrievalService> retrievalProvider) {
        this.retrievalProvider = retrievalProvider;
    }

    @Override
    public String component() {
        return CircuitComponents.MILVUS;
    }

    @Override
    public CircuitState state() {
        RetrievalService service = retrievalProvider.getIfAvailable();
        return service == null ? CircuitState.CLOSED : stateOf(service);
    }

    /** 反射判定（静态工具，单测可直接传入手工构造的检索服务）。 */
    static CircuitState stateOf(RetrievalService service) {
        if (service == null || reflectionFailed) {
            return CircuitState.CLOSED;
        }
        try {
            Field f = disabledUntilField;
            if (f == null) {
                f = RetrievalService.class.getDeclaredField("milvusDisabledUntil");
                f.setAccessible(true);
                disabledUntilField = f;
            }
            long disabledUntil = f.getLong(service);
            return disabledUntil > System.currentTimeMillis() ? CircuitState.OPEN : CircuitState.CLOSED;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            log.warn("Milvus 熔断状态反射读取失败（回退 CLOSED）: {}", e.getMessage());
            return CircuitState.CLOSED;
        }
    }
}
