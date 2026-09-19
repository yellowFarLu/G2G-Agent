package com.wikiagent.service.retrieve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询改写：调用大模型将用户问题改写为适合混合检索的独立查询。
 * 改写失败时回退原始问题，不阻断检索链路。
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String SYSTEM = """
            你是企业知识库的检索查询改写器。请将用户输入改写为一句适合混合检索（关键词 BM25 + 语义向量）的独立查询：
            - 补全代词与省略的指代，使其不依赖上下文也能被理解
            - 保留原文中的关键术语、产品名、专有名词，不要同义替换
            - 不要回答问题，不要解释，只输出一行改写后的查询
            """;

    private final ChatModel chatModel;

    public QueryRewriteService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 返回改写后的查询；任何失败均回退原始问题。 */
    public String rewrite(String question) {
        try {
            Prompt prompt = new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(question)));
            var resp = chatModel.call(prompt);
            String out = resp.getResult() == null || resp.getResult().getOutput() == null
                    ? null
                    : resp.getResult().getOutput().getText();
            if (out == null || out.isBlank()) {
                log.warn("查询改写返回空结果，回退原始查询");
                return question;
            }
            // 只取第一行，防止模型输出解释性文字
            return out.strip().lines().findFirst().orElse(question);
        } catch (Exception e) {
            log.warn("查询改写失败，回退原始查询: {}", e.getMessage());
            return question;
        }
    }
}
