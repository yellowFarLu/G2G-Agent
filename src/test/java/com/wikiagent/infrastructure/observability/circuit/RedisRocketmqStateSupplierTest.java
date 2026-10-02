package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.infrastructure.task.mq.RocketMqHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC-I4 ②③ redis/rocketmq 降级状态供给器单测：
 * 单机协调/本地调度模式 → OPEN（降级路径生效）；启用且健康 → CLOSED。
 */
class RedisRocketmqStateSupplierTest {

    @Test
    void redis未启用时OPEN标记单机降级启用时CLOSED() {
        assertThat(new RedisDegradedStateSupplier(false).state()).isEqualTo(CircuitState.OPEN);
        assertThat(new RedisDegradedStateSupplier(false).component())
                .isEqualTo(CircuitComponents.REDIS);
        assertThat(new RedisDegradedStateSupplier(true).state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void mq为local时OPEN表示本地调度降级() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RocketMqHealthIndicator> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        RocketMqLocalStateSupplier local = new RocketMqLocalStateSupplier("local", empty);
        assertThat(local.component()).isEqualTo(CircuitComponents.ROCKETMQ);
        assertThat(local.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void mq为rocketmq时按健康指示器判定() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RocketMqHealthIndicator> noIndicator = mock(ObjectProvider.class);
        when(noIndicator.getIfAvailable()).thenReturn(null);
        // rocketmq 模式但指示器缺失：保守 OPEN
        assertThat(new RocketMqLocalStateSupplier("rocketmq", noIndicator).state())
                .isEqualTo(CircuitState.OPEN);

        RocketMqHealthIndicator up = mock(RocketMqHealthIndicator.class);
        when(up.health()).thenReturn(Health.up().build());
        @SuppressWarnings("unchecked")
        ObjectProvider<RocketMqHealthIndicator> upProvider = mock(ObjectProvider.class);
        when(upProvider.getIfAvailable()).thenReturn(up);
        assertThat(new RocketMqLocalStateSupplier("rocketmq", upProvider).state())
                .isEqualTo(CircuitState.CLOSED);

        RocketMqHealthIndicator down = mock(RocketMqHealthIndicator.class);
        when(down.health()).thenReturn(Health.down(new RuntimeException("no nameserver")).build());
        @SuppressWarnings("unchecked")
        ObjectProvider<RocketMqHealthIndicator> downProvider = mock(ObjectProvider.class);
        when(downProvider.getIfAvailable()).thenReturn(down);
        assertThat(new RocketMqLocalStateSupplier("rocketmq", downProvider).state())
                .isEqualTo(CircuitState.OPEN);
    }
}
