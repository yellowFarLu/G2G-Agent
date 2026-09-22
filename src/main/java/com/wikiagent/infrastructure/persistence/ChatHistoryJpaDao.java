package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;


/**
 * 对话历史 DAO。
 */
public interface ChatHistoryJpaDao extends JpaRepository<ChatHistoryEntity, Long> {

    /** 按会话 ID 查询全部消息（按时间升序）。 */
    List<ChatHistoryEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 列出所有去重的会话 ID（按最近活跃时间降序）。 */
    @Query("SELECT DISTINCT h.sessionId FROM ChatHistoryEntity h ORDER BY h.createdAt DESC")
    List<String> findDistinctSessionIds();
}
