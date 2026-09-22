package com.wikiagent.infrastructure.llm;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * v1-v2 §7 LLM 多模型工厂。
 * <p>
 * 按业务用途分别装配 3 个 {@link ChatModel} Bean：
 * <ul>
 *   <li>{@code intentChatModel}：意图识别（{@code wikiagent.routing.intent-model}）</li>
 *   <li>{@code simpleChatModel}：简单任务（{@code wikiagent.routing.simple-model}）</li>
 *   <li>{@code complexChatModel}：复杂任务（{@code wikiagent.routing.complex-model}）</li>
 * </ul>
 * <p>
 * 与 Spring AI Alibaba 自动装配的默认 {@code chatModel} Bean（仅 1 个模型）不冲突，
 * Bean 名不同，使用方按名注入。
 * <p>
 * 当 {@code DASHSCOPE_API_KEY} 为空时（开发期未配置），返回 {@link NoOpChatModel}
 * 避免启动期因 API Key 缺失而崩溃；调用方失败时各自降级。
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.ai.chat.model.ChatModel")
public class DashScopeMultiModelFactory {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiModelFactory.class);

    private final String apiKey;
    private final String intentModel;
    private final String simpleModel;
    private final String complexModel;

    public DashScopeMultiModelFactory(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${wikiagent.routing.intent-model:qwen-flash}") String intentModel,
            @Value("${wikiagent.routing.simple-model:qwen-plus}") String simpleModel,
            @Value("${wikiagent.routing.complex-model:qwen-max}") String complexModel) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.intentModel = intentModel;
        this.simpleModel = simpleModel;
        this.complexModel = complexModel;
    }

    @Bean(name = "intentChatModel")
    public ChatModel intentChatModel() {
        return build(intentModel, "intent");
    }

    @Bean(name = "simpleChatModel")
    @Primary
    public ChatModel simpleChatModel() {
        return build(simpleModel, "simple");
    }

    @Bean(name = "complexChatModel")
    public ChatModel complexChatModel() {
        return build(complexModel, "complex");
    }

    /** API Key 有效 → 真实 DashScopeChatModel；否则 NoOpChatModel。 */
    private ChatModel build(String modelName, String label) {
        if (apiKey.isEmpty()) {
            log.warn("DashScope API Key 为空，{}ChatModel 退化为 NoOpChatModel（model={}）", label, modelName);
            return new NoOpChatModel(modelName);
        }
        try {
            DashScopeApi api = DashScopeApi.builder()
                    .apiKey(apiKey)
                    .build();
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .model(modelName)
                    .build();
            return DashScopeChatModel.builder()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .build();
        } catch (Exception e) {
            log.warn("DashScopeChatModel 构建失败 label={}, model={}: {}；退化为 NoOpChatModel",
                    label, modelName, e.getMessage());
            return new NoOpChatModel(modelName);
        }
    }

    /**
     * 空实现 ChatModel，用于 API Key 缺失或构建失败时避免启动崩溃。
     * call 返回空消息；stream 返回空 Flux（避免抛 UnsupportedOperationException）。
     * 默认 options 用 DashScopeChatOptions 装填模型名，避免实现 ChatOptions 全部抽象方法。
     */
    static final class NoOpChatModel implements ChatModel {

        private final DashScopeChatOptions defaultOptions;

        NoOpChatModel(String modelName) {
            this.defaultOptions = DashScopeChatOptions.builder()
                    .model(modelName)
                    .build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.empty();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return defaultOptions;
        }
    }
}
