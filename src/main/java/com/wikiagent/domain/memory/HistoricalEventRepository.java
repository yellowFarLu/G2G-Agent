package com.wikiagent.domain.memory;

import java.util.List;

/**
 * v1-v2 §6 历史事件库端口（DDD 端口接口）。
 * <p>
 * 基于 Milvus 向量数据库，父子索引（parent-child indexing）。
 */
public interface HistoricalEventRepository {

    /** 插入一条历史事件。 */
    void insert(HistoricalEvent event);

    /** 按语义相似度检索历史事件（召回父文档 + 子文档）。 */
    List<HistoricalEvent> search(String query, int topK);

    /** 按 userId 检索该用户的历史事件。 */
    List<HistoricalEvent> findByUserId(String userId, int limit);
}
