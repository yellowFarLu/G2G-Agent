package com.wikiagent.infrastructure.task.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 任务流总线 Redis pub/sub 实现（wikiagent.redis.enabled=true）。
 * 频道：wikiagent:task:stream:{taskId}；消息体为 StreamEvent JSON。
 * 订阅共用 TaskRedisConfig 提供的 {@link RedisMessageListenerContainer}，
 * 每次 subscribe 注册/注销一个监听器（引用由容器管理）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class RedisTaskStreamBus implements TaskStreamBus {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskStreamBus.class);

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisTaskStreamBus(StringRedisTemplate redis,
                              RedisMessageListenerContainer taskRedisMessageListenerContainer) {
        this.redis = redis;
        this.container = taskRedisMessageListenerContainer;
    }

    static String channel(String taskId) {
        return "wikiagent:task:stream:" + taskId;
    }

    @Override
    public void publish(StreamEvent event) {
        try {
            redis.convertAndSend(channel(event.taskId()), mapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("stream 事件发布失败 taskId={}: {}", event.taskId(), e.getMessage());
        }
    }

    @Override
    public AutoCloseable subscribe(String taskId, Consumer<StreamEvent> listener) {
        org.springframework.data.redis.connection.MessageListener redisListener = (message, pattern) -> {
            try {
                StreamEvent event = mapper.readValue(message.getBody(), StreamEvent.class);
                listener.accept(event);
            } catch (Exception e) {
                log.warn("stream 事件解析失败 taskId={}: {}", taskId, e.getMessage());
            }
        };
        container.addMessageListener(redisListener, new ChannelTopic(channel(taskId)));
        return () -> container.removeMessageListener(redisListener, new ChannelTopic(channel(taskId)));
    }
}
