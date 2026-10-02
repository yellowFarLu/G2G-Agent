package com.wikiagent.domain.llm.spi;

/**
 * 聊天模型调用响应（domain 层 record）。
 *
 * @param content          模型输出文本
 * @param model            实际使用的模型名
 * @param promptTokens     输入 token 数（不可得为 null）
 * @param completionTokens 输出 token 数（不可得为 null）
 * @param latencyMs        调用耗时毫秒
 */
public record ChatModelResponse(String content, String model,
                                Integer promptTokens, Integer completionTokens, Long latencyMs) {
}
