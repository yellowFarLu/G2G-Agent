package com.wikiagent.infrastructure.task.mq;

import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 投递器（mq=rocketmq）：普通消息 Tag=taskType（INGEST/AGENT），
 * body=taskId；看门狗消息 Tag=WATCHDOG、附加 watchdog/ownerWorkerId 属性。
 * 发送失败抛异常（提交方/补偿任务处理）。
 */
@Component
@Conditional(TaskMqConditions.MqRocket.class)
public class RocketMqTaskDispatcher implements TaskDispatcherPort {

    private final DefaultMQProducer producer;

    public RocketMqTaskDispatcher(DefaultMQProducer taskProducer) {
        this.producer = taskProducer;
    }

    @Override
    public void dispatch(String taskId, String taskType, int delayLevel) {
        try {
            Message msg = new Message(RocketMqConfig.TOPIC, taskType, taskId.getBytes(StandardCharsets.UTF_8));
            if (delayLevel > 0) {
                msg.setDelayTimeLevel(delayLevel);
            }
            producer.send(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务投递被中断 taskId=" + taskId, e);
        } catch (Exception e) {
            throw new IllegalStateException("任务投递失败 taskId=" + taskId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void dispatchWatchdog(String taskId, String ownerWorkerId, int delayLevel) {
        try {
            Message msg = new Message(RocketMqConfig.TOPIC, RocketMqConfig.TAG_WATCHDOG,
                    taskId.getBytes(StandardCharsets.UTF_8));
            msg.setDelayTimeLevel(Math.max(delayLevel, 4));
            msg.putUserProperty(RocketMqConfig.PROP_WATCHDOG, "true");
            msg.putUserProperty(RocketMqConfig.PROP_OWNER, ownerWorkerId);
            producer.send(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("看门狗投递被中断 taskId=" + taskId, e);
        } catch (Exception e) {
            throw new IllegalStateException("看门狗投递失败 taskId=" + taskId + ": " + e.getMessage(), e);
        }
    }
}
