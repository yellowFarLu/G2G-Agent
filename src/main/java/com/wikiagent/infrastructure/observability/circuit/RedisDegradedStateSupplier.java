package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.observability.circuit.CircuitStateSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * redis 组件降级状态：{@code wikiagent.redis.enabled=false}（开发默认）时协调层
 * （租约/控制标志/进度/stream）运行在 JVM 单机实现，限流亦为单机令牌桶——
 * 即"Redis 不可用→MySQL 行字段/单机协调/单机限流"降级路径正在生效，标记 OPEN=1。
 * <p>
 * <b>诚实声明</b>：enabled=true 但运行时 Redis 连接中断的瞬时探测（Redisson 重连状态）
 * 尚未接入，当前在启用态恒报 CLOSED，待协调组件暴露运行时健康位后补充。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class RedisDegradedStateSupplier implements CircuitStateSupplier {

    private final boolean redisEnabled;

    public RedisDegradedStateSupplier(
            @Value("${wikiagent.redis.enabled:false}") boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    @Override
    public String component() {
        return CircuitComponents.REDIS;
    }

    @Override
    public CircuitState state() {
        return redisEnabled ? CircuitState.CLOSED : CircuitState.OPEN;
    }
}
