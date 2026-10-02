package com.wikiagent.domain.llm.spi;

/**
 * 聊天模型提供者 SPI（domain 层接口，基础设施实现）。
 * <p>
 * 实现方负责单次调用的超时控制；重试/熔断/降级编排由
 * 基础设施层 ChatModelProviderChain 承担。
 */
public interface ChatModelProvider {

    /** 同步调用，返回文本 + token 用量 + 耗时。 */
    ChatModelResponse call(ChatModelRequest request);

    /**
     * 结构化输出：要求模型按 JSON Schema 约束输出，返回原始文本。
     * 默认实现：把 schema 约束追加进 system 后走 {@link #call}；
     * 有能力原生支持 response_format 的 provider 可覆盖。
     */
    default String structuredOutput(ChatModelRequest request, String schemaJson) {
        String system = (request.system() == null ? "" : request.system())
                + "\n请严格按以下 JSON Schema 输出，只输出 JSON：\n" + schemaJson;
        ChatModelResponse resp = call(new ChatModelRequest(system, request.user()));
        return resp == null ? null : resp.content();
    }

    /** 是否可用（如 API Key 缺失时返回 false）。 */
    boolean available();

    /** 提供者名（如 dashscope:qwen-plus），用于降级链日志与 fallbackFrom 打点。 */
    String name();
}
