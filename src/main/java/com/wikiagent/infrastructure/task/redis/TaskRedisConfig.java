package com.wikiagent.infrastructure.task.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Task 6 Redis 协调基础设施（wikiagent.redis.enabled=true）：
 * stream pub/sub 共用的监听容器。
 */
@Configuration
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class TaskRedisConfig {

    @Bean
    public RedisMessageListenerContainer taskRedisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
