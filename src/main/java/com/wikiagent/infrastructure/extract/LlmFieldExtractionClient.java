package com.wikiagent.infrastructure.extract;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 字段提取 LLM 客户端：复用 complexChatModel（复杂任务模型）。
 * 仅负责"提示词 → 模型文本"；JSON 解析/校验/修复重试由应用层编排。
 */
@Component
public class LlmFieldExtractionClient {

    private final ChatModel chatModel;

    public LlmFieldExtractionClient(@Qualifier("complexChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * @param systemPrompt 字段规格与输出格式约束
     * @param documentText 带页标记的文档文本
     * @param repairFeedback 首轮校验错误（null=首轮；非空=修复轮，要求模型据此修正）
     */
    public String call(String systemPrompt, String documentText, String repairFeedback) {
        StringBuilder user = new StringBuilder("文档内容如下：\n").append(documentText);
        if (repairFeedback != null && !repairFeedback.isBlank()) {
            user.append("\n\n上一轮结果未通过校验，请只输出修正后的 JSON，错误如下：\n")
                    .append(repairFeedback);
        }
        try {
            ChatResponse response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(systemPrompt), new UserMessage(user.toString()))));
            String text = response.getResult().getOutput().getText();
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            throw new IllegalStateException("字段提取模型调用失败: " + e.getMessage(), e);
        }
    }
}
