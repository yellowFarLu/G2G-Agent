package com.wikiagent.domain.memory;

import java.util.List;

/**
 * v1-v2 §3 短期记忆端口（DDD 端口接口）。
 * <p>
 * 基于 Redis 存储，最多 20 轮对话，超过触发滚动摘要。
 * 以 session ID + userId 为前缀键。
 */
public interface ShortTermMemoryPort {

    /** 保存一轮对话消息。 */
    void save(String userId, String sessionId, String role, String content);

    /** 读取最近 N 轮对话（按时间顺序）。 */
    List<MessageEntry> load(String userId, String sessionId, int maxTurns);

    /** 读取全部历史对话（不超过 max-turns 上限）。 */
    List<MessageEntry> loadAll(String userId, String sessionId);

    /** 清除指定会话的短期记忆。 */
    void clear(String userId, String sessionId);

    /** 消息条目。 */
    record MessageEntry(String role, String content, long timestamp) {}
}
