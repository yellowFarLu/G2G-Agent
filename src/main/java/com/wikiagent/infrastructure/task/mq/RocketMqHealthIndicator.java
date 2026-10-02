package com.wikiagent.infrastructure.task.mq;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 投递健康检查（mq=rocketmq）：producer 存在且连接池可用 → UP。
 * 本地模式（mq=local）不注册。
 */
@Component
@Conditional(TaskMqConditions.MqRocket.class)
public class RocketMqHealthIndicator implements HealthIndicator {

    private final DefaultMQProducer producer;

    public RocketMqHealthIndicator(DefaultMQProducer taskProducer) {
        this.producer = taskProducer;
    }

    @Override
    public Health health() {
        try {
            return Health.up()
                    .withDetail("group", producer.getProducerGroup())
                    .withDetail("nameServer", producer.getNamesrvAddr())
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
