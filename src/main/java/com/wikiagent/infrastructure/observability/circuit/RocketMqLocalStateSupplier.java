package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import com.wikiagent.infrastructure.task.mq.RocketMqHealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * rocketmq 组件降级状态：
 * <ul>
 *   <li>{@code wikiagent.task.mq=local}（开发默认）：任务走 JVM 本地调度，
 *       即"RocketMQ 不可用→本地调度"降级路径生效，标记 OPEN=1；</li>
 *   <li>{@code mq=rocketmq}：有 {@link RocketMqHealthIndicator} 且健康 DOWN 时 OPEN，
 *       其余 CLOSED。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqLocalStateSupplier implements CircuitStateSupplier {

    private final String taskMq;
    private final ObjectProvider<RocketMqHealthIndicator> healthProvider;

    public RocketMqLocalStateSupplier(
            @Value("${wikiagent.task.mq:local}") String taskMq,
            ObjectProvider<RocketMqHealthIndicator> healthProvider) {
        this.taskMq = taskMq;
        this.healthProvider = healthProvider;
    }

    @Override
    public String component() {
        return CircuitComponents.ROCKETMQ;
    }

    @Override
    public CircuitState state() {
        if (!"rocketmq".equalsIgnoreCase(taskMq)) {
            return CircuitState.OPEN;
        }
        RocketMqHealthIndicator indicator = healthProvider.getIfAvailable();
        if (indicator == null) {
            return CircuitState.OPEN;
        }
        try {
            Health health = indicator.health();
            return health != null && Status.UP.equals(health.getStatus())
                    ? CircuitState.CLOSED : CircuitState.OPEN;
        } catch (Exception e) {
            return CircuitState.OPEN;
        }
    }
}
