package com.wikiagent.service.chat;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SseSender 装饰器：拦截 delta 事件积累助手文本，在 done 时持久化为 assistant 消息。
 */
public class HistorySseSender extends SseSender {

    private final ChatHistoryService historyService;
    private final String sessionId;
    private final StringBuilder assistantText = new StringBuilder();

    public HistorySseSender(SseEmitter emitter, ChatHistoryService historyService, String sessionId) {
        super(emitter);
        this.historyService = historyService;
        this.sessionId = sessionId;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean send(String event, Object data) {
        // 积累助手回答文本
        if ("delta".equals(event) && data instanceof Map) {
            Object text = ((Map<String, Object>) data).get("text");
            if (text != null) {
                assistantText.append(text);
            }
        }
        // done 事件：持久化完整助手回答
        if ("done".equals(event)) {
            String full = assistantText.toString();
            if (!full.isEmpty()) {
                historyService.save(sessionId, "assistant", full);
            }
        }
        return super.send(event, data);
    }
}
