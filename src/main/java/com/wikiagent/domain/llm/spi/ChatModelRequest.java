package com.wikiagent.domain.llm.spi;

/**
 * 聊天模型调用请求（domain 层 record）。
 *
 * @param system 系统提示词（可空）
 * @param user   用户提示词
 */
public record ChatModelRequest(String system, String user) {

    public static ChatModelRequest of(String system, String user) {
        return new ChatModelRequest(system, user);
    }
}
