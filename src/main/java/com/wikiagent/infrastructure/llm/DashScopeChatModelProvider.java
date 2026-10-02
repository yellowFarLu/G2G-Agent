package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatModelProvider SPI 的 DashScope 适配器：包装既有
 * {@link DashScopeMultiModelFactory} 创建的 Spring AI {@link ChatModel} Bean。
 * <p>
 * 底层为 NoOpChatModel（API Key 缺失）时 {@link #available()} 返回 false，
 * 由 {@link ChatModelProviderChain} 降级到后续候选。
 */
public class DashScopeChatModelProvider implements ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModelProvider.class);

    private final String name;
    private final String model;
    private final ChatModel chatModel;

    public DashScopeChatModelProvider(String name, String model, ChatModel chatModel) {
        this.name = name;
        this.model = model;
        this.chatModel = chatModel;
    }

    @Override
    public ChatModelResponse call(ChatModelRequest request) {
        long started = System.currentTimeMillis();
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>(2);
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(new SystemMessage(request.system()));
        }
        messages.add(new UserMessage(request.user() == null ? "" : request.user()));
        var resp = chatModel.call(new Prompt(messages));
        long latency = System.currentTimeMillis() - started;

        String content = null;
        if (resp != null && resp.getResult() != null && resp.getResult().getOutput() != null) {
            content = resp.getResult().getOutput().getText();
        }
        Integer promptTokens = null;
        Integer completionTokens = null;
        try {
            if (resp != null && resp.getMetadata() != null) {
                Usage usage = resp.getMetadata().getUsage();
                if (usage != null) {
                    promptTokens = usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens();
                }
            }
        } catch (Exception e) {
            log.debug("读取 token 用量失败（不影响主链路）: {}", e.getMessage());
        }
        return new ChatModelResponse(content, model, promptTokens, completionTokens, latency);
    }

    @Override
    public boolean available() {
        return !(chatModel instanceof DashScopeMultiModelFactory.NoOpChatModel);
    }

    @Override
    public String name() {
        return name;
    }

    /** 实际模型名（用于打点）。 */
    public String model() {
        return model;
    }
}
