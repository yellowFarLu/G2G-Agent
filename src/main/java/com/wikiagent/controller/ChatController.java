package com.wikiagent.controller;

import com.wikiagent.dto.ChatRequest;
import com.wikiagent.service.chat.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 对话接口：POST /api/chat，SSE 流式返回。 */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (request.question().length() > 2000) {
            throw new IllegalArgumentException("问题过长（最多 2000 字符）");
        }
        SseEmitter emitter = new SseEmitter(0L); // 不超时，由服务端显式完成
        chatService.chat(request.question().strip(), emitter);
        return emitter;
    }
}
