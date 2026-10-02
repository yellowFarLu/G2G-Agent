package com.wikiagent.infrastructure.llm;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 流式聊天候选（基础设施层接口，{@link ChatModelProviderChain} 的同步 SPI 不覆盖流式）。
 * 实现通常同时实现 {@link com.wikiagent.domain.llm.spi.ChatModelProvider}，
 * 直接暴露底层 Spring AI {@link Flux} 以便首包前失败时切换下一个候选。
 */
public interface StreamingChatModelCandidate {

    /** 候选名（如 dashscope:qwen-plus），用于降级链日志与 fallbackFrom 打点。 */
    String name();

    /** 实际模型名（用于打点）。 */
    String model();

    /** 是否可用（如 API Key 缺失时返回 false）。 */
    boolean available();

    /** 流式调用；订阅前/首包前的错误可被 {@link StreamingChatChain} 安全切换。 */
    Flux<ChatResponse> stream(Prompt prompt);
}
