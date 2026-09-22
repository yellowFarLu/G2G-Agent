package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * v4 §6.6.2 知识使用指标事件 JPA 实体（V6__feedback_metric.sql - metric_event 表）。
 */
@Entity
@Table(name = "metric_event")
public class MetricEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chunk_id", nullable = false)
    private String chunkId;

    @Column(name = "event_type", nullable = false)
    private String eventType;  // RETRIEVED / CITED / FEEDBACK_USEFUL / FEEDBACK_USELESS

    @Column(name = "user_id")
    private String userId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "similarity")
    private Double similarity;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Double getSimilarity() { return similarity; }
    public void setSimilarity(Double similarity) { this.similarity = similarity; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
