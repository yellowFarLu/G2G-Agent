package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;

/**
 * 降级末端的空实现 provider：恒不可用，call 返回空文本。
 * 保证降级链在无真实模型可用时仍有确定性行为，不抛异常。
 */
public class NoOpChatModelProvider implements ChatModelProvider {

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
}
