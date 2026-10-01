package com.wikiagent.infrastructure.task.mq;

import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 投递层组合条件：任务框架开启（wikiagent.task.enabled=true）且 mq 指向对应实现。
 * mq 默认 local（matchIfMissing）。
 */
public final class TaskMqConditions {

    private TaskMqConditions() {
    }

    public static class MqLocal extends AllNestedConditions {
        public MqLocal() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
        static class TaskEnabled {
        }

        @ConditionalOnProperty(name = "wikiagent.task.mq", havingValue = "local", matchIfMissing = true)
        static class MqIsLocal {
        }
    }

    public static class MqRocket extends AllNestedConditions {
        public MqRocket() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
        static class TaskEnabled {
        }

        @ConditionalOnProperty(name = "wikiagent.task.mq", havingValue = "rocketmq")
        static class MqIsRocket {
        }
    }
}
