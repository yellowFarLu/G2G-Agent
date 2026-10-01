package com.wikiagent.infrastructure.task.mq;

import com.wikiagent.application.task.TaskMessageSink;
import com.wikiagent.config.TaskProperties;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RocketMQ 消费者（mq=rocketmq）：两个独立消费组实现任务类型级并发控制。
 * cg-task-ingest 订阅 INGEST；cg-task-agent 订阅 AGENT 与 WATCHDOG（看门狗复用
 * agent 组消费线程，onWatchdog 由 owner 校验天然幂等）。
 * 消费正常 CONSUME_SUCCESS；处理异常 RECONSUME_LATER 兜底（主重试走 DB 状态机），
 * 重试上限 3 次，溢出进 %DLQ% 由 TaskDlqConsumer 告警。
 */
@Configuration
@Conditional(TaskMqConditions.MqRocket.class)
public class RocketMqTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(RocketMqTaskConsumer.class);

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer taskIngestConsumer(
            @Value("${ROCKETMQ_NAME_SERVER:127.0.0.1:9876}") String nameServer,
            TaskProperties properties,
            ObjectProvider<TaskMessageSink> sinkProvider) throws Exception {
        return consumer("cg-task-ingest", RocketMqConfig.TOPIC, "INGEST",
                nameServer, properties.getConcurrency().getIngest(), sinkProvider);
    }

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer taskAgentConsumer(
            @Value("${ROCKETMQ_NAME_SERVER:127.0.0.1:9876}") String nameServer,
            TaskProperties properties,
            ObjectProvider<TaskMessageSink> sinkProvider) throws Exception {
        return consumer("cg-task-agent", RocketMqConfig.TOPIC,
                "AGENT || " + RocketMqConfig.TAG_WATCHDOG,
                nameServer, properties.getConcurrency().getAgent(), sinkProvider);
    }

    private DefaultMQPushConsumer consumer(String group, String topic, String tagExpr,
                                           String nameServer, int concurrency,
                                           ObjectProvider<TaskMessageSink> sinkProvider) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(nameServer);
        consumer.subscribe(topic, tagExpr);
        consumer.setConsumeThreadMin(Math.max(concurrency, 1));
        consumer.setConsumeThreadMax(Math.max(concurrency, 1));
        consumer.setMaxReconsumeTimes(3);
        consumer.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently) (messages, context) -> {
            TaskMessageSink sink = sinkProvider.getIfAvailable();
            if (sink == null) {
                log.warn("TaskMessageSink 未装配，丢弃 {} 条消息（开发环境）", messages.size());
                return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            try {
                for (MessageExt message : messages) {
                    String taskId = new String(message.getBody(), StandardCharsets.UTF_8);
                    if ("true".equals(message.getUserProperty(RocketMqConfig.PROP_WATCHDOG))) {
                        sink.onWatchdog(taskId, message.getUserProperty(RocketMqConfig.PROP_OWNER));
                    } else {
                        sink.onMessage(taskId, message.getTags());
                    }
                }
                return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception e) {
                log.error("任务消息处理异常，稍后重投：{}", e.getMessage(), e);
                return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
        log.info("RocketMQ 消费者已启动 group={} topic={} tag={} threads={}", group, topic, tagExpr, concurrency);
        return consumer;
    }
}
