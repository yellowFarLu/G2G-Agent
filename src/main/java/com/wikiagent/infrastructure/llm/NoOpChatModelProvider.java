package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 降级末端的空实现 provider：恒不可用，call 返回空文本，stream 返回空 Flux。
 * 保证降级链在无真实模型可用时仍有确定性行为，不抛异常。
 */
public class NoOpChatModelProvider implements ChatModelProvider, StreamingChatModelCandidate {

    private final String model;

    public NoOpChatModelProvider(String model) {
        this.model = model == null ? "noop" : model;
    }

    @Override
    public ChatModelResponse call(ChatModelRequest request) {
        return new ChatModelResponse("", model, 0, 0, 0L);
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String name() {
        return "noop:" + model;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.empty();
    }
}
