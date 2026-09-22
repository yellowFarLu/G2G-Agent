package com.wikiagent.infrastructure.trace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * v1-v2 §8 链路追踪 Spring Data JPA Repository。
 * <p>
 * 提供 agent_trace 表的按 session / conversation / user 维度查询。
 */
public interface TraceSpanJpaDao extends JpaRepository<TraceSpanEntity, Long> {

    /** 按 session 查询并按开始时间升序返回（追踪链路顺序）。 */
    List<TraceSpanEntity> findBySessionIdOrderByStartTime(String sessionId);

    /** 按 conversation 查询并按开始时间升序返回。 */
    List<TraceSpanEntity> findByConversationIdOrderByStartTime(String conversationId);

    /** 按 user 查询并按开始时间降序返回（最近优先）。 */
    List<TraceSpanEntity> findByUserIdOrderByStartTimeDesc(String userId);

    /** 按 user 查询最近 limit 条记录（按开始时间降序）。 */
    @Query("SELECT e FROM TraceSpanEntity e WHERE e.userId = :userId ORDER BY e.startTime DESC")
    List<TraceSpanEntity> findRecentByUserId(String userId, org.springframework.data.domain.Pageable pageable);
}
