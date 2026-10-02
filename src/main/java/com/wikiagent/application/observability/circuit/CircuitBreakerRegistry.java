package com.wikiagent.application.observability.circuit;

import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子项目 I（AC-I4）统一熔断器注册表：聚合五类第三方组件（milvus/parse/llm/redis/rocketmq）
 * 的归一化 {@link CircuitState}，并记录各组件最近一次 CLOSED→OPEN 的时间。
 * <p>
 * Prometheus 侧 {@code wikiagent_circuit_state}（AC-I2）从本注册表取数；
 * 无 supplier 的组件默认 CLOSED（注册表固定覆盖 {@link CircuitComponents#ALL}）。
 */
@Component("wikiagentCircuitBreakerRegistry")
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    private final Map<String, CircuitStateSupplier> suppliers = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastOpenedAt = new ConcurrentHashMap<>();
    /** 上一轮快照，用于检测 CLOSED→OPEN 跳变并打时间戳。 */
    private final Map<String, CircuitState> previous = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CircuitBreakerRegistry(List<CircuitStateSupplier> suppliers) {
        for (String component : CircuitComponents.ALL) {
            previous.put(component, CircuitState.CLOSED);
        }
        if (suppliers != null) {
            suppliers.forEach(this::register);
        }
    }

    /** 测试/手工装配：空注册表（默认全 CLOSED）。 */
    public CircuitBreakerRegistry() {
        this(List.of());
    }

    /** 注册（同组件后注册覆盖）。 */
    public void register(CircuitStateSupplier supplier) {
        if (supplier == null || supplier.component() == null) {
            return;
        }
        suppliers.put(supplier.component(), supplier);
        log.info("熔断状态供给器已注册: {}", supplier.component());
    }

    /** 当前状态（supplier 缺失或读取异常 → CLOSED）。 */
    public CircuitState state(String component) {
        CircuitStateSupplier supplier = suppliers.get(component);
        if (supplier == null) {
            return CircuitState.CLOSED;
        }
        try {
            CircuitState s = supplier.state();
            return s == null ? CircuitState.CLOSED : s;
        } catch (Exception e) {
            log.warn("熔断状态读取失败 component={}: {}", component, e.getMessage());
            return CircuitState.CLOSED;
        }
    }

    /** 是否处于 OPEN（降级中）。 */
    public boolean isOpen(String component) {
        return state(component) == CircuitState.OPEN;
    }

    /**
     * 刷新跳变时间戳并返回组件→状态快照（指标绑定时按固定频率调用）。
     * CLOSED→OPEN 记录最近 OPEN 时间；OPEN→CLOSED 保留历史 OPEN 时间（恢复可查）。
     */
    public Map<String, CircuitState> snapshot() {
        Map<String, CircuitState> out = new LinkedHashMap<>();
        for (String component : CircuitComponents.ALL) {
            CircuitState now = state(component);
            CircuitState before = previous.get(component);
            if (now == CircuitState.OPEN && before != CircuitState.OPEN) {
                lastOpenedAt.put(component, Instant.now());
                log.warn("熔断器进入 OPEN（降级开始）: {}", component);
            }
            previous.put(component, now);
            out.put(component, now);
        }
        return out;
    }

    /** 最近一次 OPEN 时间（未发生过返回 null）。 */
    public Instant lastOpenedAt(String component) {
        return lastOpenedAt.get(component);
    }
}
