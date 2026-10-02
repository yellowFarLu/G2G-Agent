package com.wikiagent.infrastructure.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 子项目 J（AC-J3）：PII 最小化自动装配。
 * <p>
 * 仅当 {@code wikiagent.security.pii-minimization.enabled=true}（prod 默认，dev 默认 false）时生效：
 * 注册 {@link PiiMinimizer}，并以 BeanPostProcessor 对<b>所有</b>
 * {@code org.springframework.ai.chat.model.ChatModel} Bean 后置包装一层
 * {@link PiiMinimizingChatModelDecorator}，无需改动任何既有服务/Provider 代码。
 */
@Configuration
@ConditionalOnProperty(prefix = "wikiagent.security.pii-minimization",
        name = "enabled", havingValue = "true")
public class PiiMinimizationConfiguration {

    @Bean
    public PiiMinimizer piiMinimizer(PiiMinimizationProperties properties) {
        return PiiMinimizer.fromConfig(properties.getPolicies());
    }

    /**
     * 静态注册：BPP 必须早于普通业务 Bean 实例化；通过 ObjectProvider 惰性取
     * {@link PiiMinimizer}，避免触发其它 Bean 的提前初始化。
     * 高优先级保证包装发生在其它后置处理之前，装饰器仍是唯一对业务可见的 ChatModel 实例。
     */
    @Bean
    @Order(0)
    public static BeanPostProcessor piiMinimizingChatModelPostProcessor(
            ObjectProvider<PiiMinimizer> minimizerProvider,
            ObjectProvider<PiiMinimizingChatModelDecorator.AuditSink> auditSinkProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof org.springframework.ai.chat.model.ChatModel chatModel)
                        || bean instanceof PiiMinimizingChatModelDecorator) {
                    return bean;
                }
                PiiMinimizer minimizer = minimizerProvider.getIfAvailable();
                if (minimizer == null) {
                    return bean;
                }
                PiiMinimizingChatModelDecorator.AuditSink sink = auditSinkProvider.getIfAvailable();
                return sink == null
                        ? new PiiMinimizingChatModelDecorator(chatModel, minimizer)
                        : new PiiMinimizingChatModelDecorator(chatModel, minimizer, sink);
            }
        };
    }
}
