package com.wikiagent.interfaces.chat;

import com.wikiagent.service.chat.ChatHistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话历史 REST API。
 * GET /api/chat/sessions — 列出全部会话
 * GET /api/chat/sessions/{sessionId}/messages — 获取该会话全部消息
 */
@RestController
@RequestMapping("/api/chat")
public class ChatHistoryController {

    private final ChatHistoryService historyService;

    public ChatHistoryController(ChatHistoryService historyService) {
        this.historyService = historyService;
    }

    /** 列出全部会话（按最近活跃降序）。 */
    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions() {
        return historyService.listSessions();
    }

    /** 获取指定会话的全部消息。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable String sessionId) {
        return historyService.getMessages(sessionId);
    }
}
