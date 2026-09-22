package com.wikiagent.service.chat;

import com.wikiagent.infrastructure.persistence.ChatHistoryEntity;
import com.wikiagent.infrastructure.persistence.ChatHistoryJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 对话历史持久化服务。
 * 保存用户问题和助手回答，支持按会话查询历史列表和消息。
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private final ChatHistoryJpaDao dao;

    public ChatHistoryService(ChatHistoryJpaDao dao) {
        this.dao = dao;
    }

    /** 保存一条消息（user 或 assistant）。 */
    public void save(String sessionId, String role, String content) {
        try {
            dao.save(new ChatHistoryEntity(sessionId, role, content));
        } catch (Exception e) {
            log.warn("保存对话历史失败 sessionId={} role={}: {}", sessionId, role, e.getMessage());
        }
    }

    /** 获取会话列表（按最近活跃降序），每个会话包含预览信息。 */
    public List<Map<String, Object>> listSessions() {
        List<String> sessionIds = dao.findDistinctSessionIds();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sid : sessionIds) {
            List<ChatHistoryEntity> msgs = dao.findBySessionIdOrderByCreatedAtAsc(sid);
            if (msgs.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", sid);
            item.put("messageCount", msgs.size());
            // 取第一条 user 消息作为预览
            String preview = msgs.stream()
                    .filter(m -> "user".equals(m.getRole()))
                    .map(ChatHistoryEntity::getContent)
                    .findFirst()
                    .orElse(msgs.get(0).getContent());
            item.put("preview", preview.length() > 50 ? preview.substring(0, 50) + "…" : preview);
            item.put("lastTime", msgs.get(msgs.size() - 1).getCreatedAt());
            item.put("createdAt", msgs.get(0).getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /** 获取指定会话的全部消息。 */
    public List<Map<String, Object>> getMessages(String sessionId) {
        List<ChatHistoryEntity> msgs = dao.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatHistoryEntity m : msgs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt());
            result.add(item);
        }
        return result;
    }
}
