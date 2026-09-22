package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/**
 * v4 §6.6.2 知识使用指标事件 JPA DAO。
 */
public interface MetricEventJpaDao extends JpaRepository<MetricEventEntity, Long> {

    List<MetricEventEntity> findByChunkId(String chunkId);

    @Query("SELECT m FROM MetricEventEntity m WHERE m.createdAt >= :since ORDER BY m.createdAt DESC")
    List<MetricEventEntity> findSince(Instant since);

    @Query("SELECT m.chunkId, COUNT(m) FROM MetricEventEntity m WHERE m.eventType = :eventType GROUP BY m.chunkId")
    List<Object[]> countByChunkIdGroupByEventType(String eventType);
}
