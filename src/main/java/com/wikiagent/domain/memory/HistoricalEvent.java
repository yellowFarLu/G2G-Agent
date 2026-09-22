package com.wikiagent.domain.memory;

import java.time.Instant;
import java.util.List;

/**
 * v1-v2 §6 历史事件库领域模型（值对象）。
 * <p>
 * 存储在 Milvus 向量数据库，使用父子索引（parent-child indexing）。
 * 父文档 = 完整对话摘要，子文档 = 单轮 Q&A。
 */
public record HistoricalEvent(
        String eventId,
        String userId,
        String sessionId,
        String parentId,           // 父事件 ID（null 表示本身是父事件）
        String eventType,          // CONVERSATION_SUMMARY / Q&A / TASK_COMPLETE
        String content,             // 事件内容
        String embedding,           // 向量嵌入（JSON 字符串）
        List<String> metadata,     // 元数据标签
        Instant createdAt
) {
    public boolean isParent() {
        return parentId == null || parentId.isEmpty();
    }
}
