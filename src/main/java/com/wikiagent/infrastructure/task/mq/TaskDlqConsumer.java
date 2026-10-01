package com.wikiagent.infrastructure.task.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 死信消费告警（mq=rocketmq）：订阅两个消费组的 %DLQ% topic，收到即告警。
 * FAIL 状态已由 worker 落库（主真相源 MySQL），此处仅补日志观测。
 */
@Component
@Conditional(TaskMqConditions.MqRocket.class)
public class TaskDlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskDlqConsumer.class);

    private final String nameServer;
    private DefaultMQPushConsumer consumer;

    public TaskDlqConsumer(@Value("${ROCKETMQ_NAME_SERVER:127.0.0.1:9876}") String nameServer) {
        this.nameServer = nameServer;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = new DefaultMQPushConsumer("cg-task-dlq");
        consumer.setNamesrvAddr(nameServer);
        consumer.subscribe("%DLQ%cg-task-ingest", "*");
        consumer.subscribe("%DLQ%cg-task-agent", "*");
        consumer.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                log.error("任务消息进入死信 topic={} body={} msgId={}",
                        message.getTopic(),
                        message.getBody() == null ? "" : new String(message.getBody()),
                        message.getMsgId());
            }
            return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("DLQ 告警消费者已启动");
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
