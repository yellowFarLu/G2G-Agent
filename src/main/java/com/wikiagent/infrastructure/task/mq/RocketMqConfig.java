package com.wikiagent.infrastructure.task.mq;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;

/**
 * RocketMQ 投递基础设施（wikiagent.task.enabled=true 且 mq=rocketmq）。
 * Topic TASK_DISPATCH 需预先创建（或开启 broker autoCreateTopicEnable）。
 */
@Configuration
@Conditional(TaskMqConditions.MqRocket.class)
public class RocketMqConfig {

    /** 任务投递主 Topic。 */
    public static final String TOPIC = "TASK_DISPATCH";

    /** 看门狗消息 Tag（仅 cg-task-agent 组订阅，onWatchdog 幂等）。 */
    public static final String TAG_WATCHDOG = "WATCHDOG";

    /** 看门狗消息属性：标记 + 期望 owner。 */
    public static final String PROP_WATCHDOG = "watchdog";
    public static final String PROP_OWNER = "ownerWorkerId";

    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer taskProducer(
            @Value("${ROCKETMQ_NAME_SERVER:127.0.0.1:9876}") String nameServer) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("wikiagent-task-producer");
        producer.setNamesrvAddr(nameServer);
        producer.setSendMsgTimeout(5000);
        producer.setRetryTimesWhenSendFailed(2);
        producer.start();
        return producer;
    }
}
