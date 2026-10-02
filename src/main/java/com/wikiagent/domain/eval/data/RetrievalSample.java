package com.wikiagent.domain.eval.data;

import java.time.Instant;

/**
 * 检索事件投影（metric_event RETRIEVED/CITED + 相关性标签）。
 *
 * @param chunkId   chunk 标识（docId 粒度）
 * @param relevant  相关性标签：kb_feedback（USEFUL=true / USELESS=false）或 golden；
 *                  {@code null} 表示无反馈信号，不计入命中率分母（禁止伪造标签）
 * @param eventType 事件类型（RETRIEVED / CITED / ...）
 * @param at        事件时间（窗口过滤依据）
 */
public record RetrievalSample(String chunkId, Boolean relevant, String eventType, Instant at) {
}
